package ge.dakalebi.data.local

import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.repository.CatalogCache
import kotlinx.serialization.json.Json

/**
 * The catalog on this device, so an ordinary page load costs one Firestore read
 * instead of 932.
 *
 * The catalog is a large, near-static table: 932 documents that change only when
 * an admin presses refresh. Reading all of them on every load was enough to
 * exhaust the Spark tier's 50,000 daily reads in one afternoon of testing, and a
 * handful of real visitors reloading would do the same.
 *
 * `meta/catalog.lastRefreshAtMillis` is the validator. It is bumped by exactly
 * the operation that changes the catalog, so comparing one small document
 * against the cached stamp says whether the other 932 are still current.
 *
 * Deliberately *not* used for watch progress: that changes every few seconds
 * during playback, so a cache would be wrong more often than right.
 *
 * Where the bytes go is [store]'s business. Everything here — the stamp rule, the
 * version key, and treating every storage failure as a lost cache rather than a
 * lost load — holds whatever that turns out to be.
 */
class LocalCatalogCache(private val store: KeyValueStore) : CatalogCache {

    private val json = Json { ignoreUnknownKeys = true }

    /*
     * Both calls below name `CachedCatalog.serializer()` rather than letting the
     * reified overloads look one up.
     *
     * The reified form resolved after a clean build and then failed after an
     * incremental one, with `Serializer for class 'CachedCatalog' is not found` —
     * a runtime lookup that Kotlin/JS cannot always satisfy for an `internal`
     * class. Both call sites swallow the failure by design, so the only symptom
     * was a warning in the console and a cache that silently stopped working:
     * 932 Firestore reads per page load instead of one, which is the exact
     * failure this class exists to prevent. Naming the generated serializer makes
     * it a compile-time reference and takes the runtime lookup out of the picture.
     */

    override fun read(stamp: Double?): List<Episode>? {
        // A null stamp on either side means we cannot prove the cache is
        // current, so it is not used.
        if (stamp == null) return null
        val raw = runCatching { store.get(KEY) }
            .onFailure { Log.w("cache", "device storage unreadable", it) }
            .getOrNull() ?: return null

        val entry = runCatching { json.decodeFromString(CachedCatalog.serializer(), raw) }
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
        val payload = runCatching { json.encodeToString(CachedCatalog.serializer(), entry) }
            .onFailure { Log.w("cache", "could not serialise catalog", it) }
            .getOrNull() ?: return

        if (payload.length > MAX_CHARS) {
            Log.w("cache", "catalog too large to cache (${payload.length} chars)")
            return
        }
        runCatching { store.set(KEY, payload) }
            .onFailure {
                // Quota exceeded, or storage blocked entirely. Losing the cache
                // costs reads, not correctness.
                Log.w("cache", "could not store catalog cache", it)
            }
    }

    override fun clear() {
        runCatching { store.remove(KEY) }
            .onFailure { Log.w("cache", "could not clear catalog cache", it) }
    }

    private companion object {
        /** Bump when [CachedEpisode]'s shape changes, so old entries are ignored. */
        const val KEY = "catalog_cache_v1"

        /**
         * Sized for the tightest store this runs on: browser `localStorage` dies
         * somewhere near 5MB, and 932 episodes is roughly 650KB. Storage failures
         * are handled either way; this only turns a quota error into a clear line.
         */
        const val MAX_CHARS = 4_000_000
    }
}
