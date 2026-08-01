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

/** Outcome of a rebuild, detailed enough to report in a toast. */
data class RefreshResult(
    val seasons: Int,
    val episodes: Int,
    val written: Int,
    val withoutVideo: Int,
)

/**
 * The catalog could not be read, as distinct from being genuinely empty.
 *
 * Carries no message: the domain does not know what language the reader
 * speaks. Presentation turns this type into words.
 */
class CatalogUnavailableException : RuntimeException("catalog unavailable")
