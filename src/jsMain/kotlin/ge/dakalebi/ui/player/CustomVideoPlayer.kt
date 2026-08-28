package ge.dakalebi.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.core.formatTime
import ge.dakalebi.domain.service.orderedQualityLabels
import ge.dakalebi.i18n.S
import ge.dakalebi.ui.Icon as PlayerIcon
import ge.dakalebi.ui.Icons as PlayerIcons
import io.github.bchmsl.keel.components.ButtonSize
import io.github.bchmsl.keel.components.ButtonVariant
import io.github.bchmsl.keel.components.DropdownMenu
import io.github.bchmsl.keel.components.DropdownMenuItem
import io.github.bchmsl.keel.components.DropdownSide
import io.github.bchmsl.keel.components.IconButton
import io.github.bchmsl.keel.components.ProgressBar
import io.github.bchmsl.keel.components.ProgressHandle
import io.github.bchmsl.keel.components.Scrub
import io.github.bchmsl.keel.components.ScrubHandle
import io.github.bchmsl.keel.components.Spinner
import io.github.bchmsl.keel.components.SpinnerSize
import io.github.bchmsl.keel.dom.buttonClasses
import io.github.bchmsl.keel.dom.classNames
import io.github.bchmsl.keel.dom.dropdownAnchorClasses
import io.github.bchmsl.keel.icons.Icon
import io.github.bchmsl.keel.icons.LucideIcon
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.max
import org.jetbrains.compose.web.attributes.min
import org.jetbrains.compose.web.attributes.step
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Video
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** Mutable holders that must not trigger recomposition when they change. */
private class PlayerRefs {
    var video: HTMLVideoElement? = null
    var container: HTMLElement? = null
    var scrub: ScrubHandle? = null
    var thinBar: ProgressHandle? = null
    var raf: Int? = null
    var hideTimer: Int? = null
    var feedbackTimer: Int? = null
    var clickTimer: Int? = null
    /** Position to restore after a quality swap. */
    var pendingSeek: Double? = null
    var pendingPlay: Boolean = false

    /** Last position the animation loop saw, for detecting real progress. */
    var lastTickTime: Double = -1.0
}

