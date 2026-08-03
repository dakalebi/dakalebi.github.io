package ge.dakalebi.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.di.LocalResolveEpisodeVideo
import ge.dakalebi.di.catalog
import ge.dakalebi.di.preferences
import ge.dakalebi.di.router
import ge.dakalebi.di.session
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.domain.service.orderedQualityLabels
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.Route
import ge.dakalebi.ui.ConfirmDialog
import ge.dakalebi.ui.EpisodeRow
import ge.dakalebi.ui.ExternalLink
import ge.dakalebi.ui.UpNextList
import ge.dakalebi.ui.player.CustomVideoPlayer
import ge.dakalebi.ui.player.NativeVideoPlayer
import ge.dakalebi.ui.player.PlayerEvents
import ge.dakalebi.ui.player.isAppleMobile
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@Composable
fun WatchScreen(episodeId: String) {
    val session = session()
    val catalog = catalog()
    val toasts = toasts()
    val router = router()
    val prefs = preferences()
    val resolveVideo = LocalResolveEpisodeVideo.current

    val scope = rememberCoroutineScope()
    val uid = session.uid
    val refs = remember(episodeId) { WatchRefs() }

    var videoUrl by remember(episodeId) { mutableStateOf<String?>(null) }
    var quality by remember(episodeId) { mutableStateOf<String?>(null) }
    // The renditions the menu offers. Held here so the label and the menu are
    // always reading the same map — see the resolution effect below.
    var available by remember(episodeId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember(episodeId) { mutableStateOf<String?>(null) }
    var ended by remember(episodeId) { mutableStateOf(false) }
    var remaining by remember(episodeId) { mutableStateOf<Double?>(null) }
    var duration by remember(episodeId) { mutableStateOf<Double?>(null) }
    var promptDismissed by remember(episodeId) { mutableStateOf(false) }
    var resolving by remember(episodeId) { mutableStateOf(true) }
    var confirmReset by remember(episodeId) { mutableStateOf(false) }

    LaunchedEffect(uid) { if (uid != null) catalog.ensureLoaded(uid) }

    val episode = catalog.byId(episodeId)
    val entry = episode?.let { catalog.progress[it.id] }
    val watched = entry?.isWatched == true
    val savedSeconds = entry?.progressSeconds ?: 0
    val progressReady = !catalog.loading
    val nextEpisode = episode?.let { catalog.next(it) }
    val autoplay = settings().autoplayNext

    val shouldAutoplay = remember(episodeId) {
        prefs.playIntent(episodeId) != "paused"
    }

    // --------------------------------------------------------- resolution

    LaunchedEffect(episode?.id) {
        val current = episode ?: return@LaunchedEffect
        resolving = true
        error = null
        val resolved = resolveVideo(current)
        if (resolved !== current) catalog.putEpisode(resolved)

        // One map for everything downstream. Previously the label was derived
        // from the freshly resolved sources while the menu was handed
        // `episode.sources` — the stored copy, in a different order — so the
        // two disagreed about which rendition was best.
        val sources = resolved.sources.ifEmpty { current.sources }
        available = sources
        val preferred = prefs.preferredQuality?.takeIf { sources.containsKey(it) }
        quality = preferred ?: orderedQualityLabels(sources).firstOrNull()
        videoUrl = preferred?.let { sources[it] }
            ?: resolved.videoUrl
            ?: quality?.let { sources[it] }

        if (videoUrl == null) error = S.videoLinkFailed
        resolving = false
    }

    // -------------------------------------------------------- persistence

    fun effectiveTime(video: HTMLVideoElement): Double = refs.airplay.currentTime(video)
    fun effectiveDuration(video: HTMLVideoElement): Double? = refs.airplay.duration(video)

    fun persist(isWatched: Boolean? = null, allowReset: Boolean = false) {
        val video = refs.video ?: return
        val current = episode ?: return
        val id = uid ?: return
        val seconds = floor(effectiveTime(video)).toInt()
        if (seconds < 1 && isWatched == null && !allowReset) return
        val dur = effectiveDuration(video)?.let { floor(it).toInt() }

        scope.launch {
            runCatching {
                catalog.saveProgress(
                    uid = id,
                    episodeId = current.id,
                    progressSeconds = if (allowReset) 0 else seconds,
                    durationSeconds = dur,
                    isWatched = isWatched,
                    allowReset = allowReset,
                )
            }.onFailure {
                // Silent until now, which meant a rules or network problem
                // looked exactly like "the app forgot where I was".
                Log.e("progress", "save failed for ${current.id} at ${seconds}s", it)
            }
        }
    }

    DisposableEffect(episode?.id, uid) {
        val onHidden: (Event) -> Unit = {
            // Backgrounding a tab on mobile often never fires beforeunload.
            if (document.asDynamic().visibilityState == "hidden") persist()
        }
        val onUnload: (Event) -> Unit = { persist() }
        document.addEventListener("visibilitychange", onHidden)
        window.addEventListener("beforeunload", onUnload)
        onDispose {
            document.removeEventListener("visibilitychange", onHidden)
            window.removeEventListener("beforeunload", onUnload)
            refs.resumeTimer?.let { window.clearTimeout(it) }
            refs.airplayTimer?.let { window.clearInterval(it) }
            persist()
        }
    }

    // ------------------------------------------------------------- resume

    /**
     * Seeks to the saved position once the element can actually accept it.
     *
     * iOS silently ignores a seek issued too early, so this retries across
     * loadedmetadata → loadeddata → canplay → seeked with a short backoff, and
     * gives up after eight attempts rather than looping forever. Deliberately
     * never pauses the element while waiting: pausing breaks the AirPlay
     * handshake and leaves the receiver playing while the phone reports 0:00.
     */
    fun applyResume(video: HTMLVideoElement, stage: String) {
        if (!progressReady || refs.resumeApplied) return
        val dur = effectiveDuration(video) ?: return
        if (dur <= 0) return

        val target = min(savedSeconds.toDouble(), max(0.0, dur - 5.0))
        duration = dur

        if (target <= 1.0) {
            refs.resumeApplied = true
            return
        }

        refs.lastSaved = target
        remaining = max(0.0, dur - target)

        if (isAppleMobile && stage == "metadata") return

        fun scheduleRetry(nextStage: String, delay: Int) {
            refs.resumeTimer?.let { window.clearTimeout(it) }
            if (refs.resumeAttempts >= 8) return
            refs.resumeAttempts += 1
            refs.resumeTimer = window.setTimeout({
                refs.resumeTimer = null
                refs.video?.let { applyResume(it, nextStage) }
            }, delay)
        }

        if (isAppleMobile && stage != "seeked") {
            refs.resumeSeekIssued = true
            seekTo(video, target, stage)
            scheduleRetry("canplay", 300)
            return
        }

        val close = kotlin.math.abs(video.currentTime - target) < 0.75
        if (!close || (isAppleMobile && !refs.resumeSeekIssued)) {
            seekTo(video, target, stage)
        }

        if (kotlin.math.abs(video.currentTime - target) < 0.75) {
            refs.resumeApplied = true
            refs.resumeAttempts = 0
            refs.resumeSeekIssued = false
            refs.resumeTimer?.let { window.clearTimeout(it) }
            refs.resumeTimer = null
            return
        }

        scheduleRetry("data", 250)
    }

    // ----------------------------------------------------- airplay ticker

    fun stopAirplayTicker() {
        refs.airplayTimer?.let { window.clearInterval(it) }
        refs.airplayTimer = null
    }

    fun startAirplayTicker() {
        if (!isAppleMobile || !refs.airplay.active || refs.airplayTimer != null) return
        refs.airplayTimer = window.setInterval({
            val video = refs.video ?: return@setInterval
            if (!video.paused && !video.ended) {
                val dur = effectiveDuration(video)
                if (dur != null && dur > 0) {
                    duration = dur
                    remaining = max(0.0, dur - effectiveTime(video))
                }
            }
        }, 500)
    }

    // -------------------------------------------------------- navigation

    fun goToNext() {
        val next = nextEpisode ?: return
        if (refs.navigated) return
        refs.navigated = true
        val video = refs.video
        val nearEnd = video != null &&
            video.duration.isFinite() && video.duration > 0 &&
            video.currentTime / video.duration >= 0.9
        persist(isWatched = if (nearEnd || ended) true else null)
        router.go(Route.Watch(next.id))
    }

    // ---------------------------------------------------------------- ui

    if (episode == null) {
        Div {
            WatchNav(null)
            Div({ classes("center-note") }) {
                Text(if (catalog.loading) S.loading else S.episodeNotFound)
            }
        }
        return
    }

    val events = PlayerEvents(
        onElement = { refs.video = it },
        onLoadedMetadata = { video ->
            refs.airplay.sync(video)
            effectiveDuration(video)?.let { duration = it }
            applyResume(video, "metadata")
        },
        onLoadedData = { video ->
            refs.airplay.sync(video)
            applyResume(video, "data")
        },
        onCanPlay = { video ->
            refs.airplay.sync(video)
            effectiveDuration(video)?.let { duration = it }
            applyResume(video, "canplay")
        },
        onDurationChange = { video ->
            refs.airplay.sync(video)
            effectiveDuration(video)?.let { duration = it }
        },
        onTimeUpdate = { video ->
            refs.airplay.sync(video)
            val time = effectiveTime(video)
            if (time - refs.lastSaved >= SAVE_EVERY) {
                refs.lastSaved = time
                persist()
            }
            val dur = effectiveDuration(video)
            if (dur != null && dur > 0) {
                duration = dur
                val left = max(0.0, dur - time)
                remaining = left
                // Re-arm the prompt if the viewer seeks back out of the window.
                if (left > PROMPT_WINDOW && promptDismissed) promptDismissed = false
            }
        },
        onSeeked = { video ->
            refs.airplay.sync(video)
            applyResume(video, "seeked")
        },
        onPlay = {
            prefs.setPlayIntent(episodeId, "playing")
            if (refs.airplay.active) startAirplayTicker()
        },
        onPause = {
            stopAirplayTicker()
            prefs.setPlayIntent(episodeId, "paused")
            persist()
        },
        onEnded = {
            prefs.setPlayIntent(episodeId, "paused")
            persist(isWatched = true)
            ended = true
            toasts.ok(S.markedAsWatched)
            if (autoplay && nextEpisode != null) goToNext()
        },
        onError = {
            Log.e(
                "player",
                "media error for ${episode.id} " +
                    "(code=${refs.video?.asDynamic()?.error?.code}, src=$videoUrl)",
            )
            if (!refs.retried) {
                refs.retried = true
                toasts.show(S.retryingVideo)
                scope.launch {
                    val resolved = resolveVideo(episode)
                    catalog.putEpisode(resolved)
                    videoUrl = resolved.videoUrl
                    if (resolved.videoUrl == null) error = S.videoLoadFailed
                }
            } else {
                error = S.videoLoadFailed
            }
        },
        onAirPlayChange = { active, video ->
            refs.airplay.active = active
            if (active) {
                refs.airplay.sync(video)
                startAirplayTicker()
            } else {
                stopAirplayTicker()
                effectiveDuration(video)?.let { duration = it }
            }
        },
    )

    // Which player is on screen. The device decides whether there is a choice
    // at all; the preference decides how it was answered. Everything else that
    // tests `isAppleMobile` is about the device's own quirks — resume, seeking,
    // AirPlay — and those hold whichever player is drawing the controls.
    val nativePlayer = isAppleMobile && prefs.useNativePlayer

    // No overlay over a native player: it draws its own fullscreen surface and
    // nothing of ours survives on top of it.
    val showPrompt = nextEpisode != null &&
        duration != null &&
        remaining != null &&
        remaining!! <= PROMPT_WINDOW &&
        !ended &&
        !promptDismissed &&
        !nativePlayer

    Div {
        WatchNav(episode)

        // Two columns above 1180px, the way YouTube lays this out: the player
        // and everything about this episode on the left, what to watch next on
        // the right. A full-bleed player left the page with nothing beside it
        // and pushed every rail below the fold.
        Div({ classes("watch-grid") }) {
        Div({ classes("watch-main") }) {
        Div({ classes("player-frame") }) {
        val url = videoUrl
        if (url != null && progressReady) {
            key(episode.id) {
                if (nativePlayer) {
                    NativeVideoPlayer(src = url, autoPlay = shouldAutoplay, events = events)
                } else {
                    CustomVideoPlayer(
                        src = url,
                        autoPlay = shouldAutoplay,
                        sources = available.ifEmpty { episode.sources },
                        quality = quality,
                        onQualitySelected = { label ->
                            quality = label
                            prefs.setPreferredQuality(label)
                            available[label]?.let { videoUrl = it }
                        },
                        events = events,
                        overlay = {
                            if (showPrompt && nextEpisode != null) {
                                NextEpisodeCard(
                                    episode = nextEpisode,
                                    countdown = ((PROMPT_WINDOW - (remaining ?: 0.0)) / PROMPT_WINDOW),
                                    autoplayOn = autoplay,
                                    onPlayNext = { goToNext() },
                                    onDismiss = { promptDismissed = true },
                                )
                            }
                        },
                    )
                }
            }
        } else {
            Div({ classes("player") }) {
                Div({ classes("center-note"); style { property("min-height", "100%") } }) {
                    Text(if (error != null) error!! else S.loading)
                }
            }
        }
        }

        Div({ classes("watch-body") }) {
            Div {
                H1({ classes("watch-h") }) {
                    Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
                }
            }

            if (error != null) {
                Div({ classes("notice") }) { Text(error!!) }
            }

            if (ended && nextEpisode != null) {
                Div({ classes("ended") }) {
                    Div({ classes("grow") }) {
                        Div({ style { property("font-weight", "650") } }) { Text(S.episodeFinished.caps) }
                        Div({ classes("nextcard-s") }) {
                            Text(S.nextUp(nextEpisode.seasonNumber, nextEpisode.episodeNumber).caps)
                        }
                    }
                    Button({ classes("btn", "btn-primary"); onClick { goToNext() } }) {
                        Text(S.nextEpisodeAction.caps)
                    }
                }
            }

            Div({ classes("watch-acts") }) {
                Button({
                    classes("btn", "btn-ghost")
                    onClick {
                        val video = refs.video
                        if (video != null) {
                            video.currentTime = 0.0
                            refs.lastSaved = 0.0
                            ended = false
                            persist(allowReset = true)
                            video.play()
                        }
                    }
                }) { Text(S.watchFromStart.caps) }

                if (watched) {
                    Button({
                        classes("btn", "btn-ghost")
                        style { property("color", "var(--ok)"); property("border-color", "rgba(62,207,142,.45)") }
                        onClick { confirmReset = true }
                    }) { Text(S.watchedTick.caps) }
                } else {
                    Button({
                        classes("btn", "btn-ghost")
                        onClick { persist(isWatched = true); toasts.ok(S.episodeMarkedWatched) }
                    }) { Text(S.markAsWatched.caps) }
                }

                if (nextEpisode != null) {
                    Button({ classes("btn", "btn-primary"); onClick { goToNext() } }) {
                        Text(S.nextEpisodeAction.caps)
                    }
                }

                ExternalLink(episode.episodePageUrl, S.openOnFormula.caps)
            }
        }

            // Four, oldest on the left, in a row that does not scroll.
            EpisodeRow(
                title = S.previousEpisodes.caps,
                episodes = catalog.previous(episode, 4),
                progress = catalog.progress,
            )
        }

            Div({ classes("watch-aside") }) {
                UpNextList(
                    title = S.nextEpisodes.caps,
                    episodes = catalog.upcoming(episode, 12),
                    progress = catalog.progress,
                )
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = S.clearProgressTitle,
            body = S.clearProgressBody,
            confirmLabel = S.delete.caps,
            destructive = true,
            onConfirm = {
                confirmReset = false
                val id = uid
                if (id != null) {
                    scope.launch {
                        runCatching { catalog.clearProgress(id, episode.id) }
                            .onSuccess {
                                refs.video?.currentTime = 0.0
                                refs.lastSaved = 0.0
                                ended = false
                                toasts.ok(S.progressCleared)
                            }
                            .onFailure {
                                Log.e("progress", "clear failed for ${episode.id}", it)
                                toasts.error(S.clearFailed)
                            }
                    }
                }
            },
            onDismiss = { confirmReset = false },
        )
    }
}
