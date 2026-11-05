package ge.dakalebi.domain.service

/**
 * Longest gap from a pause to the end of fullscreen for the pause to count as the
 * browser's. With the X button iOS pauses 5 to 120 ms before it reports the end
 * (measured, iOS 27 Simulator). Kept tight because the same order is also what a viewer
 * pausing and then closing looks like.
 */
const val PAUSE_BEFORE_EXIT_MILLIS: Double = 600.0

/**
 * Longest gap from the end of fullscreen to a pause for the pause to count as the
 * browser's. iOS pauses as its player finishes closing: 310 to 430 ms after it reports
 * the end when the player is swiped down, 480 to 760 ms with the X button (measured,
 * the same Simulator). About twice the slowest seen, since a real phone may be slower.
 * Room for a viewer's own pause is not what keeps it short: touching the page closes
 * the window at once (see [FullscreenExitResume.reset]).
 */
const val PAUSE_AFTER_EXIT_MILLIS: Double = 1_500.0

/**
 * Tells the pauses iOS makes when a video leaves its native fullscreen from a pause the
 * viewer made, so the player can undo only the first kind.
 *
 * Safari on iPhone pauses a playing video when its fullscreen player closes. Nothing in
 * the page asks for it, and nothing in the events says why: at the `pause` event the
 * element still reports fullscreen, exactly as it does for a tap on the player's own
 * pause button. What differs is time. The browser's pauses come within moments of the
 * end of fullscreen (`webkitendfullscreen`), which is reported as the player starts to
 * close, not when it is gone:
 *
 * - **The X button**: `pause`, then the end of fullscreen, then, as the player finishes
 *   closing, a second pause. Only a video playing again by then sees the second one.
 * - **Swiping the player down**: the end of fullscreen while still playing, then one
 *   pause as the player finishes closing.
 *
 * So the end of fullscreen opens a window when the video was meant to keep playing
 * (playing then, or paused just before), and the first pause inside it is undone too.
 *
 * A viewer who paused and then closed paused well before the close, and stays paused.
 * So does one who closed while paused, and one who pauses later, inline.
 *
 * The caller reports each event with a monotonic time and acts on a `true` answer by
 * playing again. It reports playback starting with [onPlay], and calls [reset] whenever
 * the viewer acts through the page: its own Pause buttons, or any touch on it, the
 * video's inline controls included. Touches inside Apple's fullscreen player never
 * reach the page, so a touch always means the viewer is back on it.
 *
 * No clock and no element, so every rule here runs in a test.
 */
class FullscreenExitResume(
    private val pauseBeforeExitMillis: Double = PAUSE_BEFORE_EXIT_MILLIS,
    private val pauseAfterExitMillis: Double = PAUSE_AFTER_EXIT_MILLIS,
) {
    /** When the element last went from playing to paused, until something explains it. */
    private var pausedAt: Double? = null

    /**
     * When fullscreen last ended with the video meant to keep playing, until a pause
     * follows. A pause soon after is the browser's.
     */
    private var keepPlayingSince: Double? = null

    /**
     * The element paused at [nowMillis]. True when fullscreen ended just before, with
     * the video meant to keep playing: the pause iOS makes as its player finishes closing.
     */
    fun onPause(nowMillis: Double): Boolean {
        val exited = keepPlayingSince
        keepPlayingSince = null
        if (exited != null && nowMillis - exited in 0.0..pauseAfterExitMillis) {
            pausedAt = null
            return true
        }
        pausedAt = nowMillis
        return false
    }

    /**
     * Fullscreen ended at [nowMillis], with the element [paused] or not. True when it
     * paused just before: the X button's first pause, which was the browser's.
     */
    fun onFullscreenExit(nowMillis: Double, paused: Boolean): Boolean {
        val pause = pausedAt
        pausedAt = null
        val pausedJustBefore = paused && pause != null && nowMillis - pause in 0.0..pauseBeforeExitMillis
        keepPlayingSince = if (!paused || pausedJustBefore) nowMillis else null
        return pausedJustBefore
    }

    /**
     * Playback started: an earlier pause explains nothing now. A window the end of
     * fullscreen opened stays open, since iOS pauses once more after the X button's
     * first pause has been undone.
     */
    fun onPlay() {
        pausedAt = null
    }

    /**
     * The viewer acted through the page: paused with its controls, or touched it.
     * Nothing pending applies now, and the next pause is theirs.
     */
    fun reset() {
        pausedAt = null
        keepPlayingSince = null
    }
}
