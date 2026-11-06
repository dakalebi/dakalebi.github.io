package ge.dakalebi.data.local

import ge.dakalebi.domain.model.Episode
import kotlinx.serialization.Serializable

/**
 * The stored shape, kept separate from [Episode].
 *
 * A serialization annotation on the domain model would put a framework in the
 * one layer that is meant to have none, and it would tie the cache format to
 * the model: renaming a domain field would silently invalidate — or worse,
 * silently mis-read — every cache in the wild. Here the coupling is explicit and
 * [LocalCatalogCache]'s version key is what manages it.
 */
@Serializable
internal data class CachedCatalog(val stamp: Double, val episodes: List<CachedEpisode>)

@Serializable
internal data class CachedEpisode(
    val id: String,
    val formulaEpisodeId: Int,
    val formulaSeasonId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val videoUrl: String? = null,
    val sources: Map<String, String> = emptyMap(),
    val durationSeconds: Int? = null,
    val episodePageUrl: String,
    val lastResolvedAtMillis: Double? = null,
    val updatedAtMillis: Double? = null,
) {
    fun toDomain() = Episode(
        id = id,
        formulaEpisodeId = formulaEpisodeId,
        formulaSeasonId = formulaSeasonId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        title = title,
        thumbnailUrl = thumbnailUrl,
        videoUrl = videoUrl,
        sources = sources,
        durationSeconds = durationSeconds,
        episodePageUrl = episodePageUrl,
        lastResolvedAtMillis = lastResolvedAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

    companion object {
        fun of(episode: Episode) = CachedEpisode(
            id = episode.id,
            formulaEpisodeId = episode.formulaEpisodeId,
            formulaSeasonId = episode.formulaSeasonId,
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            title = episode.title,
            thumbnailUrl = episode.thumbnailUrl,
            videoUrl = episode.videoUrl,
            sources = episode.sources,
            durationSeconds = episode.durationSeconds,
            episodePageUrl = episode.episodePageUrl,
            lastResolvedAtMillis = episode.lastResolvedAtMillis,
            updatedAtMillis = episode.updatedAtMillis,
        )
    }
}
