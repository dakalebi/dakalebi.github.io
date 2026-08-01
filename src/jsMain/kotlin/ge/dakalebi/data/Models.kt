package ge.dakalebi.data

import kotlinx.serialization.Serializable

/**
 * An episode as stored in Firestore.
 *
 * [id] is Formula's own episode id (as a string), used directly as the document
 * id. The original Supabase schema used a random UUID with `formula_episode_id`
 * as a unique column, which meant watch URLs changed whenever the catalog was
 * rebuilt. Keying on Formula's id makes `#/watch/531` permanent.
 */
@Serializable
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
}

/** Per-user watch progress. Lives at `users/{uid}/progress/{episodeId}`. */
data class WatchProgress(
    val episodeId: String,
    val progressSeconds: Int,
    val durationSeconds: Int?,
    val isWatched: Boolean,
    val lastWatchedAtMillis: Double,
) {
    val isStarted: Boolean get() = !isWatched && progressSeconds > 0

    /** 0..100. A watched episode always reads as full. */
    val percent: Double
        get() = when {
            isWatched -> 100.0
            durationSeconds != null && durationSeconds > 0 ->
                (progressSeconds.toDouble() / durationSeconds * 100).coerceIn(0.0, 100.0)
            else -> 0.0
        }
}

/** Catalog metadata, stored once at `meta/catalog`. */
data class CatalogMeta(
    val lastRefreshAtMillis: Double?,
    val seasonCount: Int,
    val episodeCount: Int,
)

/** Outcome of a catalog refresh, surfaced in the toast. */
data class RefreshResult(
    val seasons: Int,
    val episodes: Int,
    val written: Int,
    val withoutVideo: Int,
    /**
     * The catalog the refresh just built. Carried back so the caller can adopt
     * it directly; re-reading the collection afterwards cost another 932 reads
     * to learn what the refresh already knew.
     */
    val catalog: List<Episode>,
)
