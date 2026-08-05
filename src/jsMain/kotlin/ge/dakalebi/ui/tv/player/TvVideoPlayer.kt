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
import ge.dakalebi.ui.tv.TvConfig
import ge.dakalebi.ui.tv.actsAsButton
import ge.dakalebi.ui.tv.actsAsOption
import ge.dakalebi.ui.tv.actsAsOptionGroup
import ge.dakalebi.ui.tv.focus.Direction
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.ITEM_ATTR
import ge.dakalebi.ui.tv.focus.SpatialNav
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import ge.dakalebi.ui.tv.input.Key
import ge.dakalebi.ui.tv.input.MediaAction
import ge.dakalebi.ui.tv.input.TvInput
import ge.dakalebi.ui.tv.input.TvLayer
import ge.dakalebi.ui.tv.ownsPopup
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Video
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the D-pad currently means. */
private enum class Mode { Idle, Controls, Scrubbing }

/** Mutable holders that must not trigger recomposition when they change. */
private class TvPlayerRefs {
    var video: HTMLVideoElement? = null
    var container: HTMLElement? = null
    var fillCur: HTMLElement? = null
    var fillBuf: HTMLElement? = null
    var thumb: HTMLElement? = null
    var ghost: HTMLElement? = null
    var raf: Int? = null
    var hideTimer: Int? = null
    var scrub: TvSeek? = null
    var scrubOrigin: Double = 0.0

    /** Last buffered-bar width written, as a rounded percent, so the per-frame paint
     *  loop skips the style write while the buffered range has not visibly grown. */
    var lastBufPct: Int = -1

    /**
     * Whether playback was running when the scrub began.
     *
     * The documented contract pauses for the duration of a scrub and restores the
     * previous state after it, which also stops the audio babbling while someone
     * sweeps across ten minutes of episode.
     */
    var playingBeforeScrub: Boolean = false
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
 * - **Idle**, chrome hidden. *Every* direction press only raises the chrome — see the
 *   speed bump in `onDirection`. OK plays or pauses.
 * - **Controls**. What a horizontal press means depends on which item holds the ring,
 *   not on a flag: on the progress bar it seeks, on a button it moves focus. Vertical
 *   movement between the bar and the button row belongs to the spatial engine, because
 *   the chrome is one `Y` group with a nested `X` row inside it.
 * - **Scrubbing**. Presses accumulate into a preview and the media does not move.
 *   **OK commits, Back cancels**, and Up/Down are swallowed so a stray press cannot
 *   abandon the gesture halfway.
 *
 * Almost none of that is invented here. `PlaybackSeekUi.Client` — the contract
 * Leanback and YouTube both implement — ends a scrub on an explicit decision: confirm
 * seeks to the previewed position, cancel restores the one it started from. Samsung's
 * platform spec is where focus-on-the-bar comes from. The speed bump is YouTube's own
 * December 2025 change. An earlier version of this player committed a seek on a 500ms
 * timer instead, which is a worse design for a reason worth recording: a timer cannot
 * express cancel, because by the time you have seen that you overshot, the seek has
 * already happened.
 *
 * Two places deliberately part company with YouTube, both noted at the code: the
 * chrome layout follows YouTube rather than Leanback, and a committed seek resumes
 * playback rather than leaving the viewer paused.
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
    /**
     * What is playing, shown top-left while the chrome is up — YouTube's placement.
     * The player takes it as plain strings rather than an episode so it stays a
     * player; [subtitle] is the quieter second line (the show's name).
     */
    title: String? = null,
    subtitle: String? = null,
    /**
     * The account's "autoplay next" preference and a way to flip it, surfaced as a switch
     * in the control row so it can be changed from the sofa without opening Settings
     * (where it also still lives). Null hides the switch, for a player with no such
     * setting behind it.
     */
    autoplayNext: Boolean? = null,
    onToggleAutoplay: (() -> Unit)? = null,
    /**
     * Extra bands rendered inside the chrome, below the transport row.
     *
     * A slot rather than parameters, so the player stays a player and does not learn
     * about episodes: the watch screen fills it with the next/previous rails. Whatever
     * goes here becomes part of the chrome's `Y` group, so the spatial engine walks
     * down into it from the buttons with no extra wiring, and it hides and shows with
     * the rest of the chrome.
     */
    chromeExtra: @Composable () -> Unit = {},
    /**
     * The up-next card, shown as an overlay in the final
     * [TvConfig.UP_NEXT_WINDOW_SECONDS] before the end.
     *
     * Deliberately **not** focusable and never focused: it is a prompt, not a control,
     * so pressing OK still pauses rather than jumping to the next episode — the defect
     * the request called out. The manual way to play it is the up-next rail below the
     * chrome, which now lands on the true next episode. Null when there is nothing after
     * this one.
     */
    upNext: (@Composable () -> Unit)? = null,
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
        // The thumb marks the playhead. Positioned imperatively alongside the fill so
        // the two never disagree, and — like the fill — with no CSS transition on
        // `left`, because this is repainted every frame while playing.
        refs.thumb?.style?.left = "$pct%"

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
        val bufPctInt = bufPct.roundToInt()
        if (bufPctInt != refs.lastBufPct) {
            refs.lastBufPct = bufPctInt
            refs.fillBuf?.style?.width = "$bufPct%"
        }
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
     * chrome back rather than doing what you meant. Media3's `PlayerControlView` and
     * Samsung's TV spec both land on the same five.
     *
     * Does nothing mid-scrub: the scrub has its own readout, and swapping the control
     * bar in over the top of it would hide the position being chosen.
     */
    fun revealControls() {
        if (mode == Mode.Scrubbing) return
        if (mode == Mode.Idle) mode = Mode.Controls
        refs.hideTimer?.let { window.clearTimeout(it) }
        refs.hideTimer = window.setTimeout({
            val v = refs.video
            if (v != null && !v.paused && !qualityOpen) hideControls()
        }, TvConfig.CONTROLS_HIDE_MS)
    }

