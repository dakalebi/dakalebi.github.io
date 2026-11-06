package ge.dakalebi.domain

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
}
