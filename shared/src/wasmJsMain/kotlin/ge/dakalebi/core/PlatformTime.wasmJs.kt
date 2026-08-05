package ge.dakalebi.core

// Kotlin/Wasm has no `kotlin.js.Date`, so the fields come through typed `js(...)`
// externals. Each is a single-expression body, as wasm requires.
private fun dateNow(): Double = js("Date.now()")
private fun dateDay(ms: Double): Int = js("new Date(ms).getDate()")
private fun dateMonth(ms: Double): Int = js("new Date(ms).getMonth()")
private fun dateYear(ms: Double): Int = js("new Date(ms).getFullYear()")
private fun dateHour(ms: Double): Int = js("new Date(ms).getHours()")
private fun dateMinute(ms: Double): Int = js("new Date(ms).getMinutes()")

actual fun nowMillis(): Double = dateNow()

actual fun dateParts(millis: Double): DateParts = DateParts(
    day = dateDay(millis),
    // `getMonth()` is zero-based; DateParts is not.
    month = dateMonth(millis) + 1,
    year = dateYear(millis),
    hour = dateHour(millis),
    minute = dateMinute(millis),
)
