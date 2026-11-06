package ge.dakalebi.domain.usecase

import ge.dakalebi.domain.model.Catalog
import ge.dakalebi.domain.model.CatalogMeta
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.repository.CatalogRepository
import ge.dakalebi.testing.FakeProgressRepository
import ge.dakalebi.testing.caveaOnlyEpisode
import ge.dakalebi.testing.episode
import ge.dakalebi.testing.progress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What an episode only Cavea Plus has does to progress and durations.
 *
 * Progress on such an episode is kept under its `cavea-` id. When Formula later
 * publishes the episode, that id disappears from the catalog, and the viewer's
 * history must follow the episode rather than stay behind on an id nothing shows.
 */
class CaveaProgressTest {

    /** Formula has published Cavea's c19e1 as episode 19/1. */
    private val published = episode(19, 1, id = "9001", caveaEpisodeId = "c19e1")
    private val catalog = listOf(episode(1, 1), published, caveaOnlyEpisode(19, 2))

    @Test
    fun progress_on_a_published_episode_moves_to_formulas_id() = runTest {
        val repo = FakeProgressRepository(mapOf("cavea-c19e1" to progress("cavea-c19e1", 600, at = 10.0)))

        val result = CarryOverCaveaProgress(repo)("u", catalog, repo.stored.toMap())

        assertEquals(progress("9001", 600, at = 10.0), result["9001"])
        assertNull(result["cavea-c19e1"])
        assertEquals(result, repo.stored.toMap())
    }

    @Test
    fun a_newer_entry_already_under_formulas_id_wins_and_the_old_one_goes() = runTest {
        val newer = progress("9001", 1_400, watched = true, at = 20.0)
        val repo = FakeProgressRepository(
            mapOf("9001" to newer, "cavea-c19e1" to progress("cavea-c19e1", 600, at = 10.0)),
        )

        val result = CarryOverCaveaProgress(repo)("u", catalog, repo.stored.toMap())

        assertEquals(mapOf("9001" to newer), result)
        assertEquals(mapOf("9001" to newer), repo.stored.toMap())
    }

    @Test
    fun progress_on_an_episode_still_only_on_cavea_stays() = runTest {
        val repo = FakeProgressRepository(mapOf("cavea-c19e2" to progress("cavea-c19e2", 300)))
        val current = repo.stored.toMap()

        val result = CarryOverCaveaProgress(repo)("u", catalog, current)

        assertEquals(current, result)
        assertEquals(0, repo.saves)
    }

    @Test
    fun nothing_left_behind_costs_nothing() = runTest {
        val repo = FakeProgressRepository(mapOf("s1e1" to progress("s1e1", 300)))
        val current = repo.stored.toMap()

        assertSame(current, CarryOverCaveaProgress(repo)("u", catalog, current))
        assertEquals(0, repo.saves)
    }

    @Test
    fun a_cavea_only_episode_never_records_a_duration() = runTest {
        val recorded = RecordingCatalog()

        assertFalse(RecordEpisodeDuration(recorded)(caveaOnlyEpisode(19, 2), 1_500))
        assertTrue(recorded.durations.isEmpty())

        assertTrue(RecordEpisodeDuration(recorded)(episode(1, 1, durationSeconds = null), 1_500))
        assertEquals(listOf("s1e1" to 1_500), recorded.durations)
    }

    private class RecordingCatalog : CatalogRepository {
        val durations = mutableListOf<Pair<String, Int>>()
        override suspend fun load() = Catalog(emptyList(), null)
        override suspend fun listEpisodes() = emptyList<Episode>()
        override suspend fun getMeta(): CatalogMeta? = null
        override suspend fun refresh(nowMillis: Double, onProgress: (Int, Int) -> Unit) =
            RefreshResult(0, 0, 0, 0, emptyList())
        override suspend fun resolveVideo(episode: Episode) = episode
        override suspend fun recordDuration(episodeId: String, durationSeconds: Int) {
            durations += episodeId to durationSeconds
        }
    }
}
