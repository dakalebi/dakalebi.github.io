package ge.dakalebi.data

import ge.dakalebi.app.Log
import kotlinx.browser.localStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Local copy of the episode catalog, so an ordinary page load costs one
 * Firestore read instead of 932.
 *
 * The catalog is a large, near-static table: 932 documents that change only
 * when an admin presses refresh. Reading all of them on every load was enough
 * to exhaust the Spark tier's 50,000 daily reads during one afternoon of
 * testing, and a handful of real visitors reloading would have done the same.
 *
 * `meta/catalog.lastRefreshAtMillis` is the validator. It is bumped by exactly
 * the operation that changes the catalog, so comparing one small document
 * against the cached stamp is enough to know whether the other 932 are still
 * current.
 *
 * Deliberately *not* used for watch progress: that changes every few seconds
 * during playback, so a cache would be wrong more often than right.
 */
object CatalogCache {
    /** Bump when [Episode]'s shape changes, so old entries are ignored. */
    private const val KEY = "catalog_cache_v1"

    /** localStorage dies somewhere near 5MB; 932 episodes is roughly 650KB. */
    private const val MAX_BYTES = 4_000_000

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Entry(val stamp: Double, val episodes: List<Episode>)

    /**
     * The cached catalog, but only if [stamp] matches what the server reports.
     * A null stamp on either side means we cannot prove the cache is current,
     * so it is not used.
     */
    fun read(stamp: Double?): List<Episode>? {
        if (stamp == null) return null
        val raw = runCatching { localStorage.getItem(KEY) }
            .onFailure { Log.w("cache", "localStorage unreadable", it) }
            .getOrNull() ?: return null

        val entry = runCatching { json.decodeFromString<Entry>(raw) }
            .onFailure {
                // A shape change or a truncated write, not something to fail
                // over - drop it and fall back to the network.
                Log.w("cache", "discarding unreadable catalog cache", it)
                clear()
            }
            .getOrNull() ?: return null

        return if (entry.stamp == stamp) entry.episodes else null
    }

    fun write(stamp: Double?, episodes: List<Episode>) {
        if (stamp == null || episodes.isEmpty()) return
        val payload = runCatching { json.encodeToString(Entry(stamp, episodes)) }
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

    fun clear() {
        runCatching { localStorage.removeItem(KEY) }
            .onFailure { Log.w("cache", "could not clear catalog cache", it) }
    }
}
