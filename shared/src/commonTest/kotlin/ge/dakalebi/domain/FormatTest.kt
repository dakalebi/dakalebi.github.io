package ge.dakalebi.domain

import ge.dakalebi.core.dateParts
import ge.dakalebi.core.formatDateTime
import ge.dakalebi.core.formatDuration
import ge.dakalebi.core.formatTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatTest {

    @Test
    fun a_runtime_under_an_hour_reads_as_minutes_and_seconds() {
        assertEquals("0:00", formatTime(0.0))
        assertEquals("0:07", formatTime(7.4))
        assertEquals("1:05", formatTime(65.0))
        assertEquals("59:59", formatTime(3599.0))
    }

    @Test
    fun a_runtime_past_an_hour_gains_an_hours_field() {
        assertEquals("1:00:00", formatTime(3600.0))
        assertEquals("2:03:04", formatTime(7384.0))
    }

    /** A media element reports both of these, and neither should reach the UI. */
    @Test
    fun a_nonsense_runtime_reads_as_zero() {
        assertEquals("0:00", formatTime(Double.NaN))
        assertEquals("0:00", formatTime(-12.0))
    }

    @Test
    fun a_duration_is_absent_rather_than_zero_when_it_is_not_known() {
        assertNull(formatDuration(null))
        assertNull(formatDuration(0))
        assertNull(formatDuration(-1))
        assertEquals("21:00", formatDuration(1260))
    }

    @Test
    fun a_missing_timestamp_reads_as_a_dash() {
        assertEquals("—", formatDateTime(null))
    }

    /**
     * Pins the shape rather than the value, because the parts are local-time and
     * a fixed expectation would only pass in one timezone.
     *
     * The shape is the part that has actually broken: `toLocaleString("ka-GE")`
     * silently produced `8/1/2026, 1:27:35 AM` here. It is also what will catch a
     * platform whose calendar months are zero-based, since that renders `00`.
     */
    @Test
    fun a_timestamp_is_day_first_and_twenty_four_hour() {
        val shape = Regex("""^\d{2}\.\d{2}\.\d{4}, \d{2}:\d{2}$""")
        for (millis in listOf(0.0, 1_754_000_000_000.0, 1_767_225_600_000.0)) {
            val text = formatDateTime(millis)
            assertTrue(shape.matches(text), "unexpected shape for $millis: $text")
            assertTrue(dateParts(millis).month in 1..12, "months must be 1-based, got $text")
        }
    }
}
