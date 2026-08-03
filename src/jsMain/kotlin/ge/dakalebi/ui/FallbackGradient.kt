package ge.dakalebi.ui

import kotlin.math.abs

/**
 * Stand-in artwork for episodes without a still. Deterministic on
 * season/episode so a given episode always gets the same colours — a random
 * palette per render would make the grid feel unstable.
 *
 * Lives in `ui` rather than `core` because it returns CSS. It is a piece of
 * styling that happens to be computed, not a formatting utility.
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
