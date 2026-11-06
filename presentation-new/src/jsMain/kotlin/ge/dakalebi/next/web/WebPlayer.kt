package ge.dakalebi.next.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.domain.service.withStartFragment
import ge.dakalebi.i18n.S
import ge.dakalebi.next.player.AirPlayClock
import ge.dakalebi.next.player.PlayerKeyAction
import ge.dakalebi.next.player.PlayerKeyPolicy
import ge.dakalebi.next.player.isAppleMobile
import ge.dakalebi.next.player.mapPlayerKey
import ge.dakalebi.presentation.PlaybackViewModel
import ge.dakalebi.presentation.Playhead
import ge.dakalebi.presentation.ToastStore
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How far the skip buttons and the arrow keys move. */
const val SKIP_SECONDS = 10

/** Previous restarts the episode when it is further in than this, and goes back one otherwise. */
const val RESTART_THRESHOLD = 5.0

/** Close enough to the resume target to count as there. */
private const val RESUME_TOLERANCE = 0.75

/** A stream that never accepts the resume seek plays from where it is, rather than fighting forever. */
private const val MAX_RESUME_ATTEMPTS = 8

/** The controls over the video hide this long after the last input while it plays. */
private const val CONTROLS_HIDE_MS = 2_500

/** How long "10 s ▸" and the volume stay over the picture. */
private const val FEEDBACK_MS = 900

/** A second click inside this wait is a double-click, which goes full screen instead of pausing. */
private const val DOUBLE_CLICK_MS = 220

/** One arrow press of volume. */
private const val VOLUME_STEP = 0.1

/**
 * The web app's one `<video>`, for as long as the signed-in app is on the page.
 *
 * **One element, never rebuilt.** The web app keeps playing while the viewer browses:
 * the element moves between the mini-player, the phone's sheet and the desktop's panel,
 * and a move must never reload or restart the video. A new element per episode would also
 * lose what iOS grants an element once a tap has started it: later starts without a tap.
 * The screens only lend the element a place on the page ([attachTo]).
 *
 * **The rules are the [PlaybackViewModel]'s.** Each episode has its own (from the
 * now-playing holder); [bind] points this at it. What stays here is this browser's: the
 * resume retry loop iOS needs, the AirPlay clock, the start fragment, the page-hide saves,
 * the quality swap's seek, full screen, picture in picture, casting, and the keys.
 *
 * **Not Compose.** The fields the controls draw are Compose state. Everything else is
 * plain, because media events write it several times a second.
 */
class WebPlayer(private val toasts: ToastStore) {
    val video: HTMLVideoElement = (document.createElement("video") as HTMLVideoElement).apply {
        preload = "auto"
        // Inline on an iPhone: the sheet and the mini-player both show the picture.
        setAttribute("playsinline", "")
        // AirPlay. Deliberately no `crossorigin`: cdn.formula.ge sends no CORS headers and
        // the attribute breaks playback.
        setAttribute("x-webkit-airplay", "allow")
        classList.add("np-video")
    }

    var playing: Boolean by mutableStateOf(false)
        private set
    var buffering: Boolean by mutableStateOf(false)
        private set
    var muted: Boolean by mutableStateOf(false)
        private set

    /** The stage, not the bare video, is full screen: our controls go with it. */
    var fullscreen: Boolean by mutableStateOf(false)
        private set

    /** Whether the controls over the picture show. They hide while it plays untouched. */
    var controlsShown: Boolean by mutableStateOf(true)
        private set

    /** "10 s ▸", "Volume: 50%": what the last key did, for a moment. */
    var feedback: String? by mutableStateOf(null)
        private set

    /** An AirPlay or Cast receiver can be offered. */
    var castAvailable: Boolean by mutableStateOf(false)
        private set
    var casting: Boolean by mutableStateOf(false)
        private set

    var pipSupported: Boolean by mutableStateOf(false)
        private set
    var pip: Boolean by mutableStateOf(false)
        private set

    /** A menu over the picture is open, so the controls stay. */
    var menuOpen: Boolean = false
        set(value) {
            field = value
            if (value) showControls() else revealControls()
        }

