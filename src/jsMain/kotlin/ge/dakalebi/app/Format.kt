package ge.dakalebi.app

import kotlin.js.Date
import kotlin.math.abs
import kotlin.math.floor

/** Wall-clock epoch millis. Centralised so timestamps stay consistent. */
fun nowMillis(): Double = Date.now()

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

/** Localised timestamp for the "last refreshed" readout. */
fun formatDateTime(millis: Double?): String {
    if (millis == null) return "—"
    return runCatching { Date(millis).toLocaleString("ka-GE") }
        .onFailure { Log.w("format", "cannot localise timestamp $millis", it) }
        .getOrElse { "—" }
}

/**
 * Stand-in artwork for episodes without a still. Deterministic on
 * season/episode so a given episode always gets the same colours — a random
 * palette per render would make the grid feel unstable.
 */
fun fallbackGradient(season: Int, episode: Int): String {
    val palettes = listOf(
        Triple("#0b1220", "#142033", "#1c2d44"),
        Triple("#0c1a18", "#13322b", "#1b4a3d"),
        Triple("#0a1a1f", "#0f2a32", "#15414f"),
        Triple("#15131f", "#1f1b2e", "#2a2440"),
        Triple("#10131a", "#191d28", "#232838"),
        Triple("#0d1820", "#142433", "#1a3346"),
    )
    val (a, b, c) = palettes[abs(season * 7 + episode * 3) % palettes.size]
    return "radial-gradient(120% 90% at 30% 20%, $c 0%, $b 45%, $a 100%)"
}
