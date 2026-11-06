package ge.dakalebi.data

import ge.dakalebi.data.cavea.CaveaApi
import ge.dakalebi.data.cavea.CaveaApiException
import ge.dakalebi.data.formula.FormulaApi
import ge.dakalebi.data.formula.FormulaApiException
import ge.dakalebi.data.formula.FormulaEpisode
import ge.dakalebi.data.formula.FormulaSeason
import ge.dakalebi.data.formula.toEpisode
import ge.dakalebi.data.local.LocalCatalogCache
import ge.dakalebi.domain.model.CaveaEpisode
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.testing.FakeKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The admin refresh with each source failing on its own: one never stops the other.
 *
 * The catalog here is one season of 18 episodes on Formula, and Cavea Plus listing
 * those 18 and two more.
 */
class CatalogRefreshTest {

    private val cache = LocalCatalogCache(FakeKeyValueStore())

    private fun formulaEpisode(number: Int, video: String = "https://example.invalid/$number.mp4") =
        FormulaEpisode(id = 1_000 + number, episodeId = number.toString().padStart(2, '0'), videoFhdSrc = video)

    private val formulaSeason = (1..18).map { formulaEpisode(it) }

    /** What an earlier refresh stored, with the runtimes the player measured since. */
    private val stored = formulaSeason.map { it.toEpisode(1, SEASON_ID, EARLIER).copy(durationSeconds = 1_400) }

    private val caveaList = (1..20).map { CaveaEpisode("c$it", 1, it, null) }

    private fun refresh(formula: FormulaApi, cavea: CaveaApi, storage: FakeStorage) =
        CatalogRefresh(formula, cavea, storage, cache)

    @Test
    fun formula_failing_keeps_the_stored_episodes_and_still_brings_in_cavea_s_list() = runTest {
        val storage = FakeStorage(formula = stored)

        val result = refresh(formulaDown(), caveaAnswering(caveaList), storage)(NOW) { _, _ -> }

        assertEquals(stored.mapIndexed { i, e -> e.copy(caveaEpisodeId = "c${i + 1}") }, result.catalog.take(18))
        assertEquals(listOf("cavea-c19", "cavea-c20"), result.catalog.drop(18).map { it.id })
        assertFormulaKept(result, storage)
        assertEquals(20, result.caveaEpisodes)
        assertEquals(2, result.caveaOnly)
        assertEquals(caveaList, storage.cavea, "Cavea's new list is stored")
        assertEquals(listOf<FormulaCounts?>(null), storage.stamps.map { it.second }, "stamped, Formula's counts kept")
        assertEquals(result.catalog, cache.read(NOW))
    }

    @Test
    fun every_kind_of_formula_failure_keeps_the_stored_episodes() = runTest {
        val broken = mapOf(
            "unreadable" to formulaAnswering(seasonsBody = "<html>busy</html>"),
            "no seasons" to formulaAnswering(seasonsBody = "[]"),
            "no episodes" to formulaAnswering(episodesBody = "[]"),
            "a season failing part way" to formulaAnswering(seasons = listOf(SEASON_ID, SEASON_ID + 1)),
        )
        for ((case, formula) in broken) {
            val storage = FakeStorage(formula = stored)

            val result = refresh(formula, caveaAnswering(caveaList), storage)(NOW) { _, _ -> }

            assertFormulaKept(result, storage, case)
            assertEquals(20, result.catalog.size, case)
        }
    }

    @Test
    fun cavea_failing_keeps_its_stored_list_and_formula_s_part_is_still_written() = runTest {
        val storedCavea = caveaList.take(19)
        val storage = FakeStorage(formula = stored, cavea = storedCavea)
        val changedVideo = "https://example.invalid/3-new.mp4"
        val answer = formulaSeason.map { if (it.id == 1_003) formulaEpisode(3, changedVideo) else it }

        val result = refresh(formulaAnswering(answer), caveaAnswering(body = "{not json"), storage)(NOW) { _, _ -> }

        assertEquals(1, result.written)
        assertEquals(listOf("1003"), storage.written.map { it.id })
        assertNull(result.caveaEpisodes)
        assertEquals(storedCavea, storage.cavea, "the stored list is not replaced")
        assertEquals(19, result.catalog.size)
        assertEquals(1, result.caveaOnly)
        assertEquals(listOf<FormulaCounts?>(FormulaCounts(1, 18)), storage.stamps.map { it.second })
    }

    @Test
    fun both_failing_fails_the_refresh_and_writes_nothing() = runTest {
        val storage = FakeStorage(formula = stored, cavea = caveaList)

        assertFailsWith<FormulaApiException> {
            refresh(formulaDown(), caveaAnswering(body = null), storage)(NOW) { _, _ -> }
        }

        assertTrue(storage.written.isEmpty())
        assertTrue(storage.stamps.isEmpty(), "no stamp: every device keeps its cached catalog")
        assertNull(cache.read(NOW))
    }

    /** Cavea fetched but refused by the store is Cavea failing: nothing new would reach anyone. */
    @Test
    fun cavea_not_stored_with_formula_down_fails_the_refresh() = runTest {
        val storage = FakeStorage(formula = stored, caveaStoreFails = true)

        assertFailsWith<FormulaApiException> {
            refresh(formulaDown(), caveaAnswering(caveaList), storage)(NOW) { _, _ -> }
        }

        assertTrue(storage.stamps.isEmpty())
    }

