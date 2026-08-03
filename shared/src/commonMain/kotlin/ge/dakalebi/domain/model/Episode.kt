package ge.dakalebi.domain.model

/**
 * One episode of the series.
 *
 * [id] is Formula's own episode id as a string. The original Supabase schema
 * used a random UUID with `formula_episode_id` as a unique column, which meant
 * watch URLs changed whenever the catalog was rebuilt. Keying on Formula's id
 * makes `#/watch/531` permanent.
 *
 * [formulaEpisodeId] and [formulaSeasonId] are the one place the domain names
 * the upstream provider, and they earn it: they are the identity the catalog is
 * keyed on, not an implementation detail of how it happens to be fetched.
 */
data class Episode(
    val id: String,
    val formulaEpisodeId: Int,
    val formulaSeasonId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String?,
    val thumbnailUrl: String?,
    val videoUrl: String?,
    /** Quality label -> URL, best first. Empty for the handful with no video. */
    val sources: Map<String, String>,
    val durationSeconds: Int?,
    val episodePageUrl: String,
    val lastResolvedAtMillis: Double?,
    val updatedAtMillis: Double?,
) {
    val hasVideo: Boolean get() = !videoUrl.isNullOrBlank()

    /** Sort key that orders correctly across season boundaries. */
    val ordinal: Int get() = seasonNumber * 10_000 + episodeNumber

    /**
     * Field-wise comparison used to skip unchanged episodes on a refresh,
     * ignoring the timestamps — which move on every fetch and would otherwise
     * make all 932 documents look changed every time.
     */
    fun sameContentAs(other: Episode): Boolean =
        formulaEpisodeId == other.formulaEpisodeId &&
            formulaSeasonId == other.formulaSeasonId &&
            seasonNumber == other.seasonNumber &&
            episodeNumber == other.episodeNumber &&
            title == other.title &&
            thumbnailUrl == other.thumbnailUrl &&
            videoUrl == other.videoUrl &&
            sources == other.sources
}
