package ge.dakalebi.ui.tv

/**
 * The TV UI's tunable numbers, in one place.
 *
 * These are the values worth changing without hunting through the screens: how long
 * the player chrome lingers, how many episodes a rail carries, when the up-next card
 * appears, how long the exit prompt stays armed. They used to be private consts
 * scattered across the player, the watch screen, the browse screen and the input
 * layer, which turned "make the card appear sooner" into a search rather than an edit.
 *
 * Only genuinely shared or user-facing timings live here. A value used once and
 * meaningful only at its call site stays at its call site — over-hoisting a one-off
 * into a config object is the opposite mistake. The seek step is the clearest thing
 * that does *not* belong here: it is a property of [ge.dakalebi.presentation.TvSeek],
 * lives in shared code, and has its own tests.
 */
internal object TvConfig {

    /**
     * How long the player chrome stays up after the last press, while playing.
     *
     * Five seconds, not the web player's 2.5. A remote is slower to aim than a mouse,
     * and the penalty for hiding too early is a press spent bringing the chrome back
     * rather than doing what you meant. Media3's `PlayerControlView` and Samsung's TV
     * spec both land on the same five.
     */
    const val CONTROLS_HIDE_MS = 5_000

    /**
     * How many episodes a rail carries: the player's next and previous rails, and the
     * home screen's "continue" shelf. Enough to reach a few episodes either way without
     * turning a rail into an endurance test for the D-pad.
     */
    const val RAIL_COUNT = 12

    /**
     * Seconds before the end at which the up-next card appears, matching the web
     * player's own three-minute window so the two front ends prompt at the same point.
     */
    const val UP_NEXT_WINDOW_SECONDS = 180

    /** How long "press Back again to exit" stays armed, at the top of the stack. */
    const val BACK_TO_EXIT_WINDOW_MS = 2_000
}
