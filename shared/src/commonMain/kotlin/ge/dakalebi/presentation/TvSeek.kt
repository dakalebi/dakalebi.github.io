package ge.dakalebi.presentation

import kotlin.math.max

/**
 * A seek gesture in progress, accumulated but not yet applied.
 *
 * On a television this is the difference between a usable player and an unusable
 * one. A remote emits presses far faster than a network MP4 can seek, so applying
 * each one directly means a re-buffer per press: hold Right for two seconds and the
 * player spends the next ten recovering. Accumulating the presses and seeking once,
 * when the viewer stops, is one seek per gesture instead of one per press.
 *
 * Immutable and free of any clock: the caller passes the time in, which is what
 * makes the rule testable without a browser or a media element.
 */
data class TvSeek(
    /** Signed offset from where the gesture started. */
    val offsetSeconds: Double,
    /** How many presses have gone into it, which is what the on-screen count shows. */
    val presses: Int,
    val lastPressAtMillis: Double,
) {
    /**
     * Another press in [direction] (+1 forward, -1 back).
     *
     * A reversal subtracts rather than starting over, so overshooting and correcting
     * within one gesture is still one seek.
     */
    fun press(direction: Int, nowMillis: Double, step: Double = STEP_SECONDS) =
        TvSeek(offsetSeconds + direction * step, presses + 1, nowMillis)

    /** Whether the viewer has stopped pressing long enough to apply it. */
    fun isSettled(nowMillis: Double, settleMs: Double = SETTLE_MS) =
        nowMillis - lastPressAtMillis >= settleMs

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
        /** Matches the web player's arrow-key step, so the two agree. */
        const val STEP_SECONDS = 10.0

        /**
         * How long after the last press to commit. Long enough that a second press
         * lands inside it at any comfortable repeat rate, short enough that a single
         * press does not feel delayed.
         */
        const val SETTLE_MS = 500.0

        fun start(direction: Int, nowMillis: Double, step: Double = STEP_SECONDS) =
            TvSeek(direction * step, 1, nowMillis)
    }
}
