package ge.dakalebi.domain

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.model.Catalog
import ge.dakalebi.domain.model.CatalogMeta
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.model.Viewer
import ge.dakalebi.domain.repository.AccountRepository
import ge.dakalebi.domain.repository.AdminRepository
import ge.dakalebi.domain.repository.CatalogRepository
import ge.dakalebi.domain.repository.GuestModeRepository
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
import ge.dakalebi.presentation.CatalogStore
import ge.dakalebi.presentation.SessionStore
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
 * in between, and a guest is never asked about the account backend.
 */
class GuestSessionTest {

    private val account = Account(uid = "uid-1", email = "a@example.invalid", emailVerified = true)

    private fun session(
        accounts: ScriptedAccountRepository = ScriptedAccountRepository(),
        admins: RecordingAdminRepository = RecordingAdminRepository(),
        guestMode: MemoryGuestMode = MemoryGuestMode(),
    ) = SessionStore(
        observeAccount = ObserveAccount(accounts),
        checkAdminRights = CheckAdminRights(admins),
        signInWithEmail = SignInWithEmail(accounts),
        signUpWithEmail = SignUpWithEmail(accounts),
        sendPasswordResetUseCase = SendPasswordReset(accounts),
        signOutUseCase = SignOut(accounts),
        guestMode = guestMode,
        // The store follows the account unconfined, so it needs nothing from
        // the scope but a place to live.
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).also { it.start() }

    @Test
    fun nobody_signed_in_and_no_guest_choice_means_the_sign_in_screen() {
        val session = session()

        assertNull(session.viewer)
        assertNull(session.viewerId)
        assertFalse(session.isGuest)
    }

    @Test
    fun continuing_as_guest_makes_a_guest_and_remembers_it() {
        val guestMode = MemoryGuestMode()
        val session = session(guestMode = guestMode)

        session.continueAsGuest()

        assertEquals(Viewer.Guest, session.viewer)
        assertEquals(Viewer.Guest.id, session.viewerId)
        assertTrue(session.isGuest)
        assertNull(session.uid, "a guest has no account uid to sync settings under")
        assertTrue(guestMode.value)
    }

    @Test
    fun a_remembered_guest_choice_survives_a_reload() {
        val session = session(guestMode = MemoryGuestMode(value = true))

        assertEquals(Viewer.Guest, session.viewer)
    }

    @Test
    fun an_account_wins_over_the_guest_choice() {
        val accounts = ScriptedAccountRepository(initial = account)
        val session = session(accounts = accounts, guestMode = MemoryGuestMode(value = true))

        assertEquals(Viewer.Member(account), session.viewer)
        assertEquals("uid-1", session.viewerId)
        assertFalse(session.isGuest)
    }

    @Test
    fun signing_in_ends_guest_mode_so_signing_out_returns_to_the_sign_in_screen() {
        val accounts = ScriptedAccountRepository()
        val guestMode = MemoryGuestMode()
        val session = session(accounts = accounts, guestMode = guestMode)
        session.continueAsGuest()

        accounts.emit(account)
        assertEquals(Viewer.Member(account), session.viewer)
        assertFalse(guestMode.value)

        accounts.emit(null)
        assertNull(session.viewer)
    }

    @Test
    fun leaving_guest_mode_shows_the_sign_in_screen_and_forgets_the_choice() {
        val guestMode = MemoryGuestMode()
        val session = session(guestMode = guestMode)
        session.continueAsGuest()

        session.leaveGuestMode()

        assertNull(session.viewer)
        assertFalse(guestMode.value)
    }

    @Test
    fun switching_to_guest_signs_out_without_passing_through_nobody() = runTest {
        val accounts = ScriptedAccountRepository(initial = account)
        val guestMode = MemoryGuestMode()
        val session = session(accounts = accounts, guestMode = guestMode)
        val seen = mutableListOf<Viewer?>()
        accounts.onSignOut = { seen += session.viewer }

        session.switchToGuest()

        assertEquals(listOf<Viewer?>(Viewer.Member(account)), seen, "still the account while signing out")
        assertEquals(Viewer.Guest, session.viewer)
        assertEquals(1, accounts.signOuts)
        assertTrue(guestMode.value)
    }

    @Test
    fun a_failed_switch_to_guest_keeps_the_account_and_drops_the_choice() = runTest {
        val accounts = ScriptedAccountRepository(initial = account, signOutFails = true)
        val guestMode = MemoryGuestMode()
        val session = session(accounts = accounts, guestMode = guestMode)

        assertFailsWith<IllegalStateException> { session.switchToGuest() }

        assertEquals(Viewer.Member(account), session.viewer)
        assertFalse(guestMode.value)
    }

    @Test
    fun a_guest_is_never_an_admin_and_the_roster_is_not_asked() = runTest {
        val admins = RecordingAdminRepository()
        val session = session(admins = admins)
        session.continueAsGuest()

        session.refreshAdminRights()

        assertFalse(session.isAdmin)
        assertTrue(admins.asked.isEmpty())
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

    private fun catalogStore(catalog: CatalogRepository, progress: FakeProgressRepository): CatalogStore {
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