    fun togglePlay() {
        val v = refs.video ?: return
        if (v.paused) v.play() else v.pause()
    }

    // -------------------------------------------------------------- scrubbing

    /** The preview readout: the offset chosen, and where it lands. */
    fun paintScrub(pending: TvSeek, duration: Double) {
        val target = pending.target(refs.scrubOrigin, duration)
        val sign = if (pending.offsetSeconds >= 0) "+" else "−"
        seekPreview = "$sign${formatTime(abs(pending.offsetSeconds))}  ${formatTime(target)}"
        // The ghost knob rides the bar at the previewed position while the real fill
        // stays put, so the bar shows both where you are and where you are going.
        refs.ghost?.style?.left = if (duration > 0) "${target / duration * 100}%" else "0%"
    }

    /**
     * Leaves scrub mode, restoring whatever playback was doing before it.
     *
     * **A deliberate divergence from YouTube.** YouTube leaves the episode paused
     * after a seek and waits for another OK — a long-standing complaint, and the one
     * thing third-party clients most often add a setting to undo. The pause during the
     * gesture is worth having, because sweeping across ten minutes with the audio
     * still running is unpleasant; making the viewer press OK twice to end up where
     * one press should have taken them is not. Resuming only when it *was* playing
     * keeps a deliberate pause deliberate.
     */
    fun endScrub() {
        refs.scrub = null
        seekPreview = null
        if (refs.playingBeforeScrub) refs.video?.play()
        if (mode == Mode.Scrubbing) mode = Mode.Controls
        revealControls()
    }

    /**
     * Applies the previewed position.
     *
     * The only place in scrub mode that touches `currentTime`, which is what makes
     * holding the D-pad one seek rather than one re-buffer per repeat.
     */
    fun commitScrub() {
        val v = refs.video ?: return
        val pending = refs.scrub ?: return
        if (v.duration.isFinite() && v.duration > 0) {
            val target = pending.target(refs.scrubOrigin, v.duration)
            runCatching { v.currentTime = target }
                .onFailure { Log.w("tv-player", "seek to ${target}s rejected", it) }
        }
        endScrub()
    }

    /**
     * Abandons the gesture.
     *
     * There is no position to put back. Nothing moved the media, so the restore step
     * the contract asks for is the absence of an action rather than an undo — see
     * [TvSeek].
     */
    fun cancelScrub() = endScrub()

    /** A tap: ten seconds, applied at once. */
    fun skip(direction: Int) {
        val v = refs.video ?: return
        if (!v.duration.isFinite() || v.duration <= 0) return
        val target = TvSeek.start(direction).target(v.currentTime, v.duration)
        runCatching { v.currentTime = target }
            .onFailure { Log.w("tv-player", "skip to ${target}s rejected", it) }
    }

    /** A held press: accumulate into the preview, moving nothing. */
    fun scrub(direction: Int) {
        val v = refs.video ?: return
        if (!v.duration.isFinite() || v.duration <= 0) return
        val existing = refs.scrub
        if (existing == null) {
            refs.scrubOrigin = v.currentTime
            refs.playingBeforeScrub = !v.paused
            v.pause()
            refs.hideTimer?.let { window.clearTimeout(it) }
            refs.hideTimer = null
        }
        val pending = existing?.press(direction) ?: TvSeek.start(direction)
        refs.scrub = pending
        mode = Mode.Scrubbing
        paintScrub(pending, v.duration)
    }

