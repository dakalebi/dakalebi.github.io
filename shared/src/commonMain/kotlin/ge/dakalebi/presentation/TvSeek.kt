package ge.dakalebi.presentation

import kotlin.math.max

/**
 * A scrub in progress: a position the viewer is choosing, which the player has not
 * moved to yet.
 *
 * Two different gestures reach a new position on a remote, and conflating them is
 * what makes a television player feel wrong:
 *
 * - **A tap** means "skip a bit". It should apply at once, because a nudge that
 *   waits for confirmation reads as a dropped press.
 * - **A hold** means "take me somewhere". A remote emits auto-repeat far faster than
 *   a network MP4 can seek, so applying each repeat is one re-buffer per press —
 *   hold Right for two seconds and the player spends the next ten recovering.
 *
 * This type models the second. Presses accumulate into an offset the UI can draw
 * while the media stays exactly where it was, and the gesture ends on an explicit
 * decision: [target] to commit, or discard to cancel.
 *
 * **Cancelling is free, and that is the point.** Android's reference contract
 * (`PlaybackSeekUi.Client`) says a cancelled scrub must restore the position it
 * started from. Because nothing here ever moved the media, "restore" is not an
 * operation — it is the absence of one. An earlier version of this class committed
 * on a 500ms settle timer instead, which cannot express cancel at all: by the time
 * you know you overshot, the seek has already happened.
 *
 * Immutable, and free of any clock. The previous design needed the time of each
 * press to know when to fire its timer; deciding on a keypress instead means there
 * is nothing to time, which is also why every rule here is testable without a
 * browser or a media element.
 */
data class TvSeek(
    /** Signed offset from where the gesture started. */
    val offsetSeconds: Double,
    /** How many presses have gone into it, which is what the on-screen readout shows. */
    val presses: Int,
) {
    /**
     * Another press in [direction] (+1 forward, -1 back).
     *
     * A reversal subtracts rather than starting over, so overshooting and correcting
     * within one gesture is still one seek.
     */
    fun press(direction: Int, step: Double = STEP_SECONDS) =
        TvSeek(offsetSeconds + direction * step, presses + 1)

    /**
     * Where the gesture lands, clamped to the media.
     *
     * The tail stops half a second short of the end: a gesture that lands exactly on
     * the duration fires `ended`, which would mark the episode watched and roll into
     * the next one — not what "seek forward" means.
     */
    fun target(positionSeconds: Double, durationSeconds: Double): Double {
        val end = max(0.0, durationSeconds - 0.5)
        return (positionSeconds + offsetSeconds).coerceIn(0.0, max(0.0, end))
    }

    companion object {
        /**
         * One step, for both a tap and a press of a held scrub.
         *
         * Matches the web player's arrow-key step, so the two front ends agree, and
         * matches the ±10s convention the platform players use.
         *
         * Deliberately *not* Leanback's `mDefaultSeekIncrement = 0.01f`, which makes a
         * step 1% of the duration so a full sweep is always a hundred presses. That
         * pays off on material of wildly varying length; every episode here runs 25 to
         * 30 minutes, where 1% is 15 seconds — close enough to 10 that a second step
         * size would be complexity bought for nothing.
         */
        const val STEP_SECONDS = 10.0

        fun start(direction: Int, step: Double = STEP_SECONDS) =
            TvSeek(direction * step, 1)
    }
}
