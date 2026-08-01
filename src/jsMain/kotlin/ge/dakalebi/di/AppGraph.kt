package ge.dakalebi.di

import ge.dakalebi.core.Log
import ge.dakalebi.data.SystemClock
import ge.dakalebi.data.firebase.FirebaseAccountRepository
import ge.dakalebi.data.firebase.FirestoreCatalogRepository
import ge.dakalebi.data.firebase.FirestoreProgressRepository
import ge.dakalebi.data.firebase.FirestoreSettingsRepository
import ge.dakalebi.data.formula.FormulaApi
import ge.dakalebi.data.local.BrowserPreferencesRepository
import ge.dakalebi.domain.Clock
import ge.dakalebi.domain.repository.AccountRepository
import ge.dakalebi.domain.repository.CatalogRepository
import ge.dakalebi.domain.repository.PreferencesRepository
import ge.dakalebi.domain.repository.ProgressRepository
import ge.dakalebi.domain.repository.SettingsRepository
import ge.dakalebi.domain.usecase.CanRefreshCatalog
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
import ge.dakalebi.domain.usecase.SeedLanguage
import ge.dakalebi.domain.usecase.SendPasswordReset
import ge.dakalebi.domain.usecase.SignInWithEmail
import ge.dakalebi.domain.usecase.SignInWithGoogle
import ge.dakalebi.domain.usecase.SignOut
import ge.dakalebi.domain.usecase.SignUpWithEmail
import ge.dakalebi.presentation.CatalogStore
import ge.dakalebi.presentation.PreferencesStore
import ge.dakalebi.presentation.Router
import ge.dakalebi.presentation.SessionStore
import ge.dakalebi.presentation.SettingsStore
import ge.dakalebi.presentation.ToastStore

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
    catalogRepository: CatalogRepository = FirestoreCatalogRepository(FormulaApi()),
    progressRepository: ProgressRepository = FirestoreProgressRepository(),
    settingsRepository: SettingsRepository = FirestoreSettingsRepository(clock),
    accountRepository: AccountRepository = FirebaseAccountRepository(),
    preferencesRepository: PreferencesRepository = BrowserPreferencesRepository(),
) {
    val router = Router()
    val toasts = ToastStore()

    val preferences = PreferencesStore(preferencesRepository)

    val catalog = CatalogStore(
        loadCatalog = LoadCatalog(catalogRepository) {
            // Metadata is decoration — the "last refreshed" line. Losing it must
            // not fail the load, but it should still say so: a permission error
            // here means the rules are wrong for `meta/catalog` too.
            Log.w("catalog", "metadata unavailable", it)
        },
        loadProgress = LoadProgress(progressRepository),
        refreshCatalogUseCase = RefreshCatalog(catalogRepository, clock),
        saveProgressUseCase = SaveProgress(progressRepository, clock),
        clearEpisodeProgress = ClearEpisodeProgress(progressRepository),
        markSeasonWatchedUseCase = MarkSeasonWatched(progressRepository, clock),
        resetSeasonProgressUseCase = ResetSeasonProgress(progressRepository),
        resetAllProgressUseCase = ResetAllProgress(progressRepository),
        recordEpisodeDuration = RecordEpisodeDuration(catalogRepository),
    )

    val session = SessionStore(
        observeAccount = ObserveAccount(accountRepository),
        canRefreshCatalog = CanRefreshCatalog(accountRepository),
        signInWithEmail = SignInWithEmail(accountRepository),
        signUpWithEmail = SignUpWithEmail(accountRepository),
        signInWithGoogleUseCase = SignInWithGoogle(accountRepository),
        sendPasswordResetUseCase = SendPasswordReset(accountRepository),
        signOutUseCase = SignOut(accountRepository),
    )

    val settings = SettingsStore(
        loadUserSettings = LoadUserSettings(settingsRepository),
        observeUserSettings = ObserveUserSettings(settingsRepository),
        changeLanguage = ChangeLanguage(settingsRepository),
        seedLanguage = SeedLanguage(settingsRepository),
        prefs = preferencesRepository,
    )

    /** Used by the watch screen, which resolves a URL without going via a store. */
    val resolveEpisodeVideo = ResolveEpisodeVideo(catalogRepository)
}