    private var playback: PlaybackViewModel? = null
    private var stage: HTMLElement? = null

    // The source loaded, as the view model named it (before the start fragment), and the
    // first one this playback loaded with the saved spot then: only that one gets `#t=`.
    private var loadedUrl: String? = null
    private var firstSource: Pair<String, Int>? = null

    // Resume retry loop, for iOS.
    private var resumeAttempts = 0
    private var resumeSeekIssued = false
    private var resumeTimer: Int? = null

    private val airplay = AirPlayClock()
    private var airplayTimer: Int? = null

    // Quality swap: where the old file was and whether it played.
    private var pendingSeek: Double? = null
    private var pendingPlay = false

    // A pause this player caused while leaving an episode, not the viewer's.
    private var ownPause = false

    private var lastTime = -1.0
    private var hideTimer: Int? = null
    private var feedbackTimer: Int? = null
    private var clickTimer: Int? = null
    private var clickToggled = false

    // ------------------------------------------------------------ lifecycle

    /** Attaches every listener. Returns the teardown. */
    fun install(): () -> Unit {
        val mediaEvents = listOf<Pair<String, (Event) -> Unit>>(
            "loadedmetadata" to { onLoadedMetadata() },
            "loadeddata" to {
                buffering = false
                airplay.sync(video)
                applyResume("data")
            },
            "canplay" to {
                buffering = false
                airplay.sync(video)
                playback?.onDurationKnown()
                applyResume("canplay")
            },
            "durationchange" to {
                airplay.sync(video)
                playback?.onDurationKnown()
            },
            "timeupdate" to { onTimeUpdate() },
            "seeking" to { buffering = true },
            "seeked" to {
                buffering = false
                airplay.sync(video)
                applyResume("seeked")
            },
            "play" to {
                playing = true
                buffering = false
                revealControls()
                playback?.onPlay()
                if (airplay.active) startAirplayTicker()
            },
            "playing" to {
                playing = true
                buffering = false
            },
            "pause" to { onPause() },
            "waiting" to { buffering = !video.paused },
            "volumechange" to { muted = video.muted },
            "ended" to {
                playing = false
                showControls()
                playback?.let {
                    it.onEnded()
                    toasts.ok(S.markedAsWatched)
                }
            },
            "error" to { onError() },
            "webkitpresentationmodechanged" to {
                pip = video.asDynamic().webkitPresentationMode == "picture-in-picture"
            },
            "enterpictureinpicture" to { pip = true },
            "leavepictureinpicture" to { pip = false },
            "webkitplaybacktargetavailabilitychanged" to { event ->
                castAvailable = event.asDynamic().availability == "available"
            },
            "webkitcurrentplaybacktargetiswirelesschanged" to { onWirelessChanged() },
        )
        mediaEvents.forEach { (type, handler) -> video.addEventListener(type, handler) }

        val onFullscreen: (Event) -> Unit = {
            val doc = document.asDynamic()
            val element = doc.fullscreenElement ?: doc.webkitFullscreenElement
            fullscreen = element != null && element === stage
        }
        document.addEventListener("fullscreenchange", onFullscreen)
        document.addEventListener("webkitfullscreenchange", onFullscreen)

        // Leaving the app on a phone often never fires `beforeunload`, so this is the save
        // that matters there.
        val onVisibility: (Event) -> Unit = {
            if (document.asDynamic().visibilityState == "hidden") playback?.save()
        }
        val onPageHide: (Event) -> Unit = { playback?.save() }
        document.addEventListener("visibilitychange", onVisibility)
        window.addEventListener("pagehide", onPageHide)
        window.addEventListener("beforeunload", onPageHide)

        pipSupported = detectPip()
        watchCastAvailability()

        return {
            playback?.save()
            mediaEvents.forEach { (type, handler) -> video.removeEventListener(type, handler) }
            document.removeEventListener("fullscreenchange", onFullscreen)
            document.removeEventListener("webkitfullscreenchange", onFullscreen)
            document.removeEventListener("visibilitychange", onVisibility)
            window.removeEventListener("pagehide", onPageHide)
            window.removeEventListener("beforeunload", onPageHide)
            clearTimers()
            unload()
        }
    }

