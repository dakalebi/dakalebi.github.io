package ge.dakalebi.di

import ge.dakalebi.data.AccountSettingsSource
import ge.dakalebi.data.SyncedSettingsRepository
import ge.dakalebi.data.SystemClock
import ge.dakalebi.data.ViewerProgressRepository
import ge.dakalebi.data.firebase.FirebaseAccountRepository
import ge.dakalebi.data.firebase.FirestoreAdminRepository
import ge.dakalebi.data.firebase.FirestoreCatalogRepository
import ge.dakalebi.data.firebase.FirestoreProgressRepository
import ge.dakalebi.data.firebase.FirestoreSettingsSource
import ge.dakalebi.data.formula.FormulaApi
import ge.dakalebi.data.local.BrowserKeyValueStore
import ge.dakalebi.data.local.BrowserPreferencesRepository
import ge.dakalebi.data.local.LocalCatalogCache
import ge.dakalebi.data.local.LocalGuestMode
import ge.dakalebi.data.local.LocalProgressRepository
import ge.dakalebi.data.local.LocalSettingsCache
import ge.dakalebi.data.local.SettingsCache
import ge.dakalebi.domain.Clock
import ge.dakalebi.domain.repository.AccountRepository
import ge.dakalebi.domain.repository.AdminRepository
import ge.dakalebi.domain.repository.CatalogRepository
import ge.dakalebi.domain.repository.GuestModeRepository
import ge.dakalebi.domain.repository.PreferencesRepository
import ge.dakalebi.domain.repository.ProgressRepository
import ge.dakalebi.domain.usecase.ChangeUserSetting
import ge.dakalebi.domain.usecase.CheckAdminRights
import ge.dakalebi.domain.usecase.ClearEpisodeProgress
import ge.dakalebi.domain.usecase.LoadCatalog
import ge.dakalebi.domain.usecase.LoadProgress
import ge.dakalebi.domain.usecase.MarkSeasonWatched
import ge.dakalebi.domain.usecase.ObserveAccount
import ge.dakalebi.domain.usecase.ObserveUserSettings
import ge.dakalebi.domain.usecase.RecordEpisodeDuration
import ge.dakalebi.domain.usecase.RefreshCatalog
import ge.dakalebi.domain.usecase.ResetAllProgress
import ge.dakalebi.domain.usecase.ResetSeasonProgress
import ge.dakalebi.domain.usecase.ResolveEpisodeVideo
import ge.dakalebi.domain.usecase.SaveProgress
import ge.dakalebi.domain.usecase.SendPasswordReset
import ge.dakalebi.domain.usecase.SignInWithEmail
import ge.dakalebi.domain.usecase.SignOut
import ge.dakalebi.domain.usecase.SignUpWithEmail
import ge.dakalebi.domain.usecase.SyncUserSettings
import ge.dakalebi.presentation.CatalogStore
import ge.dakalebi.presentation.PreferencesStore
import ge.dakalebi.presentation.HashRouter
import ge.dakalebi.presentation.SessionStore
import ge.dakalebi.presentation.SettingsStore
import ge.dakalebi.presentation.ToastStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/**
 * The composition root: the one place that says which implementation is which.
 *
 * Everything above this file names interfaces only. Swapping Firestore for
 * something else, or any repository for a fake, is a change to this
 * constructor and nothing else — which is the whole reason for the layering.
 *
 * Wiring by hand rather than through a DI container. There are five
 * repositories and twenty use cases; a container would add a dependency and a
 * layer of indirection to save a page of unambiguous constructor calls.
 */
class AppGraph(
    clock: Clock = SystemClock,
    /** Lives as long as the graph, which is as long as the page. */
    scope: CoroutineScope = MainScope(),
    /** The catalog cache is here, in plain sight, so it can be switched off in one place. */
    catalogRepository: CatalogRepository = FirestoreCatalogRepository(FormulaApi(), LocalCatalogCache(BrowserKeyValueStore())),
    progressRepository: ProgressRepository = FirestoreProgressRepository(),
    /** The account's half of the settings sync. */
    accountSettings: AccountSettingsSource = FirestoreSettingsSource(clock),
    accountRepository: AccountRepository = FirebaseAccountRepository(),
    adminRepository: AdminRepository = FirestoreAdminRepository(),
    preferencesRepository: PreferencesRepository = BrowserPreferencesRepository(),
    /** This device's half of the settings sync. */
    settingsCache: SettingsCache = LocalSettingsCache(BrowserKeyValueStore()),
    /** A guest's progress: on this device, never uploaded. */
    guestProgressRepository: ProgressRepository = LocalProgressRepository(BrowserKeyValueStore()),
    guestModeRepository: GuestModeRepository = LocalGuestMode(BrowserKeyValueStore()),
) {
    /** Every progress read and write goes through here, which keeps a guest's off the account backend. */
    private val viewerProgress = ViewerProgressRepository(
        member = progressRepository,
        guest = guestProgressRepository,
    )

    /** Where this device's copy of the settings and the account's meet. Its writes run in [scope]. */
    private val settingsRepository = SyncedSettingsRepository(accountSettings, settingsCache, scope)

    /** Typed as the implementation, because only the entry point calls `start()`. */
    val router = HashRouter()
    val toasts = ToastStore(scope)

    val preferences = PreferencesStore(preferencesRepository, scope)

    val catalog = CatalogStore(
        loadCatalog = LoadCatalog(catalogRepository),
        loadProgress = LoadProgress(viewerProgress),
        refreshCatalogUseCase = RefreshCatalog(catalogRepository, clock),
        saveProgressUseCase = SaveProgress(viewerProgress, clock),
        clearEpisodeProgress = ClearEpisodeProgress(viewerProgress),
        markSeasonWatchedUseCase = MarkSeasonWatched(viewerProgress, clock),
        resetSeasonProgressUseCase = ResetSeasonProgress(viewerProgress),
        resetAllProgressUseCase = ResetAllProgress(viewerProgress),
        recordEpisodeDuration = RecordEpisodeDuration(catalogRepository),
    )

    val session = SessionStore(
        observeAccount = ObserveAccount(accountRepository),
        checkAdminRights = CheckAdminRights(adminRepository),
        signInWithEmail = SignInWithEmail(accountRepository),
        signUpWithEmail = SignUpWithEmail(accountRepository),
        sendPasswordResetUseCase = SendPasswordReset(accountRepository),
        signOutUseCase = SignOut(accountRepository),
        guestMode = guestModeRepository,
        scope = scope,
    )

    val settings = SettingsStore(
        observeUserSettings = ObserveUserSettings(settingsRepository),
        syncUserSettings = SyncUserSettings(settingsRepository),
        changeUserSetting = ChangeUserSetting(settingsRepository),
        scope = scope,
    )

    /** Used by the watch screen, which resolves a URL without going via a store. */
    val resolveEpisodeVideo = ResolveEpisodeVideo(catalogRepository)
}
