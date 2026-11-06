package ge.dakalebi.presentation

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.ProgressRepository
import ge.dakalebi.i18n.S
import ge.dakalebi.testing.FakeProgressRepository
import ge.dakalebi.testing.episode
import ge.dakalebi.testing.progress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dashboard's own state and the changes it makes.
 *
 * What is worth pinning down: the question names the season it was asked about,
 * a change runs once with the controls off until it ends, and every outcome is
 * told, including the failures that used to go unreported or depend on the
 * screen still being there.
 */
class DashboardViewModelTest {

    private val account = Account(uid = "uid-1", email = "a@example.invalid", emailVerified = true)

    /** Two seasons: two episodes of the first, one of the second. */
    private val episodes = listOf(episode(1, 1), episode(1, 2), episode(2, 1))

    private class Setup(
        val dashboard: DashboardViewModel,
        val catalog: CatalogStore,
        val toasts: ToastStore,
        val progress: ScriptedProgress,
        val catalogRepository: StubCatalogRepository,
    ) {
        val ui get() = dashboard.state.value
        val lastToast get() = toasts.items.lastOrNull()
    }

    private fun TestScope.setup(
        accounts: ScriptedAccountRepository = ScriptedAccountRepository(initial = account),
        progress: ScriptedProgress = ScriptedProgress(),
    ): Setup {
        val catalogRepository = StubCatalogRepository(episodes)
        val catalog = catalogStore(catalogRepository, progress)
        // Loads the account's catalog and progress, unconfined, before this returns.
        val session = sessionViewModel(accounts = accounts, catalog = catalog)
        // The toasts' own timers on virtual time, so nothing outlives the test.
        val toasts = ToastStore(this)
        val dashboard = DashboardViewModel(
            catalog = catalog,
            session = session,
            toasts = toasts,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        return Setup(dashboard, catalog, toasts, progress, catalogRepository)
    }

    // ------------------------------------------------------------- season

    @Test
    fun the_suggested_season_is_on_screen_until_one_is_picked() = runTest {
        val setup = setup()
        assertEquals(setup.catalog.defaultSeason, setup.dashboard.season(setup.ui))

        setup.dashboard.pickSeason(2)

        assertEquals(2, setup.dashboard.season(setup.ui))
    }

    // ----------------------------------------------------------- questions

    @Test
    fun a_question_shows_until_dismissed() = runTest {
        val setup = setup()

        setup.dashboard.ask(DashboardConfirm.MarkSeason(2))
        assertEquals(DashboardConfirm.MarkSeason(2), setup.ui.confirm)

        setup.dashboard.dismiss()
        assertNull(setup.ui.confirm)
    }

    @Test
    fun resetting_everything_clears_the_progress_and_says_so() = runTest {
        val setup = setup(progress = ScriptedProgress(progress("s1e1", seconds = 300), progress("s2e1", seconds = 60)))
        assertEquals(2, setup.catalog.progress.size)

        setup.dashboard.ask(DashboardConfirm.ResetAll)
        setup.dashboard.confirm()

        assertTrue(setup.catalog.progress.isEmpty())
        assertEquals(S.resetAllDone, setup.lastToast?.message)
        assertEquals(ToastKind.Ok, setup.lastToast?.kind)
        assertNull(setup.ui.confirm)
        assertFalse(setup.ui.busy)
    }

    /** The season in the question, not whichever one the screen shows by then. */
    @Test
    fun marking_a_season_marks_the_season_asked_about() = runTest {
        val setup = setup()

        setup.dashboard.ask(DashboardConfirm.MarkSeason(1))
        setup.dashboard.pickSeason(2)
        setup.dashboard.confirm()

        assertEquals(setOf("s1e1", "s1e2"), setup.catalog.progress.keys)
        assertTrue(setup.catalog.progress.values.all { it.isWatched })
        assertEquals(S.markSeasonDone, setup.lastToast?.message)
    }

    @Test
    fun resetting_a_season_leaves_the_others() = runTest {
        val setup = setup(progress = ScriptedProgress(progress("s1e1", seconds = 300), progress("s2e1", seconds = 60)))

        setup.dashboard.ask(DashboardConfirm.ResetSeason(1))
        setup.dashboard.confirm()

        assertEquals(setOf("s2e1"), setup.catalog.progress.keys)
        assertEquals(S.resetSeasonDone, setup.lastToast?.message)
    }

    @Test
    fun a_failed_change_says_so_and_frees_the_controls() = runTest {
        val setup = setup(progress = ScriptedProgress(progress("s1e1", seconds = 300)).apply { fails = true })

        setup.dashboard.ask(DashboardConfirm.ResetAll)
        setup.dashboard.confirm()

        assertEquals(S.actionFailed, setup.lastToast?.message)
        assertEquals(ToastKind.Error, setup.lastToast?.kind)
        assertFalse(setup.ui.busy)
        assertEquals(1, setup.catalog.progress.size, "nothing changed on screen")
    }

    @Test
    fun one_change_at_a_time() = runTest {
        val gate = CompletableDeferred<Unit>()
        val setup = setup(progress = ScriptedProgress().apply { this.gate = gate })

        setup.dashboard.ask(DashboardConfirm.ResetAll)
        setup.dashboard.confirm()
        assertTrue(setup.ui.busy)

        setup.dashboard.ask(DashboardConfirm.ResetAll)
        setup.dashboard.confirm()
        assertEquals(DashboardConfirm.ResetAll, setup.ui.confirm, "the second question stays open")

        gate.complete(Unit)

        assertEquals(1, setup.progress.changes)
        assertEquals(1, setup.toasts.items.size)
        assertFalse(setup.ui.busy)
    }

    @Test
    fun with_nobody_watching_nothing_changes() = runTest {
        val setup = setup(accounts = ScriptedAccountRepository())

        setup.dashboard.ask(DashboardConfirm.ResetAll)
        setup.dashboard.confirm()

        assertEquals(0, setup.progress.changes)
        assertTrue(setup.toasts.items.isEmpty())
        assertEquals(DashboardConfirm.ResetAll, setup.ui.confirm)
    }

    // ------------------------------------------------------------- rebuild

    @Test
    fun a_rebuild_says_what_it_did() = runTest {
        val setup = setup()

        setup.dashboard.refreshCatalog()

        assertEquals(S.refreshed(episodes.size, 0, 0, null, 0), setup.lastToast?.message)
        assertEquals(ToastKind.Ok, setup.lastToast?.kind)
        assertFalse(setup.catalog.refreshing)
    }

    @Test
    fun a_rebuild_says_what_cavea_plus_listed() = runTest {
        val setup = setup()
        setup.catalogRepository.caveaListed = 990
        setup.catalogRepository.caveaOnly = 58

        setup.dashboard.refreshCatalog()

        assertEquals(S.refreshed(episodes.size, 0, 0, 990, 58), setup.lastToast?.message)
    }

    /** Formula down and Cavea Plus up is still a rebuild: the toast says which part is old. */
    @Test
    fun a_rebuild_says_when_formula_s_list_was_not_updated() = runTest {
        val setup = setup()
        setup.catalogRepository.formulaWritten = null
        setup.catalogRepository.caveaListed = 990
        setup.catalogRepository.caveaOnly = 58

        setup.dashboard.refreshCatalog()

        assertEquals(S.refreshed(episodes.size, null, 0, 990, 58), setup.lastToast?.message)
        assertEquals(ToastKind.Ok, setup.lastToast?.kind)
    }

    @Test
    fun a_failed_rebuild_says_why() = runTest {
        val setup = setup()
        setup.catalogRepository.refreshFails = true

        setup.dashboard.refreshCatalog()

        assertEquals(ErrorMessages.catalogRefresh(IllegalStateException("formula down")), setup.lastToast?.message)
        assertEquals(ToastKind.Error, setup.lastToast?.kind)
    }

    // ------------------------------------------------------------- account

    @Test
    fun signing_out_signs_out_without_a_message() = runTest {
        val accounts = ScriptedAccountRepository(initial = account)
        val setup = setup(accounts = accounts)

        setup.dashboard.signOut()

        assertEquals(1, accounts.signOuts)
        assertTrue(setup.toasts.items.isEmpty())
    }

    /** Before, a failed sign-out from the menu went unreported. */
    @Test
    fun a_failed_sign_out_says_so() = runTest {
        val setup = setup(accounts = ScriptedAccountRepository(initial = account, signOutFails = true))

        setup.dashboard.signOut()

        assertEquals(S.actionFailed, setup.lastToast?.message)
        assertEquals(ToastKind.Error, setup.lastToast?.kind)
    }

    @Test
    fun a_failed_switch_to_guest_says_so() = runTest {
        val setup = setup(accounts = ScriptedAccountRepository(initial = account, signOutFails = true))

        setup.dashboard.switchToGuest()

        assertEquals(S.actionFailed, setup.lastToast?.message)
        assertEquals(ToastKind.Error, setup.lastToast?.kind)
    }
}

/**
 * Progress storage whose changes a test can fail, count and hold. Reads and
 * single saves pass straight through.
 */
private class ScriptedProgress(
    vararg initial: WatchProgress,
    private val inner: FakeProgressRepository = FakeProgressRepository(initial.associateBy { it.episodeId }),
) : ProgressRepository by inner {
    var fails = false
    var gate: CompletableDeferred<Unit>? = null
    var changes = 0
        private set

    private suspend fun change() {
        changes++
        gate?.await()
        if (fails) throw IllegalStateException("offline")
    }

    override suspend fun markWatched(uid: String, episodes: List<Episode>, nowMillis: Double) {
        change()
        inner.markWatched(uid, episodes, nowMillis)
    }

    override suspend fun deleteMany(uid: String, episodeIds: List<String>) {
        change()
        inner.deleteMany(uid, episodeIds)
    }

    override suspend fun deleteAll(uid: String) {
        change()
        inner.deleteAll(uid)
    }
}