    /** Shows the picture in [slot]. A move between slots keeps the element playing. */
    fun attachTo(slot: HTMLElement) {
        if (video.parentNode !== slot) slot.appendChild(video)
    }

    /** The element full screen goes to: the picture with our controls on it. */
    fun stageIs(element: HTMLElement?) {
        stage = element
    }

    /**
     * Points the player at another episode's rules, or at none. The episode playing stops
     * at once: the next one's file may take a moment to resolve, and the old one must not
     * keep talking over the new one's title.
     */
    fun bind(next: PlaybackViewModel?) {
        if (next === playback) return
        unload()
        clearResume()
        stopAirplayTicker()
        val wasCasting = airplay.active
        airplay.reset()
        airplay.active = wasCasting
        pendingSeek = null
        pendingPlay = false
        firstSource = null
        playback = next
        next?.attach(ElementPlayhead(video, airplay))
    }

    /**
     * Loads [url], the file the view model chose. The first file of an episode starts at
     * [savedSeconds] through the source itself on an iPhone (see [withStartFragment]); a
     * quality switch changes the file and does its own seek, so it gets no fragment.
     */
    fun load(url: String, savedSeconds: Int, autoplay: Boolean) {
        if (url == loadedUrl) return
        val first = firstSource ?: (url to savedSeconds).also { firstSource = it }
        val src = if (isAppleMobile && url == first.first) withStartFragment(url, first.second) else url
        loadedUrl = url
        video.src = src
        // A quality swap plays again from `loadedmetadata`, and only if it was playing.
        if (autoplay && pendingSeek == null) playSafely()
    }

    /** The still shown before the first frame, or none. */
    fun setPoster(url: String?) {
        if (url.isNullOrBlank()) video.removeAttribute("poster") else video.poster = url
    }

    /** Nothing loaded: the element stops and lets its file go. */
    private fun unload() {
        if (!video.paused) {
            ownPause = true
            video.pause()
        }
        if (video.hasAttribute("src")) {
            video.removeAttribute("src")
            video.load()
        }
        loadedUrl = null
        playing = false
        buffering = false
    }

    // ---------------------------------------------------------- media events

    private fun onLoadedMetadata() {
        airplay.sync(video)
        pendingSeek?.let { target ->
            pendingSeek = null
            // Losing this seek silently is what a quality switch that "jumps back to the
            // start" actually looks like.
            runCatching { video.currentTime = target }
                .onFailure { Log.w("player", "quality-swap seek to ${target}s failed", it) }
            if (pendingPlay) playSafely()
        }
        playback?.onDurationKnown()
        applyResume("metadata")
    }

    private fun onTimeUpdate() {
        airplay.sync(video)
        // A clock that moves is the one sign every browser agrees on that data arrives.
        // Safari fires `stalled` during healthy playback and nothing when it resumes, so
        // a spinner driven by events alone stays up over a playing video. A seek jumps
        // further than this, so it keeps the spinner until `seeked`.
        val now = video.currentTime
        if (buffering && !video.paused && now > lastTime && now - lastTime < 1.0) buffering = false
        lastTime = now
        playback?.onTimeUpdate()
    }

    private fun onPause() {
        playing = false
        buffering = false
        stopAirplayTicker()
        showControls()
        if (ownPause) {
            ownPause = false
            return
        }
        playback?.onPause()
    }

    private fun onError() {
        // Letting the file go (`removeAttribute` then `load`) is not a failure.
        if (!video.hasAttribute("src")) return
        val code = video.asDynamic().error?.code as? Int
        val rules = playback ?: return
        if (rules.onMediaError(code)) {
            // The view model asks for a fresh link. The same URL again must still reload.
            loadedUrl = null
            toasts.show(S.retryingVideo)
        }
    }

