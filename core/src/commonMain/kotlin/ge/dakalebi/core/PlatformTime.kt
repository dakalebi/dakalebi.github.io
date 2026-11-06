package ge.dakalebi.core

/**
 * A timestamp broken into the fields [formatDateTime] needs, in local time.
 *
 * [month] is 1-based, unlike most platform calendar APIs. Doing that conversion
 * inside each implementation, rather than in the shared format string, is what
 * keeps an off-by-one from reaching the UI as `00` for January.
 */
data class DateParts(
    val day: Int,
    val month: Int,
    val year: Int,
    val hour: Int,
    val minute: Int,
)

/** Wall-clock epoch millis. Centralised so timestamps stay consistent. */
expect fun nowMillis(): Double

/** Local-time calendar fields for [millis]. */
expect fun dateParts(millis: Double): DateParts
