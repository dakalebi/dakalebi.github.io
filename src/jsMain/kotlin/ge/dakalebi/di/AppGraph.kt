package ge.dakalebi.di

import ge.dakalebi.data.SystemClock
import ge.dakalebi.data.firebase.FirebaseAccountRepository
import ge.dakalebi.data.firebase.FirestoreAdminRepository
import ge.dakalebi.data.firebase.FirestoreCatalogRepository
import ge.dakalebi.data.firebase.FirestoreProgressRepository
import ge.dakalebi.data.firebase.FirestoreSettingsRepository
import ge.dakalebi.data.formula.FormulaApi
import ge.dakalebi.data.local.BrowserKeyValueStore
import ge.dakalebi.data.local.BrowserPreferencesRepository
import ge.dakalebi.data.local.LocalCatalogCache
import ge.dakalebi.domain.Clock
import ge.dakalebi.domain.repository.AccountRepository
import ge.dakalebi.domain.repository.AdminRepository
import ge.dakalebi.domain.repository.CatalogCache
import ge.dakalebi.domain.repository.CatalogRepository
import ge.dakalebi.domain.repository.PreferencesRepository
import ge.dakalebi.domain.repository.ProgressRepository
import ge.dakalebi.domain.repository.SettingsRepository
import ge.dakalebi.domain.usecase.ChangeAutoplay
import ge.dakalebi.domain.usecase.CheckAdminRights
import ge.dakalebi.domain.usecase.ChangeLanguage
import ge.dakalebi.domain.usecase.ClearEpisodeProgress
import ge.dakalebi.domain.usecase.LoadCatalog
import ge.dakalebi.domain.usecase.LoadProgress
import ge.dakalebi.domain.usecase.LoadUserSettings
import ge.dakalebi.domain.usecase.MarkSeasonWatched
import ge.dakalebi.domain.usecase.ObserveAccount
import ge.dakalebi.domain.usecase.ObserveUserSettings
import ge.dakalebi.domain.usecase.RecordEpisodeDuration
import ge.dakalebi.domain.usecase.RefreshCatalog
import ge.dakalebi.domain.usecase.ResetAllProgress
import ge.dakalebi.domain.usecase.ResetSeasonProgress
import ge.dakalebi.domain.usecase.ResolveEpisodeVideo
import ge.dakalebi.domain.usecase.SaveProgress
import ge.dakalebi.domain.usecase.SeedSettings
import ge.dakalebi.domain.usecase.SendPasswordReset
import ge.dakalebi.domain.usecase.SignInWithEmail
import ge.dakalebi.domain.usecase.SignOut
import ge.dakalebi.domain.usecase.SignUpWithEmail
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
    catalogCache: CatalogCache = LocalCatalogCache(BrowserKeyValueStore()),
    catalogRepository: CatalogRepository = FirestoreCatalogRepository(FormulaApi(), catalogCache),
    progressRepository: ProgressRepository = FirestoreProgressRepository(),
    settingsRepository: SettingsRepository = FirestoreSettingsRepository(clock),
    accountRepository: AccountRepository = FirebaseAccountRepository(),
    adminRepository: AdminRepository = FirestoreAdminRepository(),
    preferencesRepository: PreferencesRepository = BrowserPreferencesRepository(),
) {
    /** Typed as the implementation, because only the entry point calls `start()`. */
    val router = HashRouter()
    val toasts = ToastStore(scope)

    val preferences = PreferencesStore(preferencesRepository)

    val catalog = CatalogStore(
        loadCatalog = LoadCatalog(catalogRepository),
        loadProgress = LoadProgress(progressRepository),
        refreshCatalogUseCase = RefreshCatalog(catalogRepository, clock),
        saveProgressUseCase = SaveProgress(progressRepository, clock),
        clearEpisodeProgress = ClearEpisodeProgress(progressRepository),
        markSeasonWatchedUseCase = MarkSeasonWatched(progressRepository, clock),
        resetSeasonProgressUseCase = ResetSeasonProgress(progressRepository),
        resetAllProgressUseCase = ResetAllProgress(progressRepository),
        recordEpisodeDuration = RecordEpisodeDuration(catalogRepository),
        cache = catalogCache,
    )

    val session = SessionStore(
        observeAccount = ObserveAccount(accountRepository),
        checkAdminRights = CheckAdminRights(adminRepository),
        signInWithEmail = SignInWithEmail(accountRepository),
        signUpWithEmail = SignUpWithEmail(accountRepository),
        sendPasswordResetUseCase = SendPasswordReset(accountRepository),
        signOutUseCase = SignOut(accountRepository),
    )

    val settings = SettingsStore(
        loadUserSettings = LoadUserSettings(settingsRepository),
        observeUserSettings = ObserveUserSettings(settingsRepository),
        changeLanguage = ChangeLanguage(settingsRepository),
        changeAutoplay = ChangeAutoplay(settingsRepository),
        seedSettings = SeedSettings(settingsRepository),
        prefs = preferencesRepository,
    )

    /** Used by the watch screen, which resolves a URL without going via a store. */
    val resolveEpisodeVideo = ResolveEpisodeVideo(catalogRepository)
}
