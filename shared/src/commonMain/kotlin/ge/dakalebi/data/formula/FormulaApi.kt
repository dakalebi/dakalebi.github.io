package ge.dakalebi.data.formula

import kotlinx.serialization.json.Json

/**
 * Client for Formula's public middleware API, called directly from the browser.
 *
 * This is only possible because the API answers with `Access-Control-Allow-Origin: *`
 * (verified, including the OPTIONS preflight) — which is what lets the whole app
 * be a static bundle with no backend. The matching rule, that no custom headers
 * may be sent, lives on [httpGetText] where it can be enforced.
 */
class FormulaApi {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private suspend fun getText(path: String): String = httpGetText("$BASE/$path")

    /** All seasons of a series, oldest first. */
    suspend fun fetchSeasons(seriesId: Int = SERIES_ID): List<FormulaSeason> =
        json.decodeFromString(getText("api/tvseries/$seriesId"))

    /** Episodes of one season, keyed by the season's database id — not its number. */
    suspend fun fetchSeasonEpisodes(seasonDbId: Int): List<FormulaEpisode> =
        json.decodeFromString(getText("api/tvseries/se/$seasonDbId"))

    /** A single episode with its current video URLs. */
    suspend fun fetchEpisode(formulaEpisodeId: Int): FormulaEpisode =
        json.decodeFromString(getText("api/tvseries/e/$formulaEpisodeId"))

    companion object {
        private const val BASE = "https://mw-api.formula.ge/formula"

        /** The series this dashboard is for: ჩემი ცოლის დაქალები. */
        const val SERIES_ID: Int = 1
    }
}

class FormulaApiException(message: String) : RuntimeException(message)
