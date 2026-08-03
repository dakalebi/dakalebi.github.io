package ge.dakalebi.core

import kotlin.js.Date

actual fun nowMillis(): Double = Date.now()

actual fun dateParts(millis: Double): DateParts {
    val d = Date(millis)
    return DateParts(
        day = d.getDate(),
        // `getMonth()` is zero-based; DateParts is not.
        month = d.getMonth() + 1,
        year = d.getFullYear(),
        hour = d.getHours(),
        minute = d.getMinutes(),
    )
}
