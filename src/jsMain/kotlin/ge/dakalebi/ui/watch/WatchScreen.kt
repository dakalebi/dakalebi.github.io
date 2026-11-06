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
import ge.dakalebi.core.formatTime
import ge.dakalebi.di.catalog
import ge.dakalebi.di.collectState
import ge.dakalebi.di.rememberPlaybackViewModel
import ge.dakalebi.di.sessionState
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.service.COMPACT_SCREEN_MAX_QUALITY
import ge.dakalebi.domain.service.vlcOpenUrl
import ge.dakalebi.domain.service.withStartFragment
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.presentation.Route
import ge.dakalebi.ui.ConfirmDialog
import ge.dakalebi.ui.EpisodeRow
import ge.dakalebi.ui.ExternalLink
import ge.dakalebi.ui.UpNextList
import ge.dakalebi.ui.drive.driveHref
import ge.dakalebi.ui.player.CustomVideoPlayer
import ge.dakalebi.ui.player.NativeVideoPlayer
import ge.dakalebi.ui.player.PlayerEvents
import ge.dakalebi.ui.player.isAppleMobile
import ge.dakalebi.ui.player.isCompactScreen
import io.github.bchmsl.keel.components.Callout
import io.github.bchmsl.keel.components.CalloutBody
import io.github.bchmsl.keel.components.CalloutTone
import io.github.bchmsl.keel.components.LinkButton
import io.github.bchmsl.keel.dom.buttonClasses
import io.github.bchmsl.keel.components.ButtonVariant
import io.github.bchmsl.keel.dom.classNames
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.math.abs

/**
 * One episode, on a phone or a computer.
 *
 * What a position means, which file plays and when to save are the
 * [ge.dakalebi.presentation.PlaybackViewModel]'s. What is left here is this
 * browser's: the element, the resume retry loop iOS needs, the AirPlay clock,
 * the page-hide saves and the toasts.
 */