    private fun onWirelessChanged() {
        val wireless = video.asDynamic().webkitCurrentPlaybackTargetIsWireless == true
        casting = wireless
        airplay.active = wireless
        if (wireless) {
            airplay.sync(video)
            startAirplayTicker()
        } else {
            stopAirplayTicker()
            playback?.onDurationKnown()
        }
    }

    // ---------------------------------------------------------------- resume

    /**
     * Seeks to the saved position once the element can accept it.
     *
     * iOS silently ignores a seek issued too early, so this retries across loadedmetadata,
     * loadeddata, canplay and seeked with a short backoff, and gives up after eight
     * attempts rather than looping forever. It never pauses the element while waiting:
     * pausing breaks the AirPlay handshake and leaves the receiver playing while the phone
     * reports 0:00.
     */
    private fun applyResume(stage: String) {
        val rules = playback ?: return
        val target = rules.resumeTarget() ?: return

        if (isAppleMobile && stage == "metadata") return

        fun settle() {
            rules.resumeLanded()
            clearResume()
        }

        // Already there before any seek of ours: the `#t=` start did it, and seeking again
        // would only stall the element on a position it has. Once we have seeked, iOS can
        // report the target while having ignored the seek, so from then on only the retry
        // loop decides.
        if (!resumeSeekIssued && abs(video.currentTime - target) < RESUME_TOLERANCE) {
            settle()
            return
        }

        fun scheduleRetry(nextStage: String, delay: Int) {
            resumeTimer?.let { window.clearTimeout(it) }
            if (resumeAttempts >= MAX_RESUME_ATTEMPTS) return
            resumeAttempts += 1
            resumeTimer = window.setTimeout({
                resumeTimer = null
                applyResume(nextStage)
            }, delay)
        }

        if (isAppleMobile && stage != "seeked") {
            resumeSeekIssued = true
            seekTo(target, stage)
            scheduleRetry("canplay", 300)
            return
        }

        val close = abs(video.currentTime - target) < RESUME_TOLERANCE
        if (!close || (isAppleMobile && !resumeSeekIssued)) seekTo(target, stage)

        if (abs(video.currentTime - target) < RESUME_TOLERANCE) {
            settle()
            return
        }
        scheduleRetry("data", 250)
    }

    /**
     * Assigning `currentTime` throws on an element that is not seekable yet. That is
     * expected during the resume loop, but a seek that keeps failing is exactly why someone
     * would report "it never remembers where I was".
     */
    private fun seekTo(target: Double, stage: String) {
        runCatching { video.currentTime = target }
            .onFailure { Log.w("resume", "seek to ${target}s rejected at stage=$stage", it) }
    }

    private fun clearResume() {
        resumeTimer?.let { window.clearTimeout(it) }
        resumeTimer = null
        resumeAttempts = 0
        resumeSeekIssued = false
    }

    // -------------------------------------------------------- airplay ticker

    /** iOS stops the element's clock while AirPlay plays: this keeps the time moving. */
    private fun startAirplayTicker() {
        if (!isAppleMobile || !airplay.active || airplayTimer != null) return
        airplayTimer = window.setInterval({
            if (!video.paused && !video.ended) playback?.onTick()
        }, 500)
    }

    private fun stopAirplayTicker() {
        airplayTimer?.let { window.clearInterval(it) }
        airplayTimer = null
    }

    // --------------------------------------------------------------- actions

    /** Where the episode is, corrected for AirPlay. */
    val currentTime: Double get() = airplay.currentTime(video)

    fun togglePlay() {
        if (video.paused) playSafely() else video.pause()
        revealControls()
    }

    /** Asks the element to play. iOS refuses outside a tap until a tap has started it once. */
    fun playSafely() {
        if (!video.hasAttribute("src")) return
        val started = video.play()
        started.catch { error ->
            // `NotAllowedError` without a tap, `AbortError` when the source changed first.
            Log.w("player", "play() refused", error)
        }
    }

