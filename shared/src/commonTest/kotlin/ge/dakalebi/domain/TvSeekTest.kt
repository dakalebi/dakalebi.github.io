package ge.dakalebi.domain

import ge.dakalebi.presentation.TvSeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The coalescing rule for D-pad seeking.
 *
 * The only piece of the TV player with logic rather than markup, and the piece
 * that decides whether seeking on a television is usable: one seek per gesture
 * instead of one re-buffer per press.
 */
class TvSeekTest {

    @Test
    fun a_single_press_is_one_step() {
        val seek = TvSeek.start(direction = 1, nowMillis = 0.0)

        assertEquals(10.0, seek.offsetSeconds)
        assertEquals(1, seek.presses)
    }

    @Test
    fun repeated_presses_accumulate_rather_than_seeking_each_time() {
        val seek = TvSeek.start(1, 0.0).press(1, 120.0).press(1, 240.0)

        assertEquals(30.0, seek.offsetSeconds)
        assertEquals(3, seek.presses, "the count is what the on-screen readout shows")
    }

    /** Overshooting and correcting inside one gesture is still one seek. */
    @Test
    fun a_reversal_subtracts_instead_of_starting_over() {
        val seek = TvSeek.start(1, 0.0).press(1, 100.0).press(-1, 200.0)

        assertEquals(10.0, seek.offsetSeconds)
        assertEquals(3, seek.presses)
    }

    @Test
    fun a_gesture_settles_only_after_the_last_press() {
        val seek = TvSeek.start(1, 1_000.0)

        assertFalse(seek.isSettled(1_400.0), "still within the window")
        assertTrue(seek.isSettled(1_500.0), "exactly at the window counts as settled")
        assertTrue(seek.isSettled(2_000.0))
    }

    @Test
    fun each_press_extends_the_window() {
        val seek = TvSeek.start(1, 1_000.0).press(1, 1_400.0)

        assertFalse(seek.isSettled(1_600.0), "measured from the second press, not the first")
        assertTrue(seek.isSettled(1_900.0))
    }

    @Test
    fun seeking_back_past_the_start_lands_at_zero() {
        val seek = TvSeek.start(-1, 0.0).press(-1, 10.0).press(-1, 20.0)

        assertEquals(0.0, seek.target(positionSeconds = 12.0, durationSeconds = 1_500.0))
    }

    /**
     * Landing exactly on the duration fires `ended`, which marks the episode
     * watched and rolls into the next one. That is not what seeking forward means,
     * so the tail stops short.
     */
    @Test
    fun seeking_forward_past_the_end_stops_short_of_it() {
        val seek = TvSeek.start(1, 0.0).press(1, 10.0)

        val target = seek.target(positionSeconds = 1_495.0, durationSeconds = 1_500.0)

        assertEquals(1_499.5, target)
        assertTrue(target < 1_500.0, "must not reach the end and trigger ended")
    }

    @Test
    fun a_zero_length_medium_cannot_be_seeked_into() {
        assertEquals(0.0, TvSeek.start(1, 0.0).target(positionSeconds = 0.0, durationSeconds = 0.0))
    }
}