@Composable
fun CustomVideoPlayer(
    src: String,
    autoPlay: Boolean,
    sources: Map<String, String>,
    quality: String?,
    onQualitySelected: (String) -> Unit,
    events: PlayerEvents,
    overlay: @Composable () -> Unit = {},
) {
    val refs = remember { PlayerRefs() }

    var playing by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1.0) }
    var currentSec by remember { mutableStateOf(0) }
    var durationSec by remember { mutableStateOf(0) }
    var showControls by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var qualityOpen by remember { mutableStateOf(false) }
    var castSupported by remember { mutableStateOf(false) }
    var castAvailable by remember { mutableStateOf(false) }
    var casting by remember { mutableStateOf(false) }

    // ------------------------------------------------------------- helpers

    fun paintBars() {
        val v = refs.video ?: return
        val duration = if (v.duration.isFinite() && v.duration > 0) v.duration else 0.0
        val pct = if (duration > 0) (v.currentTime / duration * 100).coerceIn(0.0, 100.0) else 0.0

        // One call for the fill, the knob and the range input's own value and
        // `aria-valuetext`. The drag guard that used to be written out here is inside
        // the handle: mid-drag this does nothing, so the frame loop cannot pull the
        // knob back to the video's time while a finger is still moving it.
        //
        // The time is read from the element rather than from `currentSec`, which is
        // composition state and is a frame or more behind here.
        refs.scrub?.setPosition(
            fraction = pct / 100.0,
            valueText = "${formatTime(v.currentTime)} / ${formatTime(duration)}",
        )
        // keel's handle rather than a `style.width` of our own: it moves the fill and
        // rewrites `aria-valuenow` together, so the two cannot drift.
        refs.thinBar?.setFraction(pct / 100.0)

        var bufEnd = 0.0
        val buffered = v.buffered
        for (i in 0 until buffered.length) {
            val start = buffered.start(i)
            val end = buffered.end(i)
            if (v.currentTime in start..end) {
                bufEnd = end
                break
            }
            if (start >= v.currentTime && end > bufEnd) bufEnd = end
        }
        val bufPct = if (duration > 0) (bufEnd / duration * 100).coerceIn(0.0, 100.0) else 0.0
        refs.scrub?.setBuffered(bufPct / 100.0)
    }

    fun syncClock() {
        val v = refs.video ?: return
        val cur = if (v.currentTime.isFinite()) floor(v.currentTime).toInt() else 0
        val dur = if (v.duration.isFinite() && v.duration > 0) floor(v.duration).toInt() else 0
        if (cur != currentSec) currentSec = cur
        if (dur != durationSec) durationSec = dur
    }

    fun stopLoop() {
        refs.raf?.let { window.cancelAnimationFrame(it) }
        refs.raf = null
    }

    fun startLoop() {
        stopLoop()
        refs.lastTickTime = -1.0
        fun tick(@Suppress("UNUSED_PARAMETER") ts: Double) {
            val v = refs.video
            if (v == null) {
                refs.raf = null
                return
            }
            paintBars()
            syncClock()

            // A clock that is moving is the only trustworthy evidence that we
            // are not waiting for data, and the only one every browser agrees
            // on. Safari fires `stalled` repeatedly *during healthy playback* —
            // measured against cdn.formula.ge, six times in 45 seconds while
            // the clock ran normally — and then sends no event at all when
            // data resumes, because as far as it is concerned nothing ever
            // stopped. A spinner driven only by events therefore stays up over
            // a playing video forever. This is the escape hatch.
            //
            // The upper bound keeps a seek from clearing it early: a seek jumps
            // the clock by far more than one frame's worth, and the spinner
            // should stay up until `seeked`.
            val now = v.currentTime
            val advanced = now > refs.lastTickTime && now - refs.lastTickTime < 1.0
            if (buffering && advanced && !v.paused) buffering = false
            refs.lastTickTime = now

            refs.raf = if (!v.paused && !v.ended) window.requestAnimationFrame(::tick) else null
        }
        refs.raf = window.requestAnimationFrame(::tick)
    }

    fun flashFeedback(message: String) {
        feedback = message
        refs.feedbackTimer?.let { window.clearTimeout(it) }
        refs.feedbackTimer = window.setTimeout({ feedback = null }, 900)
    }

    fun revealControls() {
        showControls = true
        refs.hideTimer?.let { window.clearTimeout(it) }
        refs.hideTimer = window.setTimeout({
            val v = refs.video
            if (v != null && !v.paused && !qualityOpen) showControls = false
        }, 2500)
    }

    fun togglePlay() {
        val v = refs.video ?: return
        if (v.paused) v.play() else v.pause()
        revealControls()
    }

    fun seekBy(delta: Double) {
        val v = refs.video ?: return
        if (!v.duration.isFinite()) return
        v.currentTime = max(0.0, min(v.duration, v.currentTime + delta))
        paintBars()
        // Arrow-key seeking had no acknowledgement at all. On a long episode a
        // ten-second jump can leave the frame looking identical, so without
        // this there is no way to tell the key registered.
        val seconds = kotlin.math.abs(delta).toInt()
        flashFeedback(if (delta >= 0) S.seekForward(seconds) else S.seekBackward(seconds))
        revealControls()
    }

    fun applyVolume(next: Double) {
        val v = refs.video ?: return
        v.volume = next.coerceIn(0.0, 1.0)
        v.muted = next <= 0.0
        volume = v.volume
        muted = v.muted
    }

    fun toggleFullscreen() {
        val el = refs.container ?: return
        val doc = document.asDynamic()
        if (doc.fullscreenElement != null || doc.webkitFullscreenElement != null) {
            runCatching { doc.exitFullscreen() }
                .recoverCatching { doc.webkitExitFullscreen() }
                .onFailure { Log.w("player", "could not leave fullscreen", it) }
        } else {
            val dyn = el.asDynamic()
            runCatching { dyn.requestFullscreen() }
                .recoverCatching { dyn.webkitRequestFullscreen() }
                .recoverCatching {
                    // iOS Safari only ever fullscreens the video element itself.
                    refs.video?.asDynamic()?.webkitEnterFullscreen()
                }
                .onFailure { Log.w("player", "could not enter fullscreen", it) }
        }
    }

    fun startCast() {
        val v = refs.video?.asDynamic() ?: return
        runCatching {
            if (jsTypeOf(v.webkitShowPlaybackTargetPicker) == "function") {
                v.webkitShowPlaybackTargetPicker()
                flashFeedback("AirPlay")
            } else if (v.remote != null && jsTypeOf(v.remote.prompt) == "function") {
                v.remote.prompt()
                flashFeedback("Cast")
            } else {
                Log.w("cast", "no AirPlay or Remote Playback API on this element")
            }
        }.onFailure { Log.w("cast", "picker refused to open", it) }
    }

    // ------------------------------------------------------------- effects

    // Quality swap: remember where we were, then restore once the new file loads.
    DisposableEffect(src) {
        started = false
        buffering = false
        showControls = true
        qualityOpen = false
        onDispose { stopLoop() }
    }

    DisposableEffect(Unit) {
        val onFs: (Event) -> Unit = {
            val doc = document.asDynamic()
            fullscreen = doc.fullscreenElement != null || doc.webkitFullscreenElement != null
        }
        document.addEventListener("fullscreenchange", onFs)
        document.addEventListener("webkitfullscreenchange", onFs)
        onDispose {
            document.removeEventListener("fullscreenchange", onFs)
            document.removeEventListener("webkitfullscreenchange", onFs)
        }
    }

    DisposableEffect(Unit) {
        val onKey: (Event) -> Unit = handler@{ raw ->
            val event = raw as? KeyboardEvent ?: return@handler

            // The rules live in `mapPlayerKey` so the TV player can share them
            // under a policy that claims no arrows. `None` is not swallowed: a
            // key this player did not act on has to reach whatever is focused.
            fun volumeBy(delta: Double) {
                applyVolume((refs.video?.volume ?: 1.0) + delta)
                flashFeedback(S.volumeFeedback(((refs.video?.volume ?: 0.0) * 100).roundToInt()))
            }

            when (mapPlayerKey(event, PlayerKeyPolicy.Keyboard)) {
                PlayerKeyAction.None -> return@handler
                PlayerKeyAction.TogglePlay -> togglePlay()
                PlayerKeyAction.SeekForward -> seekBy(10.0)
                PlayerKeyAction.SeekBack -> seekBy(-10.0)
                PlayerKeyAction.VolumeUp -> volumeBy(0.05)
                PlayerKeyAction.VolumeDown -> volumeBy(-0.05)
                PlayerKeyAction.ToggleFullscreen -> toggleFullscreen()
                PlayerKeyAction.ToggleMute -> {
                    val v = refs.video ?: return@handler
                    v.muted = !v.muted
                    muted = v.muted
                }
            }
            event.preventDefault()
        }
        window.addEventListener("keydown", onKey)
        onDispose { window.removeEventListener("keydown", onKey) }
    }

    DisposableEffect(Unit) {
        onDispose {
            refs.hideTimer?.let { window.clearTimeout(it) }
            refs.feedbackTimer?.let { window.clearTimeout(it) }
            refs.clickTimer?.let { window.clearTimeout(it) }
            stopLoop()
        }
    }

    // ----------------------------------------------------------------- ui

    val pctText = "${formatTime(currentSec.toDouble())} / ${formatTime(durationSec.toDouble())}"
    val controlsHidden = !showControls && playing
    // Best-first, computed rather than taken from the map's own order, which
    // is not preserved across a Firestore round trip.
    val ordered = remember(sources) { orderedQualityLabels(sources) }

    Div({
        // `idle` is the state the control bar is hidden in, named on the player itself so the
        // stylesheet can also take the cursor away in fullscreen. Driven from the same value the
        // bar reads, so the pointer and the chrome cannot disagree about whether they are up.
        classNames("player", if (controlsHidden) "idle" else null)
        ref { element ->
            refs.container = element
            onDispose { refs.container = null }
        }
        onMouseMove { revealControls() }
        onMouseLeave {
            val v = refs.video
            if (v != null && !v.paused && !qualityOpen) showControls = false
        }
    }) {
        Video({
            attr("src", src)
            attr("playsinline", "")
            attr("preload", "auto")
            // Enables AirPlay. Note there is deliberately no `crossorigin`:
            // cdn.formula.ge sends no CORS headers and adding it breaks playback.
            attr("x-webkit-airplay", "allow")
            if (autoPlay) attr("autoplay", "")

            ref { element ->
                refs.video = element
                events.onElement(element)
                onDispose {
                    events.onElement(null)
                    refs.video = null
                }
            }

            onClick {
                // Delay so a double-click can cancel the play/pause toggle.
                refs.clickTimer?.let { window.clearTimeout(it) }
                refs.clickTimer = window.setTimeout({
                    togglePlay()
                    refs.clickTimer = null
                }, 220)
            }
            onDoubleClick {
                refs.clickTimer?.let { window.clearTimeout(it) }
                refs.clickTimer = null
                toggleFullscreen()
            }

            addEventListener("loadedmetadata") {
                val v = refs.video ?: return@addEventListener
                syncClock()
                paintBars()
                refs.pendingSeek?.let { target ->
                    refs.pendingSeek = null
                    // Losing this seek silently is what a quality switch that
                    // "jumps back to the start" actually looks like.
                    runCatching { v.currentTime = target }
                        .onFailure { Log.w("player", "quality-swap seek to ${target}s failed", it) }
                    if (refs.pendingPlay) v.play()
                }
                events.onLoadedMetadata(v)
            }
            addEventListener("loadeddata") {
                refs.video?.let { buffering = false; events.onLoadedData(it) }
            }
            addEventListener("canplay") {
                refs.video?.let { buffering = false; events.onCanPlay(it) }
            }
            addEventListener("durationchange") {
                syncClock()
                refs.video?.let { events.onDurationChange(it) }
            }
            addEventListener("timeupdate") {
                syncClock()
                refs.video?.let { events.onTimeUpdate(it) }
            }
            addEventListener("progress") { paintBars() }
            addEventListener("play") {
                playing = true
                started = true
                buffering = false
                startLoop()
                revealControls()
                events.onPlay()
            }
            addEventListener("playing") {
                playing = true
                buffering = false
                startLoop()
            }
            addEventListener("pause") {
                playing = false
                buffering = false
                stopLoop()
                showControls = true
                events.onPause()
            }
            addEventListener("waiting") { buffering = refs.video?.paused == false }
            // `stalled` is deliberately NOT wired to the spinner. It means "no
            // data has arrived for three seconds", which for a progressively
            // downloaded MP4 is the normal state once the browser has read
            // ahead and stopped fetching — not a sign that playback halted.
            // Safari fires it throughout untroubled playback; Chrome does not.
            // `waiting` is the event that actually means "stopped, needs data",
            // and the animation loop clears it once the clock moves again.
            addEventListener("seeking") { buffering = true }
            addEventListener("seeked") {
                buffering = false
                paintBars()
                refs.video?.let { events.onSeeked(it) }
            }
            addEventListener("volumechange") {
                val v = refs.video ?: return@addEventListener
                volume = v.volume
                muted = v.muted
            }
            addEventListener("ended") {
                playing = false
                stopLoop()
                showControls = true
                events.onEnded()
            }
            addEventListener("error") { events.onError() }
        })

        // Remote playback / AirPlay availability.
        Div({
            ref { _ ->
                val v = refs.video?.asDynamic()
                if (v != null) {
                    if (jsTypeOf(v.webkitShowPlaybackTargetPicker) == "function") {
                        castSupported = true
                    }
                    val remote = v.remote
                    if (remote != null && jsTypeOf(remote.watchAvailability) == "function") {
                        castSupported = true
                        runCatching {
                            remote.watchAvailability { available: Boolean ->
                                castAvailable = available
                            }
                        }.onFailure { Log.w("cast", "watchAvailability rejected", it) }
                        remote.onconnect = { casting = true }
                        remote.ondisconnect = { casting = false }
                    }
                    v.addEventListener("webkitplaybacktargetavailabilitychanged", { e: dynamic ->
                        castAvailable = e.availability == "available"
                    })
                    v.addEventListener("webkitcurrentplaybacktargetiswirelesschanged", { _: dynamic ->
                        val wireless = v.webkitCurrentPlaybackTargetIsWireless == true
                        casting = wireless
                        refs.video?.let { events.onAirPlayChange(wireless, it) }
                    })
                }
                onDispose { }
            }
            style { property("display", "none") }
        })

        if (!started || (!playing && !buffering)) {
            Div({ classes("player-center"); onClick { togglePlay() } }) {
                // The `-1` in the CSS class name is web.css's own optical nudge for
                // a triangle glyph, which sits visually off-centre inside a circle -
                // matching `.player-center .big .ic svg { margin-left: 3px }`.
                Div({ classes("big") }) {
                    Icon(LucideIcon.Play, size = ICON_PLAYER_BIG, className = "player-glyph-nudge")
                }
            }
        }

        if (buffering) {
            // The wrapper is layout over the video and stays local; the ring itself is
            // keel's. It also gains a label - the hand-built one announced nothing, so
            // a screen reader user had no way to tell buffering from a stalled player.
            Div({ classes("loading-ring") }) {
                Spinner(SpinnerSize.Large, ariaLabel = S.loading)
            }
        }

        overlay()

        feedback?.let {
            Div({ classes("feedback") }) { Text(it) }
        }

        Div({ classNames("ctl", if (controlsHidden) "hide" else null) }) {
            // The track, the buffered layer, the played fill, the knob, the invisible
            // range input and all three pointer listeners are keel's. What is left
            // here is the one thing only this player knows: where in the video a
            // fraction of the timeline is.
            Scrub(
                ariaLabel = S.timeline,
                onSeek = { fraction ->
                    val v = refs.video
                    if (v != null && v.duration.isFinite() && v.duration > 0) {
                        v.currentTime = fraction * v.duration
                    }
                },
                onHandleReady = { handle -> refs.scrub = handle },
            )

            Div({ classes("ctl-row") }) {
                IconButton(
                    ariaLabel = if (playing) S.pause else S.play,
                    onClick = { togglePlay() },
                ) { Icon(if (playing) LucideIcon.Pause else LucideIcon.Play, size = ICON_PLAYER_CTL) }

                IconButton(ariaLabel = S.back10, onClick = { seekBy(-10.0) }) { PlayerIcon(PlayerIcons.back10) }

                Div({ classes("vol-wrap") }) {
                    IconButton(
                        ariaLabel = if (muted) S.unmute else S.mute,
                        onClick = {
                            refs.video?.let { v -> v.muted = !v.muted; muted = v.muted }
                        },
                    ) {
                        Icon(
                            if (muted || volume == 0.0) LucideIcon.VolumeX else LucideIcon.Volume2,
                            size = ICON_PLAYER_CTL,
                        )
                    }

                    Div({ classes("vol") }) {
                        Div({ style { property("width", "${if (muted) 0.0 else volume * 100}%") } })
                        Input(InputType.Range) {
                            min("0"); max("1"); step(0.01)
                            attr("aria-label", S.volume)
                            value(if (muted) "0" else volume.toString())
                            onInput { event ->
                                applyVolume(event.value.toString().toDoubleOrNull() ?: 0.0)
                            }
                        }
                    }
                }

                Span({ classes("time", "mono") }) { Text(pctText) }

                Div({ classes("grow") })

                if (ordered.size > 1) {
                    // The trigger and the menu now share one positioned wrapper,
                    // which is what keel's dropdown resolves against. Before, the menu
                    // was a sibling of `.ctl` pinned to `.player` by a hand-measured
                    // `right: 14px; bottom: 64px` - the 64 being this control bar's
                    // height, so anything that changed the bar moved the menu off it.
                    //
                    // Nesting it inside `.ctl`, which fades out on the idle timer, is
                    // safe only because that timer already refuses to fire while
                    // `qualityOpen`: both paths that hide the bar test it. Without
                    // those guards an open menu would fade out with the bar under it.
                    Div({ classNames(dropdownAnchorClasses()) }) {
                        Button({
                            // On-media, because it sits on the video frame rather than
                            // on the page. Small rather than the default, so it reads as
                            // chrome beside the transport buttons instead of competing
                            // with them.
                            classNames(
                                "q-btn",
                                "mono",
                                buttonClasses(ButtonVariant.OnMedia, ButtonSize.Small),
                            )
                            attr("aria-label", S.quality)
                            // A disclosure, which is what this is: keel's menu is a
                            // labelled *group* of buttons and says so, so
                            // `aria-haspopup="menu"` would promise a role it does not
                            // claim. `aria-expanded` alone is the honest half, and the
                            // hand-built version announced neither.
                            attr("aria-expanded", qualityOpen.toString())
                            onClick { qualityOpen = !qualityOpen }
                        }) { Text(quality ?: ordered.first()) }

                        if (qualityOpen) {
                            DropdownMenu(
                                onDismiss = { qualityOpen = false },
                                ariaLabel = S.quality,
                                // The bar is pinned to the foot of the video, so a menu
                                // hung downward opens off the bottom of the screen.
                                side = DropdownSide.Above,
                            ) {
                                ordered.forEach { label ->
                                    DropdownMenuItem(
                                        label = label,
                                        // The check and `aria-current` are keel's now.
                                        // The old markup marked the current rendition
                                        // with a "✓" in a `.grow` span and a red `.sel`
                                        // class, and announced nothing at all - so
                                        // which quality was playing was answerable only
                                        // by looking.
                                        selected = label == quality,
                                        onClick = {
                                            qualityOpen = false
                                            val v = refs.video
                                            refs.pendingSeek = v?.currentTime
                                            refs.pendingPlay = v?.paused == false
                                            onQualitySelected(label)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (castSupported || castAvailable) {
                    Button({
                        classNames(buttonClasses(ButtonVariant.Ghost, ButtonSize.Icon))
                        attr("aria-label", "Cast")
                        if (casting) style { property("color", "var(--red)") }
                        onClick { startCast() }
                    }) { Icon(LucideIcon.Cast, size = ICON_PLAYER_CTL) }
                }

                IconButton(ariaLabel = S.fullscreen, onClick = { toggleFullscreen() }) {
                    Icon(
                        if (fullscreen) LucideIcon.Minimize else LucideIcon.Maximize,
                        size = ICON_PLAYER_CTL,
                    )
                }
            }
        }

        // Always-visible position line: shown precisely when the control bar
        // is not, so there is never a moment without a progress indicator.
        // Driven from the frame loop through `ProgressHandle`, which is what that
        // escape exists for - recomposing sixty times a second to move one element is
        // exactly what a player must not do. `fraction` is therefore a constant here.
        //
        // `aria-hidden`: this duplicates the scrub bar, which is a real range input and
        // stays in the accessibility tree even while the control row is faded out. Two
        // progressbars reporting the same position is noise, not redundancy.
        ProgressBar(
            fraction = 0.0,
            ariaLabel = S.timeline,
            onMedia = true,
            attrs = {
                classNames("thinbar", if (!controlsHidden) "hide" else null)
                attr("aria-hidden", "true")
            },
            onHandleReady = { handle -> refs.thinBar = handle },
        )
    }
}

/** Matches `.player-center .big .ic svg` in web.css. */
private const val ICON_PLAYER_BIG = 30

/** Matches `.ctl .ic svg` in web.css. */
private const val ICON_PLAYER_CTL = 24
