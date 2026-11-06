package ge.dakalebi.domain.model

/**
 * How large the television interface is drawn, as a percentage of its designed size.
 *
 * **Why this is a setting at all.** The TV sheet sizes everything from one root font
 * size, and that size is a fraction of the viewport: `min(1.667vw, 2.963vh)`, so one
 * rem is a sixtieth of the panel's width whatever the panel is. That is the right
 * default and it is the only thing a web page can know — CSS pixels say nothing about
 * how many inches the panel measures or how far away the sofa is. A 32-inch set in a
 * bedroom and a 75-inch set across a living room report the same 960x540, and the
 * interface that is comfortable on one is too big or too small on the other. Nothing
 * the layout can measure distinguishes them, so the viewer has to say.
 *
 * **Percentages rather than named sizes**, because the thing being chosen genuinely is
 * a multiplier and everyone already reads a percentage as zoom. Named steps would have
 * to invent a scale ("Medium" of what?) and would still be arbitrary underneath.
 *
 * Kept in the domain rather than beside the screen that renders it because the storage
 * layer validates against it: a value read back from `localStorage` is whatever was
 * last written there, including by a version of this app that offered different steps.
 */
object InterfaceScale {

    /**
     * The offered steps, smallest first.
     *
     * Five, spanning three quarters to a third larger. Wide enough to cover both
     * complaints that prompted this — "so big" on one panel and "so small" on another —
     * and short enough to cross with a D-pad in two presses. A continuous slider was the
     * alternative and is worse here: a remote has no fine control, and a held key gives
     * no preview until it is released.
     */
    val steps: List<Int> = listOf(75, 90, 100, 115, 130)

    /** The designed size, and what an unset or unrecognised preference means. */
    const val DEFAULT: Int = 100

    /**
     * The nearest offered step to [value], or [DEFAULT] when there is nothing to read.
     *
     * Nearest rather than rejecting outright, so a stored value from a build with a
     * different set of steps lands somewhere sensible instead of silently resetting an
     * interface the viewer had deliberately made larger.
     */
    fun normalise(value: Int?): Int {
        if (value == null) return DEFAULT
        return steps.minBy { step -> kotlin.math.abs(step - value) }
    }

    /** The CSS multiplier for a percentage, as the stylesheet's `--tv-scale` wants it. */
    fun multiplierOf(percent: Int): String = (normalise(percent) / 100.0).toString()
}
