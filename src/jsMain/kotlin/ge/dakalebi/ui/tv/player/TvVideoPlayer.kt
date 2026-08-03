package ge.dakalebi.ui.tv.player

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
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.TvSeek
import ge.dakalebi.ui.Icon
import ge.dakalebi.ui.Icons
import ge.dakalebi.ui.classNames
import ge.dakalebi.ui.player.PlayerEvents
import ge.dakalebi.ui.tv.focus.Direction
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.SpatialNav
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import ge.dakalebi.ui.tv.input.Key
import ge.dakalebi.ui.tv.input.MediaAction
import ge.dakalebi.ui.tv.input.TvInput
import ge.dakalebi.ui.tv.input.TvLayer
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Video
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement

/** What the D-pad currently means. */
private enum class Mode { Idle, Controls, Seeking }

/** Mutable holders that must not trigger recomposition when they change. */
private class TvPlayerRefs {
    var video: HTMLVideoElement? = null
    var container: HTMLElement? = null
    var fillCur: HTMLElement? = null
    var fillBuf: HTMLElement? = null
    var raf: Int? = null
    var hideTimer: Int? = null
    var settleTimer: Int? = null
    var seek: TvSeek? = null
    var preSeekPosition: Double = 0.0
}

/**
 * The player, for a remote.
 *
 * Not a restyling of the web player. The web one installs a `window` keydown
 * handler that `preventDefault`s all four arrows for its whole lifetime, which on a
 * television means the D-pad cannot move focus while it is mounted — it would
 * freeze the navigation the rest of the UI is built on. This one claims nothing
 * globally: it pushes an input layer and hands presses back when they are not its
 * business.
 *
 * Three modes, because a D-pad has four directions and two jobs:
 *
 * - **Idle**, controls hidden. Left/Right seek, Down opens the controls, OK plays
 *   or pauses, and *any* press brings the chrome back.
 * - **Controls**. Left/Right move focus through the control row, using the same
 *   spatial engine as every other screen rather than bespoke index walking. Down
 *   dismisses.
 * - **Seeking**. Presses accumulate through [TvSeek] and commit once, when the
 *   viewer stops. Applying each press directly means one network re-buffer per
 *   press; this is one seek per gesture.
 *
 * The media handling below — the paint loop, the buffered-range scan, the event
 * wiring — is carried over from the web player, which has been exercised against
 * real playback. What is dropped is everything a remote cannot produce: hover
 * reveal, click and double-click, and both `opacity: 0` range inputs, which are
 * focus traps that draw the ring around nothing.
 */
