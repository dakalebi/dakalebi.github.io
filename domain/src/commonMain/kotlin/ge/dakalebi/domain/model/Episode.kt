package ge.dakalebi.domain.model

/**
 * One episode of the series.
 *
 * [id] is Formula's own episode id as a string. The original Supabase schema
 * used a random UUID with `formula_episode_id` as a unique column, which meant
 * watch URLs changed whenever the catalog was rebuilt. Keying on Formula's id
 * makes `#/watch/531` permanent. An episode only Cavea Plus has is keyed
 * `cavea-<Cavea's id>` instead (see [ge.dakalebi.domain.service.mergeCavea]).
 *
 * [formulaEpisodeId] and [formulaSeasonId] are the one place the domain names
 * the upstream provider, and they earn it: they are the identity the catalog is
 * keyed on, not an implementation detail of how it happens to be fetched. They
 * are null only for an episode Formula does not have.
 *
 * [caveaEpisodeId] names the second provider by the same reasoning: it is the
 * identity of the same episode on Cavea Plus, which the watch screen links to
 * when the episode does not play here. Cavea's streams are DRM-protected, so the
 * app never plays them; the id is only ever a link.
 */
data class Episode(
    val id: String,
    val formulaEpisodeId: Int?,
    val formulaSeasonId: Int?,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String?,
    val thumbnailUrl: String?,
    val videoUrl: String?,
    /** Quality label -> URL, best first. Empty for the handful with no video. */
    val sources: Map<String, String>,
    val durationSeconds: Int?,
    /** Formula's page for the episode. Null when Formula does not have it. */
    val episodePageUrl: String?,
    val lastResolvedAtMillis: Double?,
    val updatedAtMillis: Double?,
    /** The same episode on Cavea Plus, when Cavea has it. */
    val caveaEpisodeId: String? = null,
) {
    /** Plays in the app. */
    val hasVideo: Boolean get() = !videoUrl.isNullOrBlank()

    /** Formula does not have it: only Cavea Plus does. Never plays in the app. */
    val isCaveaOnly: Boolean get() = formulaEpisodeId == null

    /** Cavea Plus has it, so a watch screen can link there when it does not play here. */
    val onCavea: Boolean get() = caveaEpisodeId != null

    /** Sort key that orders correctly across season boundaries. */
    val ordinal: Int get() = seasonNumber * 10_000 + episodeNumber

    /**
     * Field-wise comparison used to skip unchanged episodes on a refresh,
     * ignoring the timestamps — which move on every fetch and would otherwise
     * make all 932 documents look changed every time.
     *
     * Formula's fields only: they are what an `episodes` document holds. Cavea's
     * id is merged in on load and never stored there.
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

/**
 * One episode as Cavea Plus lists it: its id there, its numbers and its still.
 *
 * Read from Cavea's public catalog by the admin refresh only, and stored in one
 * document beside the catalog. Viewers never call Cavea.
 */
data class CaveaEpisode(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val thumbnailUrl: String?,
)
