package ge.dakalebi.data.cavea

import ge.dakalebi.core.Log
import ge.dakalebi.data.formula.httpGetText
import ge.dakalebi.domain.model.CaveaEpisode
import kotlinx.serialization.json.Json

/**
 * Cavea Plus's public list of the series' episodes: the second source of the catalog.
 *
 * Only the admin refresh calls this, and only for the list: ids, numbers and stills.
 * The answer is public and served with `Access-Control-Allow-Origin: *`, so a bare
 * GET from the browser reads it, with no sign-in, no cookie and no header of ours
 * (see [httpGetText]). Cavea's streams are DRM-protected and are never touched: an
 * episode found only here is a link to Cavea's own watch page, nothing more.
 */
class CaveaApi(private val fetch: suspend (url: String, timeoutMillis: Int?) -> String = ::httpGetText) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * Every episode Cavea lists for the series.
     *
     * Throws [CaveaApiException] when the answer is not the series it should be, or
     * lists nothing: the refresh then keeps the last good list rather than wiping it.
     */
    suspend fun fetchEpisodes(): List<CaveaEpisode> = parseSeries(fetch(SERIES_URL, TIMEOUT_MILLIS))

    internal fun parseSeries(body: String): List<CaveaEpisode> {
        val series = json.decodeFromString(CaveaSeries.serializer(), body)
        if (series.id != SERIES_ID) throw CaveaApiException("unexpected series id '${series.id}'")
        if (series.episodes.isEmpty()) throw CaveaApiException("the series lists no episodes")

        // Cavea names most episodes "S1E1", but not all: 48 of season 19's are named
        // "New season". A count, not a line each, and the episode is kept: the numbers
        // are what the catalog joins on, not the name.
        val unexpectedNames = series.episodes.count { !it.nameMatchesNumbers }
        if (unexpectedNames > 0) Log.d("cavea", "$unexpectedNames episodes named other than S<season>E<episode>")

        return series.episodes.map { it.toDomain() }
    }

    companion object {
        /** The series on Cavea Plus: ჩემი ცოლის დაქალები. */
        const val SERIES_ID = "554b7850-3036-11ed-b171-093ad0cab9c3"

        private const val SERIES_URL = "$CAVEA_ORIGIN/api/v1/movies/my-wife%27s-girlfriends?lang=ka"

        /**
         * Bounded, so a slow Cavea costs the refresh fifteen seconds and not the whole
         * refresh: Formula's part is already fetched by then and is written regardless.
         */
        private const val TIMEOUT_MILLIS = 15_000
    }
}

class CaveaApiException(message: String) : RuntimeException(message)