@Composable
fun TvVideoPlayer(
    src: String,
    autoPlay: Boolean,
    sources: Map<String, String>,
    quality: String?,
    onQualitySelected: (String) -> Unit,
    input: TvInput,
    events: PlayerEvents,
) {
    val refs = remember { TvPlayerRefs() }
    var mode by remember { mutableStateOf(Mode.Controls) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var currentSec by remember { mutableStateOf(0) }
    var durationSec by remember { mutableStateOf(0) }
    var seekPreview by remember { mutableStateOf<String?>(null) }
    var qualityOpen by remember { mutableStateOf(false) }

    val ordered = remember(sources) { orderedQualityLabels(sources) }

    // ------------------------------------------------------------- painting

    fun paintBars() {
        val v = refs.video ?: return
        val duration = if (v.duration.isFinite() && v.duration > 0) v.duration else 0.0
        val pct = if (duration > 0) (v.currentTime / duration * 100).coerceIn(0.0, 100.0) else 0.0
        refs.fillCur?.style?.width = "$pct%"

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
        refs.fillBuf?.style?.width = "$bufPct%"
    }

    fun syncClock() {
        val v = refs.video ?: return
        val cur = if (v.currentTime.isFinite()) v.currentTime.toInt() else 0
        val dur = if (v.duration.isFinite() && v.duration > 0) v.duration.toInt() else 0
        if (cur != currentSec) currentSec = cur
        if (dur != durationSec) durationSec = dur
    }

    fun stopLoop() {
        refs.raf?.let { window.cancelAnimationFrame(it) }
        refs.raf = null
    }

    fun startLoop() {
        stopLoop()
        fun tick(@Suppress("UNUSED_PARAMETER") ts: Double) {
            val v = refs.video
            if (v == null) {
                refs.raf = null
                return
            }
            paintBars()
            syncClock()
            refs.raf = if (!v.paused && !v.ended) window.requestAnimationFrame(::tick) else null
        }
        refs.raf = window.requestAnimationFrame(::tick)
    }

    // ------------------------------------------------------------- chrome

    fun hideControls() {
        refs.hideTimer?.let { window.clearTimeout(it) }
        refs.hideTimer = null
        if (!qualityOpen) mode = Mode.Idle
    }

    /**
     * Five seconds, not the web player's 2.5. A remote is slower to aim than a
     * mouse, and the penalty for hiding too early is a press spent bringing the
     * chrome back rather than doing what you meant.
     */
    fun revealControls() {
        if (mode == Mode.Idle) mode = Mode.Controls
        refs.hideTimer?.let { window.clearTimeout(it) }
        refs.hideTimer = window.setTimeout({
            val v = refs.video
            if (v != null && !v.paused && !qualityOpen) hideControls()
        }, CONTROLS_HIDE_MS)
    }

    fun togglePlay() {
        val v = refs.video ?: return
        if (v.paused) v.play() else v.pause()
    }

    // --------------------------------------------------------------- seeking

    fun commitSeek() {
        val v = refs.video ?: return
        val pending = refs.seek ?: return
        refs.seek = null
        refs.settleTimer?.let { window.clearTimeout(it) }
        refs.settleTimer = null
        seekPreview = null
        if (!v.duration.isFinite() || v.duration <= 0) return
        val target = pending.target(refs.preSeekPosition, v.duration)
        runCatching { v.currentTime = target }
            .onFailure { Log.w("tv-player", "seek to ${target}s rejected", it) }
        if (mode == Mode.Seeking) mode = Mode.Idle
    }

    fun nudgeSeek(direction: Int) {
        val v = refs.video ?: return
        if (!v.duration.isFinite() || v.duration <= 0) return
        val now = window.performance.now()
        val existing = refs.seek
        if (existing == null) refs.preSeekPosition = v.currentTime
        refs.seek = existing?.press(direction, now) ?: TvSeek.start(direction, now)
        mode = Mode.Seeking

        val pending = refs.seek ?: return
        val target = pending.target(refs.preSeekPosition, v.duration)
        val sign = if (pending.offsetSeconds >= 0) "+" else "−"
        seekPreview = "$sign${formatTime(kotlin.math.abs(pending.offsetSeconds))}  ${formatTime(target)}"

        refs.settleTimer?.let { window.clearTimeout(it) }
        refs.settleTimer = window.setTimeout({ commitSeek() }, TvSeek.SETTLE_MS.toInt())
    }

    // ---------------------------------------------------------------- input

    DisposableEffect(Unit) {
        val layer = input.push(
            TvLayer(
                key = "tv-player",
                root = { refs.container },
                // The player is the screen, not an overlay over it: an unhandled
                // Back has to reach the route table and leave, not silently remove
                // this layer and strand a player nothing is listening for.
                dismissible = false,
                onBack = {
                    when {
                        qualityOpen -> { qualityOpen = false; true }
                        refs.seek != null -> { refs.seek = null; seekPreview = null; mode = Mode.Idle; true }
                        mode == Mode.Controls -> { hideControls(); true }
                        else -> false
                    }
                },
                // Any press brings the chrome back, before the press is acted on.
                // Without this the first button you push is spent revealing the
                // thing you were aiming at.
                onAnyKey = { key -> if (key !is Key.Back) revealControls() },
                onDirection = { direction ->
                    when {
                        // In Controls the arrows belong to focus, so they are
                        // handed straight back to the spatial engine.
                        mode == Mode.Controls && direction.isHorizontal -> false
                        mode == Mode.Controls && direction == Direction.Down -> { hideControls(); true }
                        mode == Mode.Controls -> false
                        direction == Direction.Left -> { nudgeSeek(-1); true }
                        direction == Direction.Right -> { nudgeSeek(1); true }
                        direction == Direction.Down -> { revealControls(); true }
                        else -> { revealControls(); true }
                    }
                },
                onSelect = { focused ->
                    when {
                        refs.seek != null -> { commitSeek(); true }
                        // A real control has the ring: let the click through.
                        mode == Mode.Controls && focused?.hasAttribute("data-tv-item") == true -> false
                        else -> { togglePlay(); true }
                    }
                },
                onMedia = { action ->
                    when (action) {
                        MediaAction.PlayPause -> { togglePlay(); true }
                        MediaAction.Play -> { refs.video?.play(); true }
                        MediaAction.Pause -> { refs.video?.pause(); true }
                        MediaAction.Forward -> { nudgeSeek(1); true }
                        MediaAction.Rewind -> { nudgeSeek(-1); true }
                        else -> false
                    }
                },
            ),
        )
        // Land the ring on a control so the first press is not spent finding one.
        val land = window.setTimeout({ refs.container?.let { SpatialNav.ensureFocused(it) } }, 0)
        onDispose {
            window.clearTimeout(land)
            layer.dismiss()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            refs.hideTimer?.let { window.clearTimeout(it) }
            refs.settleTimer?.let { window.clearTimeout(it) }
            stopLoop()
        }
    }

    // ------------------------------------------------------------------- ui

    Div({
        classes("tv-player")
        ref { element ->
            refs.container = element
            onDispose { refs.container = null }
        }
    }) {
        Video({
            attr("src", src)
            attr("playsinline", "")
            attr("preload", "auto")
            // No `crossorigin`. cdn.formula.ge sends no CORS headers and adding
            // the attribute breaks playback outright.
            if (autoPlay) attr("autoplay", "")

            ref { element ->
                refs.video = element
                events.onElement(element)
                onDispose {
                    events.onElement(null)
                    refs.video = null
                }
            }

            addEventListener("loadedmetadata") {
                syncClock(); paintBars()
                refs.video?.let { events.onLoadedMetadata(it) }
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
                playing = true; buffering = false
                startLoop(); revealControls()
                events.onPlay()
            }
            addEventListener("playing") { playing = true; buffering = false; startLoop() }
            addEventListener("pause") {
                playing = false; buffering = false
                stopLoop()
                // Paused chrome stays up: there is nothing to watch behind it.
                mode = Mode.Controls
                events.onPause()
            }
            addEventListener("waiting") { buffering = refs.video?.paused == false }
            addEventListener("stalled") { buffering = refs.video?.paused == false }
            addEventListener("seeking") { buffering = true }
            addEventListener("seeked") {
                buffering = false; paintBars()
                refs.video?.let { events.onSeeked(it) }
            }
            addEventListener("ended") {
                playing = false; stopLoop()
                mode = Mode.Controls
                events.onEnded()
            }
            addEventListener("error") { events.onError() }
        })

        if (buffering) Div({ classes("tv-spinner") }) { Div() }

        // The seek readout. Big and central, because it is the only feedback that a
        // press registered before the seek actually happens.
        seekPreview?.let {
            Div({ classes("tv-seek-osd", "mono") }) { Text(it) }
        }

        if (qualityOpen && ordered.size > 1) {
            Div({ classes("tv-q-menu"); focusGroup("quality", FocusAxis.Y) }) {
                ordered.forEach { label ->
                    Div({
                        classNames("tv-q-item", if (label == quality) "on" else null)
                        focusItem("q-$label", entry = label == quality)
                        onClick { qualityOpen = false; onQualitySelected(label) }
                    }) { Text(label) }
                }
            }
        }

        Div({ classNames("tv-ctl", if (mode == Mode.Controls) null else "hide") }) {
            Div({ classes("tv-scrub") }) {
                Div({ classes("tv-scrub-buf"); ref { el -> refs.fillBuf = el; onDispose { refs.fillBuf = null } } })
                Div({ classes("tv-scrub-cur"); ref { el -> refs.fillCur = el; onDispose { refs.fillCur = null } } })
            }
            Div({ classes("tv-ctl-row"); focusGroup("player-controls", FocusAxis.X) }) {
                Div({
                    classes("tv-ctl-btn")
                    focusItem("play", entry = true)
                    attr("aria-label", if (playing) S.pause else S.play)
                    onClick { togglePlay() }
                }) { Icon(if (playing) Icons.pause else Icons.play) }

                Div({
                    classes("tv-ctl-btn")
                    focusItem("back10")
                    attr("aria-label", S.back10)
                    onClick { nudgeSeek(-1) }
                }) { Icon(Icons.back10) }

                Div({
                    classes("tv-ctl-btn")
                    focusItem("forward10")
                    attr("aria-label", S.forward10)
                    onClick { nudgeSeek(1) }
                }) { Icon(Icons.forward10) }

                Span({ classes("tv-time", "mono") }) {
                    Text("${formatTime(currentSec.toDouble())} / ${formatTime(durationSec.toDouble())}")
                }

                Div({ classes("grow") })

                if (ordered.size > 1) {
                    Div({
                        classes("tv-ctl-btn", "tv-q-btn", "mono")
                        focusItem("quality")
                        attr("aria-label", S.quality)
                        onClick { qualityOpen = !qualityOpen }
                    }) { Text(quality ?: ordered.first()) }
                }
            }
        }

        // Always something to read the position from, exactly when the control bar
        // is not there. Never a moment with no indicator at all.
        Div({ classNames("tv-thinbar", if (mode == Mode.Controls) "hide" else null) }) {
            Div({
                style {
                    property(
                        "width",
                        if (durationSec > 0) "${currentSec * 100.0 / durationSec}%" else "0%",
                    )
                }
            })
        }
    }
}

/** How long the chrome stays up after the last press, while playing. */
private const val CONTROLS_HIDE_MS = 5_000
