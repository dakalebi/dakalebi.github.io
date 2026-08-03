package ge.dakalebi.data.local

import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.repository.CatalogCache
import kotlinx.browser.localStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The catalog in `localStorage`, so an ordinary page load costs one Firestore
 * read instead of 932.
 *
 * The catalog is a large, near-static table: 932 documents that change only
 * when an admin presses refresh. Reading all of them on every load was enough
 * to exhaust the Spark tier's 50,000 daily reads in one afternoon of testing,
 * and a handful of real visitors reloading would do the same.
 *
 * `meta/catalog.lastRefreshAtMillis` is the validator. It is bumped by exactly
 * the operation that changes the catalog, so comparing one small document
 * against the cached stamp says whether the other 932 are still current.
 *
 * Deliberately *not* used for watch progress: that changes every few seconds
 * during playback, so a cache would be wrong more often than right.
 */
class LocalCatalogCache : CatalogCache {

    private val json = Json { ignoreUnknownKeys = true }

    override fun read(stamp: Double?): List<Episode>? {
        // A null stamp on either side means we cannot prove the cache is
        // current, so it is not used.
        if (stamp == null) return null
        val raw = runCatching { localStorage.getItem(KEY) }
            .onFailure { Log.w("cache", "localStorage unreadable", it) }
            .getOrNull() ?: return null

        val entry = runCatching { json.decodeFromString<CachedCatalog>(raw) }
            .onFailure {
                // A shape change or a truncated write — not something to fail
                // over. Drop it and fall back to the network.
                Log.w("cache", "discarding unreadable catalog cache", it)
                clear()
            }
            .getOrNull() ?: return null

        return if (entry.stamp == stamp) entry.episodes.map { it.toDomain() } else null
    }

    override fun write(stamp: Double?, episodes: List<Episode>) {
        if (stamp == null || episodes.isEmpty()) return
        val entry = CachedCatalog(stamp, episodes.map { CachedEpisode.of(it) })
        val payload = runCatching { json.encodeToString(entry) }
            .onFailure { Log.w("cache", "could not serialise catalog", it) }
            .getOrNull() ?: return

        if (payload.length > MAX_BYTES) {
            Log.w("cache", "catalog too large to cache (${payload.length} chars)")
            return
        }
        runCatching { localStorage.setItem(KEY, payload) }
            .onFailure {
                // Quota exceeded, or storage blocked entirely. Losing the cache
                // costs reads, not correctness.
                Log.w("cache", "could not store catalog cache", it)
            }
    }

    override fun clear() {
        runCatching { localStorage.removeItem(KEY) }
            .onFailure { Log.w("cache", "could not clear catalog cache", it) }
    }

    private companion object {
        /** Bump when [CachedEpisode]'s shape changes, so old entries are ignored. */
        const val KEY = "catalog_cache_v1"

        /** localStorage dies somewhere near 5MB; 932 episodes is roughly 650KB. */
        const val MAX_BYTES = 4_000_000
    }
}

/**
 * The on-disk shape, kept separate from [Episode].
 *
 * A serialization annotation on the domain model would put a framework in the
 * one layer that is meant to have none, and it would tie the cache format to
 * the model: renaming a domain field would silently invalidate — or worse,
 * silently mis-read — every cache in the wild. Here the coupling is explicit
 * and the version key next door is what manages it.
 */
@Serializable
private data class CachedCatalog(val stamp: Double, val episodes: List<CachedEpisode>)

@Serializable
private data class CachedEpisode(
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
