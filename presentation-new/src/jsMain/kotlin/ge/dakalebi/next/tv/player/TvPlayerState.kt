package ge.dakalebi.next.tv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.core.formatTime
import ge.dakalebi.next.tv.TvConfig
import ge.dakalebi.next.tv.focus.ENTRY_ATTR
import ge.dakalebi.next.tv.focus.GROUP_ATTR
import ge.dakalebi.next.tv.focus.ITEM_ATTR
import ge.dakalebi.next.tv.focus.SpatialNav
import ge.dakalebi.presentation.TvSeek
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.asList

/** What the D-pad currently means. See `TvVideoPlayer` for the three. */
enum class Mode { Idle, Controls, Scrubbing }

/** Mutable holders that must not trigger recomposition when they change. */
class TvPlayerRefs {
    var video: HTMLVideoElement? = null
    var container: HTMLElement? = null
    var fillCur: HTMLElement? = null
    var fillBuf: HTMLElement? = null
    var thumb: HTMLElement? = null
    var ghost: HTMLElement? = null

    /** The sliding column of episode rails; see `TvVideoPlayer`'s note on the shelf. */
    var shelfTrack: HTMLElement? = null

    /** The quality menu, while it is open. Used as its own input layer's root. */
    var qualityMenu: HTMLElement? = null

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
 * What `TvVideoPlayer` knows and does: the element handles, the mode and the state the
 * chrome draws, and the actions the remote, the buttons and the media events share.
 *
 * One per player, held with `remember`. The input layer ([playerLayer]), the element
 * ([TvPlayerVideo]) and the chrome (`TvPlayerChrome`) all act through it.
 */
class TvPlayerState {
    val refs = TvPlayerRefs()
    var mode by mutableStateOf(Mode.Controls)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var currentSec by mutableStateOf(0)
    var durationSec by mutableStateOf(0)
    var seekPreview by mutableStateOf<String?>(null)
    var qualityOpen by mutableStateOf(false)

    /**
     * How far the episode shelf has been pulled up, in whole bands.
     *
     * `0` is the resting state: the transport is up and the first band only peeks above
     * the bottom edge. `1` puts the first band fully on screen with the second peeking,
     * `2` the second with the third peeking, and so on — YouTube's progressive reveal.
     *
     * **Derived from wherever the ring is, never driven.** A `focusin` listener maps the
     * focused element back to the band that holds it, so the shelf cannot disagree with
     * the cursor. Driving it from the key handler instead would mean re-deciding, in the
     * player, everything the spatial engine already decides correctly about which band a
     * press lands in.
     */
    var shelfLevel by mutableStateOf(0)

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
     * The bands the shelf holds, in the order they are drawn.
     *
     * Read off the DOM rather than passed in, because the bands arrive through
     * `TvVideoPlayer`'s `chromeExtra` and the player is not told what they are. One
     * focus group is one band: that is the only structural thing the shelf assumes
     * about the slot.
     */
    fun shelfBands(): List<HTMLElement> =
        refs.shelfTrack
            ?.querySelectorAll("[$GROUP_ATTR]")
            ?.asList()
            ?.filterIsInstance<HTMLElement>()
            ?.mapNotNull { it.parentElement as? HTMLElement }
            ?: emptyList()

    /**
     * Slides the shelf so the band at [shelfLevel] sits at the top of its window.
     *
     * Measured rather than calculated from a row pitch. The offset is the band's
     * position *within the track*, which is the difference of two rects and therefore
     * unaffected by the translate already applied — so this stays correct however many
     * times it runs, and does not care that a band's height depends on what is in it.
     *
     * At level 0 the track is pushed back down by the window's own height, less the
     * peek, which is the one case with no band to align to.
     */
    fun applyShelf(level: Int) {
        val track = refs.shelfTrack ?: return
        // The window is `overflow: hidden`, which still scrolls: a browser bringing a
        // focused child into view moves it, and that would silently offset everything
        // the transform below computes. The engine focuses with `preventScroll`, so this
        // should already be zero — pinning it costs nothing and removes the whole class
        // of drift, including whatever a WebView decides to do on its own.
        (track.parentElement as? HTMLElement)?.scrollTop = 0.0
        val band = shelfBands().getOrNull(level - 1)
        track.style.transform = if (band == null) {
            "translateY(calc(var(--shelf-h) - var(--shelf-peek)))"
        } else {
            val offset = band.getBoundingClientRect().top - track.getBoundingClientRect().top
            "translateY(${-offset}px)"
        }
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
            // Not while the ring is down in the shelf. Taking the episode list away
            // from under someone who is reading it is the timer doing the opposite of
            // what it is for, and the shelf is the chrome's own surface — the same
            // reason an open quality menu holds it off.
            if (v != null && !v.paused && !qualityOpen && shelfLevel == 0) hideControls()
        }, TvConfig.CONTROLS_HIDE_MS)
    }

    fun togglePlay() {
        val v = refs.video ?: return
        if (v.paused) v.play() else v.pause()
    }

    // -------------------------------------------------------------- scrubbing

    /** The preview readout: the offset chosen, and where it lands. */
    private fun paintScrub(pending: TvSeek, duration: Double) {
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
    private fun endScrub() {
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
    private fun scrub(direction: Int) {
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

    // ------------------------------------------------------------------ focus

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

    /**
     * Lands the ring in the open quality menu, on the rendition that is playing.
     *
     * Explicitly, not through `ensureFocused`. That asks focus memory first, and
     * memory is exactly wrong for this menu: the second open would land on
     * whatever the ring last passed over rather than on what is actually
     * selected — and where the group has a memory but the guardian's other
     * fallbacks do not apply, it lands nowhere at all, which is what happened.
     * A menu of current settings opens on the current setting.
     */
    fun focusCurrentQuality() {
        val menu = refs.qualityMenu ?: return
        val current = menu.querySelector("[$ENTRY_ATTR]") as? HTMLElement
        if (current != null) {
            SpatialNav.focus(current, direction = null, scope = menu)
        } else {
            SpatialNav.ensureFocused(menu)
        }
    }

    /** The player is leaving the page: no timer or frame may outlive it. */
    fun release() {
        refs.hideTimer?.let { window.clearTimeout(it) }
        stopLoop()
    }
}
