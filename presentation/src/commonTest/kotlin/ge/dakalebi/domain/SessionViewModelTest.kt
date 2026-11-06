package ge.dakalebi.domain

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.model.Catalog
import ge.dakalebi.domain.model.CatalogMeta
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.model.Viewer
import ge.dakalebi.domain.repository.AccountRepository
import ge.dakalebi.domain.repository.AdminRepository
import ge.dakalebi.domain.repository.CatalogRepository
import ge.dakalebi.domain.repository.GuestModeRepository
import ge.dakalebi.domain.repository.SettingsRepository
import ge.dakalebi.domain.usecase.CheckAdminRights
import ge.dakalebi.domain.usecase.ClearEpisodeProgress
import ge.dakalebi.domain.usecase.LoadCatalog
import ge.dakalebi.domain.usecase.LoadProgress
import ge.dakalebi.domain.usecase.MarkSeasonWatched
import ge.dakalebi.domain.usecase.ObserveAccount
import ge.dakalebi.domain.usecase.RecordEpisodeDuration
import ge.dakalebi.domain.usecase.RefreshCatalog
import ge.dakalebi.domain.usecase.ResetAllProgress
import ge.dakalebi.domain.usecase.ResetSeasonProgress
import ge.dakalebi.domain.usecase.SaveProgress
import ge.dakalebi.domain.usecase.SendPasswordReset
import ge.dakalebi.domain.usecase.SignInWithEmail
import ge.dakalebi.domain.usecase.SignOut
import ge.dakalebi.domain.usecase.SignUpWithEmail
import ge.dakalebi.domain.usecase.SyncUserSettings
import ge.dakalebi.presentation.CatalogStore
import ge.dakalebi.presentation.SessionUiState
import ge.dakalebi.presentation.SessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who the app thinks is watching, and what follows from it.
 *
 * The rules worth pinning down: an account always wins over the guest choice,
 * signing in ends guest mode, switching to guest never shows the sign-in screen
 * in between, and a guest is never asked about the account backend. And the
 * work every shell used to repeat: settings sync follows the account, and the
 * catalog is dropped when nobody is using the app.
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
    ) = SessionViewModel(
        observeAccount = ObserveAccount(accounts),
        checkAdminRights = CheckAdminRights(admins),
        signInWithEmail = SignInWithEmail(accounts),
        signUpWithEmail = SignUpWithEmail(accounts),
        sendPasswordResetUseCase = SendPasswordReset(accounts),
        signOutUseCase = SignOut(accounts),
        guestMode = guestMode,
        syncUserSettings = SyncUserSettings(settings),
        catalog = catalog,
        // Everything follows the account unconfined, so it needs nothing from
        // the scope but a place to live.
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).also { it.start() }

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

    private fun catalogStore(
        catalog: CatalogRepository = StubCatalogRepository(),
        progress: FakeProgressRepository = FakeProgressRepository(),
    ): CatalogStore {
        val clock = FakeClock()
        return CatalogStore(
            loadCatalog = LoadCatalog(catalog),
            loadProgress = LoadProgress(progress),
            refreshCatalogUseCase = RefreshCatalog(catalog, clock),
            saveProgressUseCase = SaveProgress(progress, clock),
            clearEpisodeProgress = ClearEpisodeProgress(progress),
            markSeasonWatchedUseCase = MarkSeasonWatched(progress, clock),
            resetSeasonProgressUseCase = ResetSeasonProgress(progress),
            resetAllProgressUseCase = ResetAllProgress(progress),
            recordEpisodeDuration = RecordEpisodeDuration(catalog),
        )
    }
}

/** An auth provider the test drives by hand. */
private class ScriptedAccountRepository(
    initial: Account? = null,
    private val signOutFails: Boolean = false,
) : AccountRepository {
    override val account = MutableStateFlow(initial)
    var signOuts = 0
        private set

    /** Runs as the sign-out starts, before the provider reports it. */
    var onSignOut: () -> Unit = {}

    fun emit(account: Account?) {
        this.account.value = account
    }

    override suspend fun signIn(email: String, password: String) = Unit
    override suspend fun signUp(email: String, password: String) = Unit
    override suspend fun sendPasswordReset(email: String) = Unit

    override suspend fun signOut() {
        onSignOut()
        if (signOutFails) throw IllegalStateException("network")
        signOuts++
        emit(null)
    }
}

private class RecordingAdminRepository : AdminRepository {
    val asked = mutableListOf<String>()

    override suspend fun isAdmin(uid: String): Boolean {
        asked += uid
        return true
    }
}

private class MemoryGuestMode(var value: Boolean = false) : GuestModeRepository {
    override fun isGuest() = value

    override fun setGuest(value: Boolean) {
        this.value = value
    }
}

/** Which account the settings sync was attached to, in order. A stop is null. */
private class RecordingSettingsRepository : SettingsRepository {
    val attached = mutableListOf<String?>()

    override val settings = MutableStateFlow(UserSettings())

    override fun start(uid: String) {
        attached += uid
    }

    override fun stop() {
        attached += null
    }

    override suspend fun <T : Any> set(setting: Setting<T>, value: T) = true
}

private class StubCatalogRepository : CatalogRepository {
    var fails = false
    private val episodes = listOf(episode(1, 1, id = "e1"), episode(1, 2, id = "e2"))

    override suspend fun load(): Catalog {
        if (fails) throw IllegalStateException("offline")
        return Catalog(episodes, CatalogMeta(1.0, 1, episodes.size))
    }

    override suspend fun listEpisodes() = episodes
    override suspend fun getMeta() = CatalogMeta(1.0, 1, episodes.size)
    override suspend fun refresh(nowMillis: Double, onProgress: (Int, Int) -> Unit) =
        RefreshResult(1, episodes.size, 0, 0, episodes)

    override suspend fun resolveVideo(episode: Episode) = episode
    override suspend fun recordDuration(episodeId: String, durationSeconds: Int) = Unit
}
