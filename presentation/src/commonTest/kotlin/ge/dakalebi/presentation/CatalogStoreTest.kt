package ge.dakalebi.presentation

import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.ProgressRepository
import ge.dakalebi.testing.FakeProgressRepository
import ge.dakalebi.testing.episode
import ge.dakalebi.testing.progress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Loading the catalog, now that the session starts every load.
 *
 * Two callers can still meet: the session's load and the fixture preload in
 * `Main.kt`. And the session stops a load when the viewer changes. These pin
 * that the two meet in one fetch, and that a stopped load leaves nothing behind
 * that looks like a failure.
 */
class CatalogStoreTest {

    @Test
    fun a_second_load_for_the_same_viewer_waits_for_the_first() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = StubCatalogRepository().apply { loadGate = gate }
        val store = catalogStore(repository)

        val first = launch { store.ensureLoaded("uid-1") }
        val second = launch { store.ensureLoaded("uid-1") }
        runCurrent()
        gate.complete(Unit)
        joinAll(first, second)

        assertEquals(1, repository.loads)
        assertEquals(2, store.episodes.size)
        assertFalse(store.loading)
    }

    @Test
    fun a_stopped_load_is_not_a_failed_one() = runTest {
        val store = catalogStore(StubCatalogRepository().apply { loadGate = CompletableDeferred() })

        val load = launch { store.ensureLoaded("uid-1") }
        runCurrent()
        load.cancelAndJoin()

        assertNull(store.loadError)
        assertTrue(store.loading, "whoever stopped it loads next, or resets")
    }

    @Test
    fun the_load_after_a_stopped_one_runs() = runTest {
        val repository = StubCatalogRepository().apply { loadGate = CompletableDeferred() }
        val store = catalogStore(repository)
        val load = launch { store.ensureLoaded("uid-1") }
        runCurrent()
        load.cancelAndJoin()

        repository.loadGate = null
        store.ensureLoaded("uid-1")

        assertEquals(2, repository.loads)
        assertEquals(2, store.episodes.size)
        assertFalse(store.loading)
    }

    @Test
    fun a_failed_load_keeps_the_failure_and_stops_loading() = runTest {
        val store = catalogStore(StubCatalogRepository().apply { fails = true })

        store.ensureLoaded("uid-1")

        assertTrue(store.loadError != null)
        assertFalse(store.loading)
    }

    // ---------------------------------------------------------- Cavea Plus

    /** Formula has since published Cavea's c1e2 as episode e2. */
    private val joined = listOf(episode(1, 1, id = "e1"), episode(1, 2, id = "e2", caveaEpisodeId = "c1e2"))

    @Test
    fun a_load_moves_progress_left_on_a_cavea_only_id_to_the_episode_it_became() = runTest {
        val progress = FakeProgressRepository(mapOf("cavea-c1e2" to progress("cavea-c1e2", 700, at = 5.0)))
        val store = catalogStore(StubCatalogRepository(joined), progress)

        store.ensureLoaded("uid-1")

        assertEquals(setOf("e2"), store.progress.keys)
        assertEquals(700, store.progress.getValue("e2").progressSeconds)
        assertEquals(setOf("e2"), progress.stored.keys)
    }

    /** Nothing is lost by failing: the entry stays where it was for the next load. */
    @Test
    fun a_failed_move_does_not_fail_the_load() = runTest {
        val stored = FakeProgressRepository(mapOf("cavea-c1e2" to progress("cavea-c1e2", 700)))
        val refusing = object : ProgressRepository by stored {
            override suspend fun save(uid: String, progress: WatchProgress): Boolean = error("offline")
        }
        val store = catalogStore(StubCatalogRepository(joined), refusing)

        store.ensureLoaded("uid-1")

        assertNull(store.loadError)
        assertFalse(store.loading)
        assertEquals(setOf("cavea-c1e2"), store.progress.keys)
        assertEquals(setOf("cavea-c1e2"), stored.stored.keys)
    }
}
