package ge.dakalebi.domain

import ge.dakalebi.domain.service.FullscreenExitResume
import ge.dakalebi.domain.service.PAUSE_AFTER_EXIT_MILLIS
import ge.dakalebi.domain.service.PAUSE_BEFORE_EXIT_MILLIS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which pauses around the end of iOS's native fullscreen are the browser's, and so are
 * undone, and which are the viewer's, and so are kept. The gaps used are ones measured
 * in the iOS Simulator: for the X button a pause 120 ms before the end and another 480
 * ms after it, for the swipe one pause 310 ms after the end.
 */
class FullscreenExitResumeTest {

    private val rule = FullscreenExitResume()

    // ------------------------------------------------- the browser's pauses

    /** The X button: the pause first, then the end of fullscreen. */
    @Test
    fun a_pause_just_before_fullscreen_ends_is_undone() {
        assertFalse(rule.onPause(10_000.0))
        assertTrue(rule.onFullscreenExit(10_120.0, paused = true))
    }

    /**
     * The X button, whole: the first pause is undone, playback starts again, and the
     * second pause, as the player finishes closing, is undone too.
     */
    @Test
    fun the_x_buttons_second_pause_is_undone_too() {
        rule.onPause(10_000.0)
        assertTrue(rule.onFullscreenExit(10_120.0, paused = true))
        rule.onPlay()
        assertTrue(rule.onPause(10_600.0))
    }

    /** Swiping the player down: fullscreen ends while playing, then the pause. */
    @Test
    fun a_pause_just_after_fullscreen_ends_while_playing_is_undone() {
        assertFalse(rule.onFullscreenExit(10_000.0, paused = false))
        assertTrue(rule.onPause(10_310.0))
    }

    @Test
    fun the_windows_include_their_edges() {
        rule.onPause(0.0)
        assertTrue(rule.onFullscreenExit(PAUSE_BEFORE_EXIT_MILLIS, paused = true))

        rule.onFullscreenExit(0.0, paused = false)
        assertTrue(rule.onPause(PAUSE_AFTER_EXIT_MILLIS))
    }

    // --------------------------------------------------- the viewer's pauses

    /** Paused with the player's own button, then closed it a few seconds later. */
    @Test
    fun a_pause_well_before_fullscreen_ends_is_kept() {
        rule.onPause(10_000.0)
        assertFalse(rule.onFullscreenExit(13_000.0, paused = true))
    }

    @Test
    fun a_pause_just_outside_the_window_before_the_end_is_kept() {
        rule.onPause(0.0)
        assertFalse(rule.onFullscreenExit(PAUSE_BEFORE_EXIT_MILLIS + 1, paused = true))
    }

    /** Closed fullscreen while playing, and the browser kept playing: a later pause is the viewer's. */
    @Test
    fun a_pause_well_after_fullscreen_ends_is_kept() {
        rule.onFullscreenExit(10_000.0, paused = false)
        assertFalse(rule.onPause(10_000.0 + PAUSE_AFTER_EXIT_MILLIS + 1))
    }

    /** Closed while already paused, with no pause of the browser's to undo. */
    @Test
    fun closing_while_paused_long_ago_resumes_nothing() {
        assertFalse(rule.onFullscreenExit(10_000.0, paused = true))
        assertFalse(rule.onPause(10_100.0))
    }

    @Test
    fun closing_with_no_pause_seen_at_all_resumes_nothing() {
        assertFalse(rule.onFullscreenExit(10_000.0, paused = true))
    }

    // -------------------------------------------------- what clears the slate

    /** The page's own Pause button, or the steering wheel's, right after leaving fullscreen. */
    @Test
    fun a_pause_through_the_page_cancels_a_pending_exit() {
        rule.onFullscreenExit(10_000.0, paused = false)
        rule.reset()
        assertFalse(rule.onPause(10_200.0))
    }

    /** The page's Pause between the X button's two pauses: the second one stands. */
    @Test
    fun a_pause_through_the_page_cancels_the_x_buttons_second_undo() {
        rule.onPause(10_000.0)
        assertTrue(rule.onFullscreenExit(10_120.0, paused = true))
        rule.onPlay()
        rule.reset()
        assertFalse(rule.onPause(10_300.0))
    }

    /** Paused, played again, and closed: the old pause is forgotten. */
    @Test
    fun playing_again_forgets_an_earlier_pause() {
        rule.onPause(10_000.0)
        rule.onPlay()
        assertFalse(rule.onFullscreenExit(10_100.0, paused = true))
    }

    /** Swiping down: one undo, and a pause soon after that is the viewer's again. */
    @Test
    fun a_swipe_undoes_at_most_one_pause() {
        rule.onFullscreenExit(10_000.0, paused = false)
        assertTrue(rule.onPause(10_300.0))
        rule.onPlay()
        assertFalse(rule.onPause(10_500.0))
    }

    /** The X button: two undos at most, then a pause is the viewer's again. */
    @Test
    fun the_x_button_undoes_at_most_two_pauses() {
        rule.onPause(10_000.0)
        assertTrue(rule.onFullscreenExit(10_120.0, paused = true))
        rule.onPlay()
        assertTrue(rule.onPause(10_600.0))
        rule.onPlay()
        assertFalse(rule.onPause(10_800.0))
    }

    /** A viewer who paused well before closing opens no window for a later pause. */
    @Test
    fun closing_after_the_viewers_pause_opens_no_window() {
        rule.onPause(10_000.0)
        assertFalse(rule.onFullscreenExit(13_000.0, paused = true))
        rule.onPlay()
        assertFalse(rule.onPause(13_400.0))
    }

    @Test
    fun an_earlier_pause_is_not_reused_by_a_later_exit() {
        rule.onPause(10_000.0)
        assertTrue(rule.onFullscreenExit(10_100.0, paused = true))
        assertFalse(rule.onFullscreenExit(10_200.0, paused = true))
    }

    /** A clock that went backwards is no evidence of anything. */
    @Test
    fun a_negative_gap_counts_as_no_gap_match() {
        rule.onPause(10_000.0)
        assertFalse(rule.onFullscreenExit(9_000.0, paused = true))
    }
}
