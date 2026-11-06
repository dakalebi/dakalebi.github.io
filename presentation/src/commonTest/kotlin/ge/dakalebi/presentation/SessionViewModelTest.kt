package ge.dakalebi.presentation

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.model.Viewer
import ge.dakalebi.testing.FakeProgressRepository
import ge.dakalebi.testing.progress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who the app thinks is watching, and what follows from it.
 *
 * The rules worth pinning down: an account always wins over the guest choice,
 * signing in ends guest mode, switching to guest never shows the sign-in screen
 * in between, and a guest is never asked about the account backend. And the
 * work every shell used to repeat: settings sync follows the account, the
 * catalog loads for each viewer, and it is dropped when nobody is using the app.
 *
 * The fakes are in `SessionFakes.kt`, shared with the screen ViewModels' tests.
 */
class SessionViewModelTest {

    private val account = Account(uid = "uid-1", email = "a@example.invalid", emailVerified = true)
    private val other = Account(uid = "uid-2", email = "b@example.invalid", emailVerified = true)

    private fun session(
        accounts: ScriptedAccountRepository = ScriptedAccountRepository(),
        admins: RecordingAdminRepository = RecordingAdminRepository(),
        guestMode: MemoryGuestMode = MemoryGuestMode(),
        settings: RecordingSettingsRepository = RecordingSettingsRepository(),
        catalog: CatalogStore = catalogStore(),
    ) = sessionViewModel(accounts, admins, guestMode, settings, catalog)

    private val SessionViewModel.now: SessionUiState get() = state.value

    @Test
    fun nobody_signed_in_and_no_guest_choice_means_the_sign_in_screen() {
        val session = session()

        assertNull(session.now.viewer)
        assertNull(session.now.viewerId)
        assertFalse(session.now.isGuest)
    }

    @Test
    fun continuing_as_guest_makes_a_guest_and_remembers_it() {
        val guestMode = MemoryGuestMode()
        val session = session(guestMode = guestMode)

        session.continueAsGuest()

        assertEquals(Viewer.Guest, session.now.viewer)
        assertEquals(Viewer.Guest.id, session.now.viewerId)
        assertTrue(session.now.isGuest)
        assertNull(session.now.uid, "a guest has no account uid to sync settings under")
        assertTrue(guestMode.value)
    }

    @Test
    fun a_remembered_guest_choice_survives_a_reload() {
        val session = session(guestMode = MemoryGuestMode(value = true))

        assertEquals(Viewer.Guest, session.now.viewer)
    }

    @Test
    fun an_account_wins_over_the_guest_choice() {
        val accounts = ScriptedAccountRepository(initial = account)
        val session = session(accounts = accounts, guestMode = MemoryGuestMode(value = true))

        assertEquals(Viewer.Member(account), session.now.viewer)
        assertEquals("uid-1", session.now.viewerId)
        assertFalse(session.now.isGuest)
    }

    @Test
    fun signing_in_ends_guest_mode_so_signing_out_returns_to_the_sign_in_screen() {
        val accounts = ScriptedAccountRepository()
        val guestMode = MemoryGuestMode()
        val session = session(accounts = accounts, guestMode = guestMode)
        session.continueAsGuest()

        accounts.emit(account)
        assertEquals(Viewer.Member(account), session.now.viewer)
        assertFalse(guestMode.value)

        accounts.emit(null)
        assertNull(session.now.viewer)
    }

    @Test
    fun leaving_guest_mode_shows_the_sign_in_screen_and_forgets_the_choice() {
        val guestMode = MemoryGuestMode()
        val session = session(guestMode = guestMode)
        session.continueAsGuest()

        session.leaveGuestMode()

        assertNull(session.now.viewer)
        assertFalse(guestMode.value)
    }

    @Test
    fun switching_to_guest_signs_out_without_passing_through_nobody() = runTest {
        val accounts = ScriptedAccountRepository(initial = account)
        val guestMode = MemoryGuestMode()
        val session = session(accounts = accounts, guestMode = guestMode)
        val seen = mutableListOf<Viewer?>()
        accounts.onSignOut = { seen += session.now.viewer }

        session.switchToGuest()

        assertEquals(listOf<Viewer?>(Viewer.Member(account)), seen, "still the account while signing out")
        assertEquals(Viewer.Guest, session.now.viewer)
        assertEquals(1, accounts.signOuts)
        assertTrue(guestMode.value)
    }

    @Test
    fun a_failed_switch_to_guest_keeps_the_account_and_drops_the_choice() = runTest {
        val accounts = ScriptedAccountRepository(initial = account, signOutFails = true)
        val guestMode = MemoryGuestMode()
        val session = session(accounts = accounts, guestMode = guestMode)

        assertFailsWith<IllegalStateException> { session.switchToGuest() }

        assertEquals(Viewer.Member(account), session.now.viewer)
        assertFalse(guestMode.value)
    }

    @Test
    fun a_guest_is_never_an_admin_and_the_roster_is_not_asked() = runTest {
        val admins = RecordingAdminRepository()
        val session = session(admins = admins)
        session.continueAsGuest()

        session.refreshAdminRights()

        assertFalse(session.now.isAdmin)
        assertTrue(admins.asked.isEmpty())
    }

    @Test
    fun an_account_on_the_roster_is_an_admin() = runTest {
        val admins = RecordingAdminRepository()
        val session = session(accounts = ScriptedAccountRepository(initial = account), admins = admins)

        session.refreshAdminRights()

        assertTrue(session.now.isAdmin)
        assertEquals(listOf("uid-1"), admins.asked)
    }

