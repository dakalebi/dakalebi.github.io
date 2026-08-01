package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ge.dakalebi.app.Log
import ge.dakalebi.app.Prefs
import ge.dakalebi.app.Route
import ge.dakalebi.app.Router
import ge.dakalebi.app.Toasts
import ge.dakalebi.app.formatTime
import ge.dakalebi.auth.AuthStore
import ge.dakalebi.data.Episode
import ge.dakalebi.data.EpisodeRepository
import ge.dakalebi.data.Library
import ge.dakalebi.formula.orderedQualityLabels
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.ui.player.AirPlayClock
import ge.dakalebi.ui.player.CustomVideoPlayer
import ge.dakalebi.ui.player.NativeVideoPlayer
import ge.dakalebi.ui.player.PlayerEvents
import ge.dakalebi.ui.player.isAppleMobile
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Seconds before the end at which the next-episode card appears. */
private const val PROMPT_WINDOW = 180.0

/**
 * Assigning `currentTime` throws on an element that is not seekable yet. That
 * is expected during the resume retry loop, but a seek that keeps failing is
 * exactly why someone would report "it never remembers where I was".
 */
private fun seekTo(video: HTMLVideoElement, target: Double, stage: String) {
    runCatching { video.currentTime = target }
        .onFailure { Log.w("resume", "seek to ${target}s rejected at stage=$stage", it) }
}

/** Save at most this often during continuous playback. */
private const val SAVE_EVERY = 7.0

private class WatchRefs {
    var video: HTMLVideoElement? = null
    var lastSaved: Double = 0.0
    var retried: Boolean = false
    var navigated: Boolean = false

    // Resume state machine
    var resumeApplied: Boolean = false
    var resumeAttempts: Int = 0
    var resumeSeekIssued: Boolean = false
    var resumeTimer: Int? = null

    val airplay = AirPlayClock()
    var airplayTimer: Int? = null
}

@Composable
fun WatchScreen(episodeId: String) {
    val scope = rememberCoroutineScope()
    val uid = AuthStore.uid
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

    LaunchedEffect(uid) { if (uid != null) Library.ensureLoaded(uid) }

    val episode = Library.byId(episodeId)
    val entry = episode?.let { Library.progress[it.id] }
    val watched = entry?.isWatched == true
    val savedSeconds = entry?.progressSeconds ?: 0
    val progressReady = !Library.loading
    val nextEpisode = episode?.let { Library.next(it) }
    val autoplay = Prefs.autoplayNext

    val shouldAutoplay = remember(episodeId) {
        Prefs.playIntent(episodeId) != "paused"
    }

    // --------------------------------------------------------- resolution

    LaunchedEffect(episode?.id) {
        val current = episode ?: return@LaunchedEffect
        resolving = true
        error = null
        val resolved = EpisodeRepository.resolveVideo(current)
        if (resolved !== current) Library.putEpisode(resolved)

        // One map for everything downstream. Previously the label was derived
        // from the freshly resolved sources while the menu was handed
        // `episode.sources` — the stored copy, in a different order — so the
        // two disagreed about which rendition was best.
        val sources = resolved.sources.ifEmpty { current.sources }
        available = sources
        val preferred = Prefs.preferredQuality?.takeIf { sources.containsKey(it) }
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
                Library.saveProgress(
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
        Router.go(Route.Watch(next.id))
    }

    // ---------------------------------------------------------------- ui

    if (episode == null) {
        Div {
            WatchNav(null)
            Div({ classes("center-note") }) {
                Text(if (Library.loading) S.loading else S.episodeNotFound)
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
            Prefs.setPlayIntent(episodeId, "playing")
            if (refs.airplay.active) startAirplayTicker()
        },
        onPause = {
            stopAirplayTicker()
            Prefs.setPlayIntent(episodeId, "paused")
            persist()
        },
        onEnded = {
            Prefs.setPlayIntent(episodeId, "paused")
            persist(isWatched = true)
            ended = true
            Toasts.ok(S.markedAsWatched)
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
                Toasts.show(S.retryingVideo)
                scope.launch {
                    val resolved = EpisodeRepository.resolveVideo(episode)
                    Library.putEpisode(resolved)
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

    val showPrompt = nextEpisode != null &&
        duration != null &&
        remaining != null &&
        remaining!! <= PROMPT_WINDOW &&
        !ended &&
        !promptDismissed &&
        !isAppleMobile

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
                if (isAppleMobile) {
                    NativeVideoPlayer(src = url, autoPlay = shouldAutoplay, events = events)
                } else {
                    CustomVideoPlayer(
                        src = url,
                        autoPlay = shouldAutoplay,
                        sources = available.ifEmpty { episode.sources },
                        quality = quality,
                        onQualitySelected = { label ->
                            quality = label
                            Prefs.setPreferredQuality(label)
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
                Div({ classes("eyebrow") }) {
                    Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
                }
                H1({ classes("watch-h") }) {
                    Text((episode.title ?: S.episode(episode.episodeNumber)).caps)
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
                        onClick { persist(isWatched = true); Toasts.ok(S.episodeMarkedWatched) }
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
                episodes = Library.previous(episode, 4),
                progress = Library.progress,
            )
        }

            Div({ classes("watch-aside") }) {
                UpNextList(
                    title = S.nextEpisodes.caps,
                    episodes = Library.upcoming(episode, 12),
                    progress = Library.progress,
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
                        runCatching { Library.clearProgress(id, episode.id) }
                            .onSuccess {
                                refs.video?.currentTime = 0.0
                                refs.lastSaved = 0.0
                                ended = false
                                Toasts.ok(S.progressCleared)
                            }
                            .onFailure {
                                Log.e("progress", "clear failed for ${episode.id}", it)
                                Toasts.error(S.clearFailed)
                            }
                    }
                }
            },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun WatchNav(episode: Episode?) {
    Div({ classes("nav", "nav-solid") }) {
        A(href = Router.href(Route.Dashboard), attrs = { classes("btn", "btn-quiet") }) {
            Icon(Icons.back)
            Text(S.back.caps)
        }
        episode?.let {
            Span({ classes("eyebrow-mut") }) {
                Text(S.seasonAndEpisode(it.seasonNumber, it.episodeNumber).caps)
            }
        }
    }
}

@Composable
private fun NextEpisodeCard(
    episode: Episode,
    countdown: Double,
    autoplayOn: Boolean,
    onPlayNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    Div({ classes("nextcard") }) {
        Div({ classes("nextcard-th") }) { Thumb(episode, showLabel = false) }
        Div({ classes("nextcard-b") }) {
            Div({ classes("eyebrow") }) { Text(S.nextEpisode.caps) }
            Div({ classes("nextcard-t") }) {
                Text((episode.title ?: S.episode(episode.episodeNumber)).caps)
            }
            Div({ classes("nextcard-s") }) {
                Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
            }
            if (autoplayOn) {
                Div({ classes("nextcard-bar") }) {
                    Div({ style { property("width", "${(countdown * 100).coerceIn(0.0, 100.0)}%") } })
                }
            }
            Div({ classes("nextcard-row") }) {
                Button({ classes("btn", "btn-primary"); style { property("padding", "7px 13px") }; onClick { onPlayNext() } }) {
                    Text(S.watch.caps)
                }
                Button({ classes("btn", "btn-quiet"); onClick { onDismiss() } }) { Text(S.dismiss.caps) }
            }
        }
    }
}
