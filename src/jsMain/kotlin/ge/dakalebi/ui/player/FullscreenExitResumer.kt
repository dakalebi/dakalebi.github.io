package ge.dakalebi.ui.player

import ge.dakalebi.core.Log
import ge.dakalebi.domain.service.FullscreenExitResume
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event

/**
 * Plays [video] again when iOS pauses it on the way out of its native fullscreen.
 *
 * Closing Safari's fullscreen player on an iPhone pauses the video. That is the browser,
 * not this app (a bare page with a `<video>` does the same), so it is undone here, and
 * only for a video that was playing. The X button pauses twice, once as the player starts
 * to close and once as it finishes, so one exit can mean two undos. Which pause is the
 * browser's is decided by [FullscreenExitResume]; this class only feeds it the events.
 *
 * Only on an iPhone or iPad. A desktop browser does not pause on leaving fullscreen, so
 * there is nothing to undo, and a viewer there who pauses and then presses Escape must
 * stay paused.
 *
 * Every `pause` the page itself causes must go through [viewerPaused] first: a press on
 * the page's own Pause button, or the steering wheel's, right after leaving fullscreen is
 * the viewer's choice and is never undone. A tap on the video's own inline controls
 * needs nothing from the caller: any touch on the page does the same here.
 */
class FullscreenExitResumer(private val video: HTMLVideoElement) {

    private val rule = FullscreenExitResume()

    /** The source at the last pause. A pause and an exit around a source change are not one gesture. */
    private var pausedSource: String? = null

    private val onPlay: (Event) -> Unit = { rule.onPlay() }

    private val onPause: (Event) -> Unit = {
        pausedSource = video.currentSrc
        if (rule.onPause(now())) resume("after")
    }

    private val onFullscreenEnd: (Event) -> Unit = {
        if (rule.onFullscreenExit(now(), paused = video.paused) && video.currentSrc == pausedSource) {
            resume("before")
        }
    }

    // Touches inside Apple's fullscreen player never reach the page (checked in the
    // Simulator: the X button, its controls and the swipe down). A tap on the inline
    // controls does, as a pointerdown on the video. So any touch here is the viewer's.
    private val onTouch: (Event) -> Unit = { rule.reset() }

    /** Attaches the listeners. Returns the teardown. */
    fun install(): () -> Unit {
        if (!isAppleMobile) return {}
        video.addEventListener("play", onPlay)
        video.addEventListener("pause", onPause)
        // WebKit's own event for its native player closing. The standard
        // `fullscreenchange` never fires for it on an iPhone.
        video.addEventListener("webkitendfullscreen", onFullscreenEnd)
        // Capture, so a control that stops the event still counts.
        document.addEventListener("pointerdown", onTouch, true)
        return {
            video.removeEventListener("play", onPlay)
            video.removeEventListener("pause", onPause)
            video.removeEventListener("webkitendfullscreen", onFullscreenEnd)
            document.removeEventListener("pointerdown", onTouch, true)
            rule.reset()
        }
    }

    /** The viewer paused through the page. Call before `video.pause()`. */
    fun viewerPaused() = rule.reset()

    /**
     * [order] is which side of the end of fullscreen the browser's pause fell on, for
     * the log: if a real iPhone ever needs other windows, this says which one.
     */
    private fun resume(order: String) {
        // An episode that ended pauses too (`pause`, then `ended`). Playing it again
        // would start it over. A failed load and a hidden page (the phone locked
        // mid-exit) are no reason to play either.
        if (video.ended || video.error != null || document.asDynamic().visibilityState == "hidden") return
        Log.d("player", "iOS paused on leaving fullscreen ($order the exit); playing on")
        video.play().catch { Log.w("player", "play() after leaving fullscreen refused", it) }
    }

    private fun now(): Double = window.performance.now()
}
