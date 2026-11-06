package ge.dakalebi.data.local

import ge.dakalebi.domain.model.Episode

/**
 * Local copy of the catalog, keyed by the server's last-refresh stamp.
 *
 * An interface rather than a detail inside the catalog repository so the caching
 * decision — which is about cost, not correctness — is visible in the
 * composition root and can be switched off there. It is the data layer's
 * business only: nothing above `:data` knows a cache exists.
 */
interface CatalogCache {
    /** The cached catalog, but only when [stamp] proves it is still current. */
    fun read(stamp: Double?): List<Episode>?

    fun write(stamp: Double?, episodes: List<Episode>)

    /**
     * Records a duration the player measured on the cached copy of one episode,
     * keeping the stamp it was cached under.
     *
     * Durations are learned locally and do not move the catalog's refresh stamp,
     * so without this the cache keeps serving the old value and every reload
     * forgets the runtime it just measured. Does nothing when nothing is cached.
     */
    fun recordDuration(episodeId: String, durationSeconds: Int)

    fun clear()
}
