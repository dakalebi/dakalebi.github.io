package ge.dakalebi.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.core.formatTime
import ge.dakalebi.i18n.S
import io.github.bchmsl.keel.components.ProgressHandle
import io.github.bchmsl.keel.components.ScrubHandle
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement

/** Mutable holders that must not trigger recomposition when they change. */
internal class PlayerRefs {
    var video: HTMLVideoElement? = null
    var container: HTMLElement? = null
    var scrub: ScrubHandle? = null
    var thinBar: ProgressHandle? = null
    var raf: Int? = null
    var hideTimer: Int? = null
    var feedbackTimer: Int? = null
    var clickTimer: Int? = null

    /**
     * The first click of the current click run already toggled playback: its wait ran out
     * before the second click came. A double-click is then undone by the `dblclick`.
     */
    var clickToggled: Boolean = false

    /** Position to restore after a quality swap. */
    var pendingSeek: Double? = null
    var pendingPlay: Boolean = false

    /** Last position the animation loop saw, for detecting real progress. */
    var lastTickTime: Double = -1.0
}

/**
 * What [CustomVideoPlayer] knows and does: the element handles, the state its chrome
 * draws, and the actions its controls, keys and media events share.
 *
 * One per player, held with `remember`. The composable wires it to the page; the
 * control bar ([CustomPlayerControls]) and the element ([CustomPlayerVideo]) read and
 * act through it.
 */
internal class CustomPlayerState {
    val refs = PlayerRefs()

    var playing by mutableStateOf(false)
    var started by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var volume by mutableStateOf(1.0)
    var currentSec by mutableStateOf(0)
    var durationSec by mutableStateOf(0)
    var showControls by mutableStateOf(true)
    var fullscreen by mutableStateOf(false)
    var feedback by mutableStateOf<String?>(null)
    var qualityOpen by mutableStateOf(false)
    var castSupported by mutableStateOf(false)
    var castAvailable by mutableStateOf(false)
    var casting by mutableStateOf(false)

    // ------------------------------------------------------------- painting

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

    // -------------------------------------------------------------- chrome

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

    /** The pointer left the player: the bar goes at once, unless the quality menu is open. */
    fun hideControlsNow() {
        val v = refs.video
        if (v != null && !v.paused && !qualityOpen) showControls = false
    }

    // ------------------------------------------------------------- actions

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

    fun toggleMute() {
        val v = refs.video ?: return
        v.muted = !v.muted
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

    /**
     * Remembers where the episode is and whether it was playing, so the next file
     * picks up there. [CustomPlayerVideo] restores both once the new file's metadata
     * arrives.
     */
    fun holdPositionForQualitySwap() {
        val v = refs.video
        refs.pendingSeek = v?.currentTime
        refs.pendingPlay = v?.paused == false
    }

    // ----------------------------------------------------------- clicks

    /**
     * A click on the picture. [detail] is the click count of the run it belongs to.
     *
     * Plays or pauses after a short wait, so a double-click can cancel the toggle.
     */
    fun clickVideo(detail: Int) {
        refs.clickTimer?.let { window.clearTimeout(it) }
        refs.clickTimer = null
        // The second click of a double-click. The `dblclick` right after it decides.
        if (detail > 1) return
        // Delay so a double-click can cancel the play/pause toggle.
        refs.clickToggled = false
        refs.clickTimer = window.setTimeout({
            refs.clickTimer = null
            refs.clickToggled = true
            togglePlay()
        }, 220)
    }

    fun doubleClickVideo() {
        refs.clickTimer?.let { window.clearTimeout(it) }
        refs.clickTimer = null
        // A double-click slower than the wait: the first click has already paused
        // or played. Put it back, or leaving fullscreen this way pauses the episode.
        if (refs.clickToggled) togglePlay()
        refs.clickToggled = false
        toggleFullscreen()
    }

    /** The player is leaving the page: no timer or frame may outlive it. */
    fun release() {
        refs.hideTimer?.let { window.clearTimeout(it) }
        refs.feedbackTimer?.let { window.clearTimeout(it) }
        refs.clickTimer?.let { window.clearTimeout(it) }
        stopLoop()
    }
}