    /**
     * One direction press, routed by whether it is a tap or auto-repeat.
     *
     * Already scrubbing means every further press accumulates, repeat or not — once
     * the gesture is open, a deliberate extra nudge belongs to it.
     */
    fun seekPress(direction: Int, repeat: Boolean) {
        if (repeat || refs.scrub != null) scrub(direction) else skip(direction)
    }

    /** Whether the progress bar, rather than a button, currently holds the ring. */
    fun scrubFocused(): Boolean =
        document.activeElement?.getAttribute(ITEM_ATTR) == "scrub"

    /**
     * Moves the ring to a named control in the chrome.
     *
     * Used by the entry rule to place focus by the direction that revealed the chrome,
     * rather than leaving it wherever it happened to be. Absolute placement, so it does
     * not matter where the ring was while the chrome was hidden.
     */
    fun focusChromeItem(itemKey: String) {
        val container = refs.container ?: return
        val target = container.querySelector("[$ITEM_ATTR=\"$itemKey\"]") as? HTMLElement
            ?: return
        SpatialNav.focus(target, direction = null, scope = container)
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
                        // Cancel, per the contract: the gesture is abandoned and the
                        // position it started from is the one still playing.
                        mode == Mode.Scrubbing -> { cancelScrub(); true }
                        mode == Mode.Controls -> { hideControls(); true }
                        else -> false
                    }
                },
                // Media keys reveal the chrome before acting, so pressing Play on a
                // hidden player shows the controls too. Direction and Select keys are
                // deliberately *not* here: each must see the hidden state itself to act
                // correctly (a direction places the ring by entry, an OK plays rather
                // than firing whatever invisible control held focus), and `onAnyKey`
                // runs first — revealing here would flip the mode out from under them.
                onAnyKey = { key -> if (key is Key.Media) revealControls() },
                onDirection = { direction, repeat ->
                    val sign = if (direction == Direction.Left) -1 else 1
                    when {
                        // Swallowed mid-scrub. The contract eats Up and Down in seek
                        // mode so a stray press cannot abandon a gesture halfway
                        // through, and horizontal presses are the gesture itself.
                        mode == Mode.Scrubbing -> {
                            if (direction.isHorizontal) seekPress(sign, repeat)
                            true
                        }

                        /*
                         * Entry from the hidden state: reveal, then land the ring by the
                         * direction that opened the chrome. Up lands on the buttons,
                         * Down or a horizontal press on the bar.
                         *
                         * The horizontal case is the speed bump, kept from YouTube's
                         * December 2025 change: a Left/Right press here only *selects*
                         * the bar, it does not seek. The next press seeks, because the
                         * bar then holds the ring and the rule below fires. That is what
                         * stops a remote sat on by accident from scrubbing a running
                         * episode with a single press.
                         */
                        mode == Mode.Idle -> {
                            revealControls()
                            focusChromeItem(if (direction == Direction.Up) "play" else "scrub")
                            true
                        }

                        // Up on the bar dismisses the chrome, matching Back in this
                        // state. The bar is the topmost control, so Up has nothing to
                        // move to, and "up and away" is the natural gesture for putting
                        // the controls down. Checked before the keep-alive reveal below,
                        // or the chrome would flash back the instant it was hidden.
                        direction == Direction.Up && scrubFocused() -> { hideControls(); true }

                        else -> {
                            // Chrome already shown: any press keeps it alive.
                            revealControls()
                            when {
                                // Horizontal seeks on the bar and moves focus on a
                                // button. One rule, decided by which item holds the ring.
                                direction.isHorizontal && scrubFocused() -> {
                                    seekPress(sign, repeat); true
                                }
                                // Everything else is the engine's: horizontal walks the
                                // button row, vertical walks the bands — bar, buttons,
                                // and the next/previous rails below them.
                                else -> false
                            }
                        }
                    }
                },
                onSelect = { focused ->
                    when {
                        // Confirm, per the contract.
                        mode == Mode.Scrubbing -> { commitScrub(); true }
                        // Chrome hidden: OK reveals and plays or pauses. It must NOT
                        // fire whatever control still holds the (invisible) ring —
                        // focus now persists while hidden, so without this branch an
                        // OK meant to pause would skip ten seconds if Forward-10 had
                        // been the last thing focused. Land the ring on Play too.
                        mode == Mode.Idle -> {
                            revealControls()
                            focusChromeItem("play")
                            togglePlay()
                            true
                        }
                        // The bar is a slider, not a button; OK on it plays or pauses
                        // rather than doing nothing.
                        scrubFocused() -> { togglePlay(); true }
                        // A real control has the ring: let the click through.
                        mode == Mode.Controls && focused?.hasAttribute(ITEM_ATTR) == true -> false
                        else -> { togglePlay(); true }
                    }
                },
                onMedia = { action ->
                    when (action) {
                        MediaAction.PlayPause -> { togglePlay(); true }
                        MediaAction.Play -> { refs.video?.play(); true }
                        MediaAction.Pause -> { refs.video?.pause(); true }
                        // A dedicated transport key is always a discrete skip: it has
                        // no auto-repeat contract to lean on, and a remote that has
                        // these keys expects them to do something on every press.
                        MediaAction.Forward -> { skip(1); true }
                        MediaAction.Rewind -> { skip(-1); true }
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

        // What is playing, top-left, shown with the chrome. YouTube lifts the title
        // out of the control cluster to here; the episode number is the title and the
        // show's name is the quieter line under it.
        if (title != null) {
            Div({ classNames("tv-player-title", if (mode == Mode.Controls) null else "hide") }) {
                Div({ classes("tv-player-title-main") }) { Text(title) }
                subtitle?.let { Div({ classes("tv-player-title-sub") }) { Text(it) } }
            }
        }

        // The up-next prompt, an overlay in the final stretch. Not part of the chrome, so
        // it stays up whether the controls are showing or not, and — see [upNext] — not
        // focusable, so OK still means play/pause. When autoplay is on it also carries a
        // bar that fills as the end (and the automatic jump) approaches.
        if (upNext != null && durationSec > 0 &&
            (durationSec - currentSec) in 1..TvConfig.UP_NEXT_WINDOW_SECONDS
        ) {
            Div({ classes("tv-upnext") }) {
                upNext()
                if (autoplayNext == true) {
                    val elapsed = (TvConfig.UP_NEXT_WINDOW_SECONDS - (durationSec - currentSec))
                        .coerceIn(0, TvConfig.UP_NEXT_WINDOW_SECONDS)
                    Div({ classes("tv-upnext-bar") }) {
                        Div({
                            style {
                                property(
                                    "width",
                                    "${elapsed * 100.0 / TvConfig.UP_NEXT_WINDOW_SECONDS}%",
                                )
                            }
                        })
                    }
                }
            }
        }

        // The seek readout. Big and central, because it is the only feedback that a
        // press registered before the seek actually happens.
        seekPreview?.let {
            Div({ classes("tv-seek-osd", "mono") }) { Text(it) }
        }

        if (qualityOpen && ordered.size > 1) {
            Div({
                classes("tv-q-menu")
                focusGroup("quality", FocusAxis.Y)
                actsAsOptionGroup(S.quality)
            }) {
                ordered.forEach { label ->
                    Div({
                        classNames("tv-q-item", if (label == quality) "on" else null)
                        focusItem("q-$label", entry = label == quality)
                        actsAsOption(selected = label == quality)
                        onClick { qualityOpen = false; onQualitySelected(label) }
                    }) { Text(label) }
                }
            }
        }

        /*
         * The scrubber on top, the buttons in a row beneath it.
         *
         * This is **not** the Leanback reference layout, which docks the primary
         * transport row *above* the bar. It is what YouTube on a television actually
         * does since its December 2025 redesign: the bar sits on top of the cluster
         * with the buttons immediately below, and the episode title is lifted out of
         * the cluster to the top-left of the screen entirely. Where the two disagree,
         * the brief was to look like YouTube.
         *
         * Structurally the chrome is one `Y` group holding two rows, the lower of them
         * a nested `X` group. That is what lets the engine own vertical movement
         * between the bar and the buttons while the player only has to intercept
         * horizontal presses, and nested groups are exactly the case the traversal
         * rules were fixed for.
         */
        Div({
            classNames("tv-ctl", if (mode == Mode.Controls) null else "hide")
            focusGroup("player-chrome", FocusAxis.Y)
        }) {
            // Elapsed at the start, total at the end, flanking the bar — YouTube's
            // placement. Not a focus stop, just a readout.
            Div({ classes("tv-ctl-times", "mono") }) {
                Span { Text(formatTime(currentSec.toDouble())) }
                Span { Text(formatTime(durationSec.toDouble())) }
            }

            /*
             * The bar is a focus stop, not decoration.
             *
             * Samsung's platform spec puts focus here when Left or Right opened the
             * controls, and it is what makes "arrows seek" and "arrows move between
             * buttons" coexist without a mode flag: the answer is simply which item
             * holds the ring.
             */
            Div({
                classes("tv-scrub")
                focusItem("scrub", entry = true)
                // A slider, not a button: it reports a position rather than firing an action,
                // and the position is the thing worth announcing.
                attr("role", "slider")
                attr("aria-label", S.timeline)
                attr("aria-valuemin", "0")
                attr("aria-valuemax", durationSec.toString())
                attr("aria-valuenow", currentSec.toString())
            }) {
                Div({ classes("tv-scrub-buf"); ref { el -> refs.fillBuf = el; onDispose { refs.fillBuf = null } } })
                Div({ classes("tv-scrub-cur"); ref { el -> refs.fillCur = el; onDispose { refs.fillCur = null } } })
                // The playhead marker, at the current position. Last of the fills so it
                // sits on top of them.
                Div({ classes("tv-scrub-thumb"); ref { el -> refs.thumb = el; onDispose { refs.thumb = null } } })
                // Where a scrub would land, drawn only while one is open.
                Div({
                    classNames("tv-scrub-ghost", if (mode == Mode.Scrubbing) "on" else null)
                    ref { el -> refs.ghost = el; onDispose { refs.ghost = null } }
                })
            }

            /*
             * One `X` group for the whole row, even though it reads as three clusters.
             * The clusters are a CSS concern; making them three focus groups would put
             * a wall between the transport buttons and the quality button, because
             * horizontal presses deliberately cannot leave an `X` group.
             */
            Div({ classes("tv-ctl-row"); focusGroup("player-buttons", FocusAxis.X) }) {
                // The autoplay control in the left cell, mirroring the quality button on
                // the right and keeping the transport centred. A labelled pill rather than
                // a bare toggle, because a lone switch in a player says nothing about what
                // it does. A focus stop in this row, so Left from the transport reaches it;
                // never the entry item, so the ring still starts on the bar. Absent when
                // there is no setting behind it.
                Div({ classes("tv-ctl-start") }) {
                    if (autoplayNext != null && onToggleAutoplay != null) {
                        Div({
                            classes("tv-ctl-autoplay")
                            focusItem("autoplay")
                            attr("role", "switch")
                            attr("aria-checked", autoplayNext.toString())
                            // `switch` takes its name from the author, not its contents, so
                            // it needs an explicit one — the same word the pill shows, which
                            // keeps the spoken and visible labels the same (WCAG 2.5.3).
                            attr("aria-label", S.autoplayShort)
                            onClick { onToggleAutoplay() }
                        }) {
                            Span({ classes("tv-ctl-autoplay-label") }) { Text(S.autoplayShort.caps) }
                            Div({
                                classNames("tv-switch", "tv-ctl-switch", if (autoplayNext) "on" else null)
                            }) { Div() }
                        }
                    }
                }

                Div({ classes("tv-ctl-mid") }) {
                    Div({
                        classes("tv-ctl-btn")
                        focusItem("back10")
                        actsAsButton(S.back10)
                        onClick { skip(-1) }
                    }) { Icon(Icons.back10) }

                    Div({
                        classes("tv-ctl-btn", "tv-ctl-btn-lg")
                        focusItem("play")
                        actsAsButton(if (playing) S.pause else S.play)
                        onClick { togglePlay() }
                    }) { Icon(if (playing) Icons.pause else Icons.play) }

                    Div({
                        classes("tv-ctl-btn")
                        focusItem("forward10")
                        actsAsButton(S.forward10)
                        onClick { skip(1) }
                    }) { Icon(Icons.forward10) }
                }

                Div({ classes("tv-ctl-end") }) {
                    if (ordered.size > 1) {
                        val shown = quality ?: ordered.first()
                        Div({
                            classes("tv-ctl-btn", "tv-q-btn", "mono")
                            focusItem("quality")
                            // The name carries the rendition, because the rendition is
                            // what this button visibly shows. "Quality" alone replaced it
                            // and left the current selection unannounced — the one thing
                            // someone opening this menu wants to know first.
                            actsAsButton("${S.quality}: $shown")
                            // And it opens a menu, which an ordinary button does not.
                            ownsPopup(qualityOpen)
                            onClick { qualityOpen = !qualityOpen }
                        }) { Text(shown) }
                    }
                }
            }

            // The next/previous rails, when the watch screen supplies them. Rendered
            // here so they belong to the chrome's `Y` group — the engine walks Down
            // into them from the buttons — and hide and show with everything else.
            chromeExtra()
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