    fun seekBy(delta: Double) {
        val duration = video.duration
        if (!duration.isFinite()) return
        seek(max(0.0, min(duration, video.currentTime + delta)))
        // On a long episode a ten-second jump can leave the frame looking the same.
        val seconds = abs(delta).toInt()
        flash(if (delta >= 0) S.seekForward(seconds) else S.seekBackward(seconds))
    }

    /** The viewer moved the scrubber, or pressed a skip. */
    fun seek(seconds: Double) {
        if (!video.hasAttribute("src")) return
        runCatching { video.currentTime = seconds }
            .onFailure { Log.w("player", "seek to ${seconds}s rejected", it) }
        playback?.jumpedTo(seconds)
        revealControls()
    }

    /** Back to 0:00, playing. */
    fun restart() {
        seek(0.0)
        playSafely()
    }

    fun toggleMute() {
        video.muted = !video.muted
        muted = video.muted
        revealControls()
    }

    private fun changeVolume(delta: Double) {
        video.volume = (video.volume + delta).coerceIn(0.0, 1.0)
        video.muted = video.volume <= 0.0
        muted = video.muted
        flash(S.volumeFeedback((video.volume * 100).roundToInt()))
    }

    fun toggleFullscreen() {
        val doc = document.asDynamic()
        if (doc.fullscreenElement != null || doc.webkitFullscreenElement != null) {
            attempt("leave full screen") {
                if (jsTypeOf(doc.exitFullscreen) == "function") doc.exitFullscreen() else doc.webkitExitFullscreen()
            }
            return
        }
        val target = stage?.asDynamic()
        val element = video.asDynamic()
        attempt("enter full screen") {
            when {
                target != null && jsTypeOf(target.requestFullscreen) == "function" -> target.requestFullscreen()
                target != null && jsTypeOf(target.webkitRequestFullscreen) == "function" -> target.webkitRequestFullscreen()
                // An iPhone only ever puts the video element itself full screen.
                jsTypeOf(element.webkitEnterFullscreen) == "function" -> element.webkitEnterFullscreen()
                else -> Log.w("player", "no full screen API here")
            }
        }
    }

    fun togglePip() {
        val element = video.asDynamic()
        val doc = document.asDynamic()
        attempt("picture in picture") {
            when {
                jsTypeOf(element.webkitSetPresentationMode) == "function" ->
                    element.webkitSetPresentationMode(if (pip) "inline" else "picture-in-picture")
                pip && jsTypeOf(doc.exitPictureInPicture) == "function" -> doc.exitPictureInPicture()
                jsTypeOf(element.requestPictureInPicture) == "function" -> element.requestPictureInPicture()
                else -> Log.w("player", "no picture in picture here")
            }
        }
    }

    fun startCast() {
        val element = video.asDynamic()
        attempt("cast picker") {
            when {
                jsTypeOf(element.webkitShowPlaybackTargetPicker) == "function" -> element.webkitShowPlaybackTargetPicker()
                element.remote != null && jsTypeOf(element.remote.prompt) == "function" -> element.remote.prompt()
                else -> Log.w("cast", "no AirPlay or Remote Playback API on this element")
            }
        }
    }

    /**
     * Remembers where the episode is and whether it played, so the next file picks up
     * there. Call before the view model swaps the file.
     */
    fun holdPositionForQualitySwap() {
        pendingSeek = video.currentTime
        pendingPlay = !video.paused
    }

    // ------------------------------------------------------------ the picture

    /**
     * A click on the picture plays or pauses, after a short wait so a double-click can go
     * full screen instead. A tap on hidden controls only shows them: on a phone the first
     * tap is to see what is there, not to stop the episode.
     */
    fun clickPicture(clickCount: Int, touch: Boolean) {
        if (touch && !controlsShown) {
            revealControls()
            return
        }
        clickTimer?.let { window.clearTimeout(it) }
        clickTimer = null
        // The second click of a double-click: the `dblclick` right after it decides.
        if (clickCount > 1) return
        clickToggled = false
        clickTimer = window.setTimeout({
            clickTimer = null
            clickToggled = true
            togglePlay()
        }, DOUBLE_CLICK_MS)
    }

