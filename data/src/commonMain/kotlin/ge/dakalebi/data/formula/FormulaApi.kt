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

    private suspend fun getText(path: String, timeoutMillis: Int? = null): String =
        httpGetText("$BASE/$path", timeoutMillis)

    /** All seasons of a series, oldest first. */
    suspend fun fetchSeasons(seriesId: Int = SERIES_ID): List<FormulaSeason> =
        json.decodeFromString(getText("api/tvseries/$seriesId"))

    /** Episodes of one season, keyed by the season's database id — not its number. */
    suspend fun fetchSeasonEpisodes(seasonDbId: Int): List<FormulaEpisode> =
        json.decodeFromString(getText("api/tvseries/se/$seasonDbId"))

    /**
     * A single episode with its current video URLs.
     *
     * Bounded by [EPISODE_TIMEOUT_MILLIS]: the watch screen waits on this before it
     * shows the player, and when it fails the screen plays the stored URL instead.
     * The season fetches have no bound, because only the admin refresh makes them
     * and a slow full answer is better there than a cut-off one.
     */
    suspend fun fetchEpisode(formulaEpisodeId: Int): FormulaEpisode =
        json.decodeFromString(getText("api/tvseries/e/$formulaEpisodeId", EPISODE_TIMEOUT_MILLIS))

    companion object {
        private const val BASE = "https://mw-api.formula.ge/formula"

        /**
         * How long the watch screen waits for fresh video URLs before it falls back
         * to the stored ones. Ample for a slow mobile connection, and an outage then
         * costs five seconds instead of a player that never appears.
         */
        const val EPISODE_TIMEOUT_MILLIS: Int = 5_000

        /** The series this dashboard is for: ჩემი ცოლის დაქალები. */
        const val SERIES_ID: Int = 1
    }
}

class FormulaApiException(message: String) : RuntimeException(message)
