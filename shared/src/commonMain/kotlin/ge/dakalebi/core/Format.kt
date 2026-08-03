package ge.dakalebi.core

import kotlin.math.floor

/** `m:ss`, or `h:mm:ss` past an hour. */
fun formatTime(seconds: Double): String {
    val s = if (seconds.isNaN() || seconds < 0) 0.0 else seconds
    val total = floor(s).toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "$hours:${minutes.pad()}:${secs.pad()}"
    } else {
        "${total / 60}:${secs.pad()}"
    }
}

fun formatDuration(seconds: Int?): String? {
    if (seconds == null || seconds <= 0) return null
    return formatTime(seconds.toDouble())
}

private fun Int.pad(): String = if (this < 10) "0$this" else toString()

/**
 * Timestamp for the "last refreshed" readout, as `dd.MM.yyyy, HH:mm`.
 *
 * Formatted by hand rather than through a locale-aware formatter. Georgian
 * locale data is not present in every runtime — Chrome here returns `[]` from
 * `Intl.DateTimeFormat.supportedLocalesOf(['ka'])` and silently resolves to
 * `en-US`, which put `8/1/2026, 1:27:35 AM` in an otherwise entirely Georgian
 * interface. Day-first and 24-hour is what the rest of the UI implies, and it
 * renders identically everywhere.
 *
 * Only the calendar arithmetic is platform work, and that is [dateParts]. The
 * layout of the string stays here, so no platform can quietly reformat it.
 */
fun formatDateTime(millis: Double?): String {
    if (millis == null) return "—"
    return runCatching {
        val d = dateParts(millis)
        "${d.day.pad()}.${d.month.pad()}.${d.year}, ${d.hour.pad()}:${d.minute.pad()}"
    }
        .onFailure { Log.w("format", "cannot format timestamp $millis", it) }
        .getOrElse { "—" }
}