    // ------------------------------------------------- what follows the session

    @Test
    fun settings_sync_follows_the_account() {
        val accounts = ScriptedAccountRepository()
        val settings = RecordingSettingsRepository()
        session(accounts = accounts, settings = settings)

        accounts.emit(account)
        accounts.emit(other)
        accounts.emit(null)

        assertEquals(listOf(null, "uid-1", "uid-2", null), settings.attached)
    }

    /** A guest has no settings document, so their settings stay on the device. */
    @Test
    fun a_guest_has_no_account_to_sync() {
        val settings = RecordingSettingsRepository()
        val session = session(settings = settings)

        session.continueAsGuest()

        assertEquals(listOf<String?>(null), settings.attached, "stopped at boot, never started")
    }

    /** Keyed on the uid alone, so nothing else that changes restarts it. */
    @Test
    fun only_a_new_account_restarts_the_sync() = runTest {
        val settings = RecordingSettingsRepository()
        val session = session(accounts = ScriptedAccountRepository(initial = account), settings = settings)

        session.refreshAdminRights()

        assertTrue(session.now.isAdmin)
        assertEquals(listOf<String?>("uid-1"), settings.attached)
    }

    @Test
    fun signing_out_drops_the_catalog() = runTest {
        val accounts = ScriptedAccountRepository(initial = account)
        val catalog = catalogStore()
        session(accounts = accounts, catalog = catalog)
        catalog.ensureLoaded("uid-1")
        assertEquals(2, catalog.episodes.size)

        accounts.emit(null)

        assertTrue(catalog.episodes.isEmpty())
        assertTrue(catalog.loading, "so the next viewer loads afresh")
    }

    @Test
    fun leaving_guest_mode_drops_the_catalog() = runTest {
        val catalog = catalogStore()
        val session = session(guestMode = MemoryGuestMode(value = true), catalog = catalog)
        catalog.ensureLoaded(Viewer.Guest.id)

        session.leaveGuestMode()

        assertTrue(catalog.episodes.isEmpty())
    }

    /** Nobody is ever between the account and the guest, so there is nothing to drop. */
    @Test
    fun switching_to_guest_keeps_the_catalog() = runTest {
        val catalog = catalogStore()
        val session = session(accounts = ScriptedAccountRepository(initial = account), catalog = catalog)
        catalog.ensureLoaded("uid-1")

        session.switchToGuest()

        assertEquals(2, catalog.episodes.size)
    }

    @Test
    fun the_next_viewer_never_sees_the_previous_viewers_progress() = runTest {
        val progressRepository = FakeProgressRepository()
        val catalogRepository = StubCatalogRepository()
        val store = catalogStore(catalogRepository, progressRepository)
        progressRepository.stored["e1"] = progress("e1", seconds = 600)
        store.ensureLoaded("uid-1")
        assertEquals(setOf("e1"), store.progress.keys)

        // The guest's load fails, and the account's progress must not be what is left.
        catalogRepository.fails = true
        store.ensureLoaded(Viewer.Guest.id)

        assertTrue(store.progress.isEmpty())
        assertTrue(store.loadError != null)
    }

    // ------------------------------------------------------------ loading

    @Test
    fun a_viewer_arriving_loads_their_catalog() {
        val accounts = ScriptedAccountRepository()
        val catalog = catalogStore()
        session(accounts = accounts, catalog = catalog)
        assertTrue(catalog.episodes.isEmpty(), "nobody is watching yet")

        accounts.emit(account)

        assertEquals(2, catalog.episodes.size)
        assertFalse(catalog.loading)
    }

    @Test
    fun a_guest_from_the_last_visit_loads_at_start() {
        val catalog = catalogStore()

        session(guestMode = MemoryGuestMode(value = true), catalog = catalog)

        assertEquals(2, catalog.episodes.size)
    }

    @Test
    fun the_same_viewer_reported_again_does_not_load_again() {
        val accounts = ScriptedAccountRepository(initial = account)
        val repository = StubCatalogRepository()
        session(accounts = accounts, catalog = catalogStore(repository))

        accounts.emit(account.copy(emailVerified = false))

        assertEquals(1, repository.loads)
    }

    /** The account's load must not land after the reset that signing out made. */
    @Test
    fun signing_out_stops_a_load_still_running() {
        val gate = CompletableDeferred<Unit>()
        val accounts = ScriptedAccountRepository(initial = account)
        val catalog = catalogStore(StubCatalogRepository().apply { loadGate = gate })
        session(accounts = accounts, catalog = catalog)
        assertTrue(catalog.loading)

        accounts.emit(null)
        gate.complete(Unit)

        assertTrue(catalog.episodes.isEmpty())
        assertTrue(catalog.loading, "so the next viewer loads afresh")
        assertNull(catalog.loadError, "a stopped load is not a failed one")
    }

    @Test
    fun a_failed_load_is_retried_for_the_same_viewer() {
        val repository = StubCatalogRepository().apply { fails = true }
        val catalog = catalogStore(repository)
        val session = session(accounts = ScriptedAccountRepository(initial = account), catalog = catalog)
        assertNotNull(catalog.loadError)

        repository.fails = false
        session.retryCatalogLoad()

        assertNull(catalog.loadError)
        assertEquals(2, catalog.episodes.size)
    }

    @Test
    fun retrying_with_nobody_watching_loads_nothing() {
        val repository = StubCatalogRepository()
        val session = session(catalog = catalogStore(repository))

        session.retryCatalogLoad()

        assertEquals(0, repository.loads)
    }
}
