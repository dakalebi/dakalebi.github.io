package ge.dakalebi.domain

import ge.dakalebi.presentation.TvSeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scrub rule for D-pad seeking.
 *
 * The only piece of the TV player with logic rather than markup, and the piece that
 * decides whether seeking on a television is usable: the media does not move while
 * the viewer is still choosing, so a held press is one seek rather than one
 * re-buffer per repeat.
 */
class TvSeekTest {

    @Test
    fun a_single_press_is_one_step() {
        val seek = TvSeek.start(direction = 1)

        assertEquals(10.0, seek.offsetSeconds)
        assertEquals(1, seek.presses)
    }

    @Test
    fun repeated_presses_accumulate_rather_than_seeking_each_time() {
        val seek = TvSeek.start(1).press(1).press(1)

        assertEquals(30.0, seek.offsetSeconds)
        assertEquals(3, seek.presses, "the count is what the on-screen readout shows")
    }

    /** Overshooting and correcting inside one gesture is still one seek. */
    @Test
    fun a_reversal_subtracts_instead_of_starting_over() {
        val seek = TvSeek.start(1).press(1).press(-1)

        assertEquals(10.0, seek.offsetSeconds)
        assertEquals(3, seek.presses)
    }

    @Test
    fun a_reversal_can_cross_back_through_the_starting_point() {
        val seek = TvSeek.start(1).press(-1).press(-1)

        assertEquals(-10.0, seek.offsetSeconds, "the offset is signed, not a magnitude")
    }

    @Test
    fun seeking_back_past_the_start_lands_at_zero() {
        val seek = TvSeek.start(-1).press(-1).press(-1)

        assertEquals(0.0, seek.target(positionSeconds = 12.0, durationSeconds = 1_500.0))
    }

    /**
     * Landing exactly on the duration fires `ended`, which marks the episode watched
     * and rolls into the next one. That is not what seeking forward means, so the tail
     * stops short.
     */
    @Test
    fun seeking_forward_past_the_end_stops_short_of_it() {
        val seek = TvSeek.start(1).press(1)

        val target = seek.target(positionSeconds = 1_495.0, durationSeconds = 1_500.0)

        assertEquals(1_499.5, target)
        assertTrue(target < 1_500.0, "must not reach the end and trigger ended")
    }

    @Test
    fun a_zero_length_medium_cannot_be_seeked_into() {
        assertEquals(0.0, TvSeek.start(1).target(positionSeconds = 0.0, durationSeconds = 0.0))
    }

    /**
     * The property the whole preview model rests on: a scrub is a pure calculation
     * over the position it started from, so abandoning one needs no restore step.
     * Committing the same gesture twice from the same origin cannot drift.
     */
    @Test
    fun a_target_is_a_function_of_the_origin_alone() {
        val seek = TvSeek.start(1).press(1).press(1)

        assertEquals(130.0, seek.target(positionSeconds = 100.0, durationSeconds = 1_500.0))
        assertEquals(130.0, seek.target(positionSeconds = 100.0, durationSeconds = 1_500.0))
        assertEquals(230.0, seek.target(positionSeconds = 200.0, durationSeconds = 1_500.0))
    }

    /** A custom step is what lets a caller scale the sweep to the material. */
    @Test
    fun the_step_size_is_the_callers_choice() {
        val seek = TvSeek.start(1, step = 30.0).press(1, step = 30.0)

        assertEquals(60.0, seek.offsetSeconds)
    }
}
