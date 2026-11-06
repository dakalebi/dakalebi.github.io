package ge.dakalebi.presentation

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.model.Catalog
import ge.dakalebi.domain.model.CatalogMeta
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.repository.AccountRepository
import ge.dakalebi.domain.repository.AdminRepository
import ge.dakalebi.domain.repository.CatalogRepository
import ge.dakalebi.domain.repository.GuestModeRepository
import ge.dakalebi.domain.repository.ProgressRepository
import ge.dakalebi.domain.repository.SettingsRepository
import ge.dakalebi.domain.usecase.CarryOverCaveaProgress
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
import ge.dakalebi.testing.FakeClock
import ge.dakalebi.testing.FakeProgressRepository
import ge.dakalebi.testing.episode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * The session and the catalog with fakes behind them, for the tests of the
 * ViewModels that sit on top of the two.
 */

/** A started [SessionViewModel] over fakes. */
fun sessionViewModel(
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

fun catalogStore(
    catalog: CatalogRepository = StubCatalogRepository(),
    progress: ProgressRepository = FakeProgressRepository(),
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
        carryOverCaveaProgress = CarryOverCaveaProgress(progress),
    )
}

/** An auth provider the test drives by hand. */
class ScriptedAccountRepository(
    initial: Account? = null,
    private val signOutFails: Boolean = false,
) : AccountRepository {
    override val account = MutableStateFlow(initial)
    var signOuts = 0
        private set

    /** Runs as the sign-out starts, before the provider reports it. */
    var onSignOut: () -> Unit = {}

    /** Every sign-in, sign-up and password reset asked for, in order. */
    val requests = mutableListOf<String>()

    /** What sign-in, sign-up and password reset fail with, when set. */
    var authFailure: Throwable? = null

    /** Holds sign-in, sign-up and password reset until completed, when set. */
    var authGate: CompletableDeferred<Unit>? = null

    fun emit(account: Account?) {
        this.account.value = account
    }

    override suspend fun signIn(email: String, password: String) = request("signIn $email $password")
    override suspend fun signUp(email: String, password: String) = request("signUp $email $password")
    override suspend fun sendPasswordReset(email: String) = request("reset $email")

    private suspend fun request(what: String) {
        requests += what
        authGate?.await()
        authFailure?.let { throw it }
    }

    override suspend fun signOut() {
        onSignOut()
        if (signOutFails) throw IllegalStateException("network")
        signOuts++
        emit(null)
    }
}

class RecordingAdminRepository : AdminRepository {
    val asked = mutableListOf<String>()

    override suspend fun isAdmin(uid: String): Boolean {
        asked += uid
        return true
    }
}

class MemoryGuestMode(var value: Boolean = false) : GuestModeRepository {
    override fun isGuest() = value

    override fun setGuest(value: Boolean) {
        this.value = value
    }
}

/** Which account the settings sync was attached to, in order. A stop is null. */
class RecordingSettingsRepository : SettingsRepository {
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

/** Two episodes of season 1, with loads a test can fail, count and hold. */
class StubCatalogRepository(
    private val episodes: List<Episode> = listOf(episode(1, 1, id = "e1"), episode(1, 2, id = "e2")),
) : CatalogRepository {
    var fails = false
    var refreshFails = false

    /** What the rebuild reports of Cavea Plus's list. */
    var caveaListed: Int? = null
    var caveaOnly = 0
    var loads = 0
        private set

    /** Holds every load until completed, when set. */
    var loadGate: CompletableDeferred<Unit>? = null

    override suspend fun load(): Catalog {
        loads++
        loadGate?.await()
        if (fails) throw IllegalStateException("offline")
        return Catalog(episodes, CatalogMeta(1.0, 1, episodes.size))
    }

    override suspend fun listEpisodes() = episodes
    override suspend fun getMeta() = CatalogMeta(1.0, 1, episodes.size)
    override suspend fun refresh(nowMillis: Double, onProgress: (Int, Int) -> Unit): RefreshResult {
        if (refreshFails) throw IllegalStateException("formula down")
        return RefreshResult(1, episodes.size, 0, 0, episodes, caveaListed, caveaOnly)
    }

    override suspend fun resolveVideo(episode: Episode) = episode
    override suspend fun recordDuration(episodeId: String, durationSeconds: Int) = Unit
}