    @Test
    fun both_answering_writes_only_what_changed_and_keeps_the_stored_runtimes() = runTest {
        val storage = FakeStorage(formula = stored)
        val changedVideo = "https://example.invalid/3-new.mp4"
        val answer = formulaSeason.map { if (it.id == 1_003) formulaEpisode(3, changedVideo) else it } + formulaEpisode(19)

        val result = refresh(formulaAnswering(answer), caveaAnswering(caveaList), storage)(NOW) { _, _ -> }

        assertEquals(listOf("1003", "1019"), storage.written.map { it.id })
        assertEquals(2, result.written)
        assertEquals(19, result.episodes)
        val third = result.catalog.single { it.id == "1003" }
        assertEquals(changedVideo, third.videoUrl)
        assertEquals(1_400, third.durationSeconds, "the write keeps the stored runtime, so the cache does too")
        assertNull(result.catalog.single { it.id == "1019" }.durationSeconds)
        assertEquals(listOf("cavea-c20"), result.catalog.filter { it.isCaveaOnly }.map { it.id })
        assertEquals(listOf<FormulaCounts?>(FormulaCounts(1, 19)), storage.stamps.map { it.second })
        assertEquals(result.catalog, cache.read(NOW))
    }

    /** A refresh stopped part way is stopped, not a Formula failure to work around. */
    @Test
    fun a_cancelled_fetch_stops_the_refresh() = runTest {
        val storage = FakeStorage(formula = stored)
        val stopped = FormulaApi(fetch = { _, _ -> throw CancellationException("left the page") })

        assertFailsWith<CancellationException> {
            refresh(stopped, caveaAnswering(caveaList), storage)(NOW) { _, _ -> }
        }

        assertTrue(storage.stamps.isEmpty())
        assertTrue(storage.cavea.isEmpty())
    }

    // ------------------------------------------------------------------ doubles

    private fun assertFormulaKept(result: RefreshResult, storage: FakeStorage, case: String? = null) {
        assertNull(result.written, case)
        assertEquals(18, result.episodes, case)
        assertTrue(storage.written.isEmpty(), "none of Formula's answer is written: $case")
        assertEquals(stored, result.catalog.filterNot { it.isCaveaOnly }.map { it.copy(caveaEpisodeId = null) }, case)
    }

    private fun formulaDown() = FormulaApi(fetch = { url, _ -> throw FormulaApiException("HTTP 503 for $url") })

    /**
     * Formula answering for [seasons], each with [answer] as its episodes, or with the
     * raw bodies given. A season it does not know fails, as Formula does.
     */
    private fun formulaAnswering(
        answer: List<FormulaEpisode> = formulaSeason,
        seasons: List<Int> = listOf(SEASON_ID),
        seasonsBody: String? = null,
        episodesBody: String? = null,
    ) = FormulaApi(fetch = { url, _ ->
        val path = url.substringAfter("/formula/")
        when {
            path == "api/tvseries/1" -> seasonsBody ?: json.encodeToString(
                ListSerializer(FormulaSeason.serializer()),
                seasons.mapIndexed { i, id -> FormulaSeason(id = id, seasonId = "0${i + 1}") },
            )
            path == "api/tvseries/se/$SEASON_ID" ->
                episodesBody ?: json.encodeToString(ListSerializer(FormulaEpisode.serializer()), answer)
            else -> throw FormulaApiException("HTTP 503 for $url")
        }
    })

    /** Cavea Plus answering with [list], or with the raw [body]; a null body is Cavea down. */
    private fun caveaAnswering(list: List<CaveaEpisode>) = caveaAnswering(
        body = """{"id": "${CaveaApi.SERIES_ID}", "episodes": [""" +
            list.joinToString { """{"id": "${it.id}", "seasonNumber": ${it.seasonNumber}, "episodeNumber": ${it.episodeNumber}}""" } +
            "]}",
    )

    private fun caveaAnswering(body: String?) = CaveaApi(fetch = { url, _ -> body ?: throw CaveaApiException("HTTP 503 for $url") })

    private class FakeStorage(
        val formula: List<Episode>,
        var cavea: List<CaveaEpisode> = emptyList(),
        private val caveaStoreFails: Boolean = false,
    ) : CatalogStorage {
        val written = mutableListOf<Episode>()
        val stamps = mutableListOf<Pair<Double, FormulaCounts?>>()

        override suspend fun formulaEpisodes() = formula

        override suspend fun storedCavea() = cavea

        override suspend fun writeEpisodes(episodes: List<Episode>) {
            written += episodes
        }

        override suspend fun storeCavea(episodes: List<CaveaEpisode>, nowMillis: Double) {
            if (caveaStoreFails) error("permission-denied")
            cavea = episodes
        }

        override suspend fun markRefreshed(nowMillis: Double, formula: FormulaCounts?) {
            stamps += nowMillis to formula
        }
    }

    private companion object {
        const val SEASON_ID = 101
        const val EARLIER = 1_000.0
        const val NOW = 2_000.0
        val json = Json
    }
}
