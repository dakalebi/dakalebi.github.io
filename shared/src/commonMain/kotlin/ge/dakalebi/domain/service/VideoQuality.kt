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