    fun doubleClickPicture() {
        clickTimer?.let { window.clearTimeout(it) }
        clickTimer = null
        // A double-click slower than the wait: the first click already toggled. Put it
        // back, or leaving full screen this way pauses the episode.
        if (clickToggled) togglePlay()
        clickToggled = false
        toggleFullscreen()
    }

    /** Any input over the picture. The controls hide again once it plays untouched. */
    fun revealControls() {
        controlsShown = true
        hideTimer?.let { window.clearTimeout(it) }
        hideTimer = window.setTimeout({
            hideTimer = null
            if (!video.paused && !menuOpen) controlsShown = false
        }, CONTROLS_HIDE_MS)
    }

    /** The pointer left the picture: the controls go at once while it plays. */
    fun hideControlsNow() {
        if (!video.paused && !menuOpen) controlsShown = false
    }

    private fun showControls() {
        hideTimer?.let { window.clearTimeout(it) }
        hideTimer = null
        controlsShown = true
    }

    private fun flash(message: String) {
        feedback = message
        feedbackTimer?.let { window.clearTimeout(it) }
        feedbackTimer = window.setTimeout({ feedback = null }, FEEDBACK_MS)
    }

    // ------------------------------------------------------------------ keys

    /**
     * A key pressed while the player is open. True when it was the player's, and the
     * caller should `preventDefault`. Keys the policy does not claim stay the page's.
     */
    fun onKey(event: KeyboardEvent): Boolean {
        when (mapPlayerKey(event, PlayerKeyPolicy.Keyboard)) {
            PlayerKeyAction.None -> return false
            PlayerKeyAction.TogglePlay -> togglePlay()
            PlayerKeyAction.SeekForward -> seekBy(SKIP_SECONDS.toDouble())
            PlayerKeyAction.SeekBack -> seekBy(-SKIP_SECONDS.toDouble())
            PlayerKeyAction.VolumeUp -> changeVolume(VOLUME_STEP)
            PlayerKeyAction.VolumeDown -> changeVolume(-VOLUME_STEP)
            PlayerKeyAction.ToggleMute -> toggleMute()
            PlayerKeyAction.ToggleFullscreen -> toggleFullscreen()
        }
        return true
    }

    // --------------------------------------------------------------- helpers

    private fun clearTimers() {
        clearResume()
        stopAirplayTicker()
        hideTimer?.let { window.clearTimeout(it) }
        feedbackTimer?.let { window.clearTimeout(it) }
        clickTimer?.let { window.clearTimeout(it) }
        hideTimer = null
        feedbackTimer = null
        clickTimer = null
    }

    private fun detectPip(): Boolean {
        val element = video.asDynamic()
        if (jsTypeOf(element.webkitSupportsPresentationMode) == "function") {
            return element.webkitSupportsPresentationMode("picture-in-picture") == true
        }
        return document.asDynamic().pictureInPictureEnabled == true
    }

    /** The cast button shows only when there is somewhere to cast to. */
    private fun watchCastAvailability() {
        val remote = video.asDynamic().remote ?: return
        if (jsTypeOf(remote.watchAvailability) != "function") return
        attempt("cast availability") {
            remote.watchAvailability { available: Boolean -> castAvailable = available }
        }
    }

    /**
     * Runs a browser call that may throw at once or return a promise that rejects later.
     * Both are expected (no tap, no permission, no API) and only worth a log line.
     */
    private fun attempt(what: String, call: () -> dynamic) {
        runCatching {
            val result = call()
            if (result != null && jsTypeOf(result.then) == "function") {
                result.catch { error: dynamic -> Log.w("player", "$what refused: $error") }
            }
        }.onFailure { Log.w("player", "$what failed", it) }
    }
}

/** The element's position, corrected while iOS plays through AirPlay and reports 0:00. */
private class ElementPlayhead(
    private val video: HTMLVideoElement,
    private val airplay: AirPlayClock,
) : Playhead {
    override val position: Double get() = airplay.currentTime(video)
    override val duration: Double? get() = airplay.duration(video)
}
