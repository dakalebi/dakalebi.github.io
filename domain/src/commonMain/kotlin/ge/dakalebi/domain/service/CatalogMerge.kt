package ge.dakalebi.domain.service

import ge.dakalebi.domain.model.CaveaEpisode
import ge.dakalebi.domain.model.Episode

/** The id prefix of an episode only Cavea Plus has. */
const val CAVEA_ONLY_ID_PREFIX = "cavea-"

/** The catalog id of the Cavea-only episode [caveaEpisodeId]. */
fun caveaOnlyId(caveaEpisodeId: String): String = CAVEA_ONLY_ID_PREFIX + caveaEpisodeId

/**
 * The whole series: Formula's catalog with Cavea Plus's list joined in, ordered.
 *
 * The two are joined by season and episode number, the only identity they share.
 * - An episode both have keeps everything Formula says and gains [Episode.caveaEpisodeId].
 * - An episode only Formula has is unchanged.
 * - An episode only Cavea has becomes a Cavea-only episode: id `cavea-<Cavea's id>`,
 *   Cavea's still, no video and no Formula fields. It never plays here; the watch
 *   screen links to Cavea for it.
 *
 * When Cavea lists the same numbers twice, the first one wins, so the result does not
 * depend on anything but the order Cavea answered in. The caller logs it.
 *
 * Pure, so the joining rules are tested rather than checked by looking at a screen.
 */
fun mergeCavea(formula: List<Episode>, cavea: List<CaveaEpisode>): List<Episode> {
    if (cavea.isEmpty()) return formula.sortedBy { it.ordinal }

    val byNumbers = LinkedHashMap<Pair<Int, Int>, CaveaEpisode>()
    for (entry in cavea) {
        byNumbers.getOrPut(entry.seasonNumber to entry.episodeNumber) { entry }
    }

    val matched = HashSet<Pair<Int, Int>>()
    val merged = formula.map { episode ->
        val numbers = episode.seasonNumber to episode.episodeNumber
        val twin = byNumbers[numbers] ?: return@map episode
        matched += numbers
        episode.copy(caveaEpisodeId = twin.id)
    }

    val caveaOnly = byNumbers
        .filterKeys { it !in matched }
        .values
        .map { it.toCaveaOnlyEpisode() }

    return (merged + caveaOnly).sortedBy { it.ordinal }
}

private fun CaveaEpisode.toCaveaOnlyEpisode() = Episode(
    id = caveaOnlyId(id),
    formulaEpisodeId = null,
    formulaSeasonId = null,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    title = null,
    thumbnailUrl = thumbnailUrl,
    videoUrl = null,
    sources = emptyMap(),
    durationSeconds = null,
    episodePageUrl = null,
    lastResolvedAtMillis = null,
    updatedAtMillis = null,
    caveaEpisodeId = id,
)
