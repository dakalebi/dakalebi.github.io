package ge.dakalebi.domain.service

/**
 * Rank of a quality label, higher being better. Derived from the leading digits
 * so an unfamiliar label from Formula still sorts sensibly; anything without a
 * number ("original") ranks last.
 */
fun qualityRank(label: String): Int = label.takeWhile { it.isDigit() }.toIntOrNull() ?: -1

/**
 * Quality labels best-first.
 *
 * Never rely on the iteration order of a [Map] of sources for this. The map is
 * built best-first by [qualitySources], but it round-trips through a Firestore
 * map field on the way to the UI, and Firestore does not preserve or guarantee
 * key order — the same document came back in two different orders on two
 * consecutive reads.
 */
fun orderedQualityLabels(sources: Map<String, String>): List<String> =
    sources.keys.sortedWith(compareByDescending<String> { qualityRank(it) }.thenBy { it })

/**
 * The highest quality a phone-sized screen starts in. The 1080p file is the slowest
 * to start, and on a screen that size the step down to 720p is hard to see.
 */
const val COMPACT_SCREEN_MAX_QUALITY: Int = 720

/**
 * The rendition to start in when the viewer has not picked one on this device.
 *
 * Without [maxRank], the best one. With it, the best one at or under the cap, and
 * when every numbered rendition is above the cap, the smallest of them, because a
 * smaller file is the reason for the cap. Under a cap a label without a number
 * ("original") never wins over a numbered one: its size is unknown.
 */
fun defaultQualityLabel(sources: Map<String, String>, maxRank: Int? = null): String? {
    val ordered = orderedQualityLabels(sources)
    if (maxRank == null) return ordered.firstOrNull()
    val numbered = ordered.filter { qualityRank(it) >= 0 }
    return numbered.firstOrNull { qualityRank(it) <= maxRank }
        ?: numbered.lastOrNull()
        ?: ordered.firstOrNull()
}
