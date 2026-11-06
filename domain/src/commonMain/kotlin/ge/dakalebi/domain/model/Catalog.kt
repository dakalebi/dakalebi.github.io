package ge.dakalebi.domain.model

/** When the catalog was last rebuilt from the provider, and how big it is. */
data class CatalogMeta(
    val lastRefreshAtMillis: Double?,
    val seasonCount: Int,
    val episodeCount: Int,
)

/** Everything one load of the catalog produces. */
data class Catalog(
    val episodes: List<Episode>,
    val meta: CatalogMeta?,
)

/**
 * Outcome of a rebuild, detailed enough to report in a toast.
 *
 * [seasons], [episodes], [written] and [withoutVideo] are Formula's: what the rebuild
 * fetched from it and wrote back. Cavea Plus's part is the last two. Either source can
 * fail on its own, and its stored part then stands.
 */
data class RefreshResult(
    val seasons: Int,
    val episodes: Int,
    /**
     * How many of Formula's episodes changed and were written, or null when Formula
     * could not be read this time. The stored episodes stand then, and [seasons],
     * [episodes] and [withoutVideo] count those.
     */
    val written: Int?,
    val withoutVideo: Int,
    /**
     * The catalog the rebuild just produced, Cavea Plus's list merged in.
     *
     * Carried out rather than re-read: the caller already holds what it needs,
     * and reading the collection back to learn what we were just told costs
     * another 932 reads.
     */
    val catalog: List<Episode>,
    /**
     * How many episodes Cavea Plus listed, or null when its list could not be read
     * this time. The last good list is kept then, so the catalog still has it.
     */
    val caveaEpisodes: Int? = null,
    /** Episodes only Cavea Plus has, in [catalog]. */
    val caveaOnly: Int = 0,
)

/**
 * The catalog could not be read, as distinct from being genuinely empty.
 *
 * Carries no message: the domain does not know what language the reader
 * speaks. Presentation turns this type into words.
 */
class CatalogUnavailableException : RuntimeException("catalog unavailable")
