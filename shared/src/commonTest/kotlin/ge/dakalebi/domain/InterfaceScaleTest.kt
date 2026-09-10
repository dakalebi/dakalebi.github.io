package ge.dakalebi.domain

import ge.dakalebi.domain.model.InterfaceScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The size preference's one rule: whatever comes back from storage, the interface is
 * drawn at a size someone can read.
 *
 * This is the only preference in the app that is multiplied into the root font size,
 * which makes it the only one whose bad value is unrecoverable from inside the app —
 * an interface drawn at 5% cannot be used to set it back. So the normalising is not a
 * tidy-up, it is the guard, and it is tested rather than trusted.
 */
class InterfaceScaleTest {

    @Test
    fun an_absent_preference_is_the_designed_size() {
        assertEquals(100, InterfaceScale.normalise(null))
        assertEquals(100, InterfaceScale.DEFAULT)
    }

    @Test
    fun every_offered_step_survives_a_round_trip() {
        InterfaceScale.steps.forEach { step ->
            assertEquals(step, InterfaceScale.normalise(step), "step $step should be kept as it is")
        }
    }

    @Test
    fun a_value_between_steps_snaps_to_the_nearest_one() {
        assertEquals(90, InterfaceScale.normalise(92))
        assertEquals(100, InterfaceScale.normalise(96))
        assertEquals(115, InterfaceScale.normalise(120))
    }

    /**
     * A value from an older build, a hand-edited `localStorage`, or another tab. None
     * of them may reach the font size.
     */
    @Test
    fun a_value_off_the_scale_is_pulled_back_onto_it() {
        assertEquals(75, InterfaceScale.normalise(5))
        assertEquals(75, InterfaceScale.normalise(-400))
        assertEquals(130, InterfaceScale.normalise(1000))
    }

    @Test
    fun the_multiplier_is_the_percentage_as_a_fraction() {
        assertEquals("1", InterfaceScale.multiplierOf(100))
        assertEquals("0.75", InterfaceScale.multiplierOf(75))
        assertEquals("1.3", InterfaceScale.multiplierOf(130))
    }

    /** The same guard, reached through the property that is actually written to CSS. */
    @Test
    fun the_multiplier_normalises_too() {
        assertEquals(InterfaceScale.multiplierOf(130), InterfaceScale.multiplierOf(9000))
    }

    @Test
    fun the_steps_are_ordered_and_contain_the_default() {
        assertEquals(InterfaceScale.steps.sorted(), InterfaceScale.steps)
        assertTrue(InterfaceScale.DEFAULT in InterfaceScale.steps)
    }
}