@Composable
fun WatchScreen(episodeId: String) {
    val sessionState = sessionState()
    val catalog = catalog()
    val toasts = toasts()
    val settings = settings()

    val scope = rememberCoroutineScope()
    val playback = rememberPlaybackViewModel(
        episodeId = episodeId,
        // A phone starts at 720p, because the 1080p file is the slowest to start
        // and the screen hides the difference.
        maxQuality = COMPACT_SCREEN_MAX_QUALITY.takeIf { isCompactScreen },
        appleMobile = isAppleMobile,
    )
    val ui by playback.collectState()
    // Whose progress this is: the account's, or a guest's on this device.
    val uid = sessionState.viewerId
    val refs = remember(episodeId) { WatchRefs() }
    var confirmReset by remember(episodeId) { mutableStateOf(false) }

    LaunchedEffect(uid) { if (uid != null) catalog.ensureLoaded(uid) }

    val episode = catalog.byId(episodeId)
    val entry = episode?.let { catalog.progress[it.id] }
    val watched = entry?.isWatched == true
    val savedSeconds = entry?.progressSeconds ?: 0
    val progressReady = !catalog.loading
    val nextEpisode = episode?.let { catalog.next(it) }

    LaunchedEffect(episode?.id) {
        val current = episode ?: return@LaunchedEffect
        playback.resolve(current)
    }

    DisposableEffect(playback) {
        val onHidden: (Event) -> Unit = {
            // Backgrounding a tab on mobile often never fires beforeunload.
            if (document.asDynamic().visibilityState == "hidden") playback.save()
        }
        val onUnload: (Event) -> Unit = { playback.save() }
        document.addEventListener("visibilitychange", onHidden)
        window.addEventListener("beforeunload", onUnload)
        onDispose {
            document.removeEventListener("visibilitychange", onHidden)
            window.removeEventListener("beforeunload", onUnload)
            refs.resumeTimer?.let { window.clearTimeout(it) }
            refs.airplayTimer?.let { window.clearInterval(it) }
            playback.save()
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
        val target = playback.resumeTarget() ?: return

        if (isAppleMobile && stage == "metadata") return

        fun settle() {
            playback.resumeLanded()
            refs.resumeAttempts = 0
            refs.resumeSeekIssued = false
            refs.resumeTimer?.let { window.clearTimeout(it) }
            refs.resumeTimer = null
        }

        // Already there before any seek of ours: the `#t=` start on the source did it,
        // and seeking again would only stall the element on a position it has. Once
        // we have seeked, iOS can report the target while having ignored the seek, so
        // from then on only the retry loop below decides.
        if (!refs.resumeSeekIssued && abs(video.currentTime - target) < 0.75) {
            settle()
            return
        }

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

        val close = abs(video.currentTime - target) < 0.75
        if (!close || (isAppleMobile && !refs.resumeSeekIssued)) {
            seekTo(video, target, stage)
        }

        if (abs(video.currentTime - target) < 0.75) {
            settle()
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
            if (!video.paused && !video.ended) playback.onTick()
        }, 500)
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
        onElement = { video ->
            refs.video = video
            playback.attach(video?.let { ElementPlayhead(it, refs.airplay) })
        },
        onLoadedMetadata = { video ->
            refs.airplay.sync(video)
            playback.onDurationKnown()
            applyResume(video, "metadata")
        },
        onLoadedData = { video ->
            refs.airplay.sync(video)
            applyResume(video, "data")
        },
        onCanPlay = { video ->
            refs.airplay.sync(video)
            playback.onDurationKnown()
            applyResume(video, "canplay")
        },
        onDurationChange = { video ->
            refs.airplay.sync(video)
            playback.onDurationKnown()
        },
        onTimeUpdate = { video ->
            refs.airplay.sync(video)
            playback.onTimeUpdate()
        },
        onSeeked = { video ->
            refs.airplay.sync(video)
            applyResume(video, "seeked")
        },
        onPlay = {
            playback.onPlay()
            if (refs.airplay.active) startAirplayTicker()
        },
        onPause = {
            stopAirplayTicker()
            playback.onPause()
        },
        onEnded = {
            playback.onEnded()
            toasts.ok(S.markedAsWatched)
            // No autoplay into the next episode for now: the new page swaps the video
            // element, which closes fullscreen and on an iPhone often cannot start
            // without a tap. The card and the Next button stay.
        },
        onError = {
            val code = refs.video?.asDynamic()?.error?.code as? Int
            if (playback.onMediaError(code)) toasts.show(S.retryingVideo)
        },
        onAirPlayChange = { active, video ->
            refs.airplay.active = active
            if (active) {
                refs.airplay.sync(video)
                startAirplayTicker()
            } else {
                stopAirplayTicker()
                playback.onDurationKnown()
            }
        },
    )

    // Which player is on screen. The device decides whether there is a choice
    // at all; the preference decides how it was answered. Everything else that
    // tests `isAppleMobile` is about the device's own quirks — resume, seeking,
    // AirPlay — and those hold whichever player is drawing the controls.
    val nativePlayer = isAppleMobile && settings[Setting.UseNativePlayer]

    // No overlay over a native player: it draws its own fullscreen surface and
    // nothing of ours survives on top of it.
    val showPrompt = nextEpisode != null && ui.promptDue && !nativePlayer

    val error = ui.error?.let { ErrorMessages.playback(it) }

    Div {
        WatchNav(episode)

        // Two columns above 1180px, the way YouTube lays this out: the player
        // and everything about this episode on the left, what to watch next on
        // the right. A full-bleed player left the page with nothing beside it
        // and pushed every rail below the fold.
        Div({ classes("watch-grid") }) {
        Div({ classes("watch-main") }) {
        Div({ classes("player-frame") }) {
        val url = ui.videoUrl
        if (url != null && progressReady) {
            key(episode.id) {
                // iOS resumes through the source itself (see [withStartFragment]).
                // Fixed at the first source: the saved position moves with every
                // save while playing, and a changed src reloads the video. A quality
                // switch changes the URL and does its own seek, so it gets no fragment.
                val start = remember(episode.id) { url to savedSeconds }
                val src = if (isAppleMobile && url == start.first) withStartFragment(url, start.second) else url
                if (nativePlayer) {
                    NativeVideoPlayer(src = src, autoPlay = playback.autoplay, events = events)
                } else {
                    CustomVideoPlayer(
                        src = src,
                        autoPlay = playback.autoplay,
                        sources = ui.sources.ifEmpty { episode.sources },
                        quality = ui.quality,
                        onQualitySelected = { label ->
                            playback.selectQuality(label) { toasts.error(S.settingNotSynced) }
                        },
                        events = events,
                        overlay = {
                            if (showPrompt && nextEpisode != null) {
                                NextEpisodeCard(
                                    episode = nextEpisode,
                                    onPlayNext = { playback.goToNext() },
                                    onDismiss = { playback.dismissPrompt() },
                                )
                            }
                        },
                    )
                }
            }
        } else {
            Div({ classes("player") }) {
                Div({ classes("center-note"); style { property("min-height", "100%") } }) {
                    Text(error ?: S.loading)
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
                // `announce`, because this arrives in response to something the viewer
                // did - a stream that would not start - rather than sitting on the page
                // from the beginning. A screen reader says it when it appears.
                Callout(tone = CalloutTone.Destructive, announce = true) { Text(error) }
            }

            if (ui.ended && nextEpisode != null) {
                // Not `announce`: this one is reached by playing to the end, and the
                // viewer is looking at the screen when it happens.
                Callout(tone = CalloutTone.Primary) {
                    CalloutBody {
                        Div({ style { property("font-weight", "650") } }) { Text(S.episodeFinished.caps) }
                        Div({ classes("nextcard-s") }) {
                            Text(S.nextUp(nextEpisode.seasonNumber, nextEpisode.episodeNumber).caps)
                        }
                    }
                    Button({ classNames(buttonClasses()); onClick { playback.goToNext() } }) {
                        Text(S.nextEpisodeAction.caps)
                    }
                }
            }

            Div({ classes("watch-acts") }) {
                Button({
                    classNames(buttonClasses(ButtonVariant.Outline))
                    onClick {
                        val video = refs.video
                        if (video != null) {
                            video.currentTime = 0.0
                            playback.watchFromStart()
                            video.play()
                        }
                    }
                }) { Text(S.watchFromStart.caps) }

                if (watched) {
                    Button({
                        classNames(buttonClasses(ButtonVariant.Outline))
                        style { property("color", "var(--ok)"); property("border-color", "rgba(62,207,142,.45)") }
                        onClick { confirmReset = true }
                    }) { Text(S.watchedTick.caps) }
                } else {
                    Button({
                        classNames(buttonClasses(ButtonVariant.Outline))
                        onClick { playback.markWatched(); toasts.ok(S.episodeMarkedWatched) }
                    }) { Text(S.markAsWatched.caps) }
                }

                if (nextEpisode != null) {
                    Button({ classNames(buttonClasses()); onClick { playback.goToNext() } }) {
                        Text(S.nextEpisodeAction.caps)
                    }
                }

                // Every episode on an iPhone or iPad, not only after an error: VLC brings
                // its own decoder, so it also plays what Safari cannot (all of season 2).
                // Not on a computer, where VLC registers no link scheme and the browser
                // already plays everything. Not `external`: a new tab would only sit
                // empty behind VLC. The label carries where VLC starts: the saved spot
                // until this page's player moves, then wherever it is. The playlist
                // link starts there by itself; the plain fallback starts at 0:00, and
                // the label is the time to seek to by hand.
                val url = ui.videoUrl
                if (isAppleMobile && url != null) {
                    val seekTo = ui.position ?: savedSeconds
                    LinkButton(
                        href = vlcOpenUrl(
                            current = episode,
                            currentUrl = url,
                            // VLC plays its queue through, which is autoplay, and the web
                            // has none for now: this episode alone.
                            upcoming = emptyList(),
                            quality = ui.quality,
                            startSeconds = seekTo,
                            title = { S.seasonAndEpisode(it.seasonNumber, it.episodeNumber) },
                        ),
                        label = if (seekTo >= 1) {
                            "${S.openInVlc.caps} · ${formatTime(seekTo.toDouble())}"
                        } else {
                            S.openInVlc.caps
                        },
                        attrs = { classes("vlc-btn") },
                    )
                }

                // Car mode, on this episode. It is another page and reads the saved spot,
                // so the spot is saved first: a save started while the page unloads may
                // never reach the server.
                val driveLink = driveHref(Route.Watch(episode.id))
                LinkButton(
                    href = driveLink,
                    label = S.driveMode.caps,
                    variant = ButtonVariant.Outline,
                    attrs = {
                        onClick { event ->
                            // A modified click opens a new tab; this page keeps playing.
                            if (event.metaKey || event.ctrlKey || event.shiftKey) return@onClick
                            event.preventDefault()
                            refs.video?.pause()
                            val save = playback.save()
                            scope.launch {
                                withTimeoutOrNull(DRIVE_SAVE_WAIT_MS) { save?.join() }
                                window.location.href = driveLink
                            }
                        }
                    },
                )

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
                scope.launch {
                    if (playback.clearProgress()) {
                        refs.video?.currentTime = 0.0
                        toasts.ok(S.progressCleared)
                    } else {
                        toasts.error(S.clearFailed)
                    }
                }
            },
            onDismiss = { confirmReset = false },
        )
    }
}
