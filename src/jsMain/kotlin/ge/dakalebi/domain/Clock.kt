package ge.dakalebi.domain

/**
 * Wall-clock time, as a dependency.
 *
 * Every timestamp the app stores comes from here. It exists so a test can say
 * what time it is: progress records carry `lastWatchedAtMillis` and half the
 * "which episode next" logic sorts on it, which is untestable against a real
 * clock.
 */
fun interface Clock {
    fun nowMillis(): Double
}
