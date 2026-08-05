package ge.dakalebi.web.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ge.dakalebi.data.formula.FormulaApi
import ge.dakalebi.data.local.LocalCatalogCache
import ge.dakalebi.di.LocalCatalog
import ge.dakalebi.di.LocalPreferences
import ge.dakalebi.di.LocalResolveEpisodeVideo
import ge.dakalebi.di.LocalRouter
import ge.dakalebi.di.LocalSession
import ge.dakalebi.di.LocalSettings
import ge.dakalebi.di.LocalToasts
import ge.dakalebi.domain.Clock
import ge.dakalebi.domain.repository.AccountRepository
import ge.dakalebi.domain.repository.AdminRepository
import ge.dakalebi.domain.repository.CatalogCache
import ge.dakalebi.domain.repository.CatalogRepository
import ge.dakalebi.domain.repository.PreferencesRepository
import ge.dakalebi.domain.repository.ProgressRepository
import ge.dakalebi.domain.repository.SettingsRepository
import ge.dakalebi.domain.usecase.ChangeAutoplay
import ge.dakalebi.domain.usecase.ChangeLanguage
import ge.dakalebi.domain.usecase.CheckAdminRights
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
import ge.dakalebi.domain.usecase.SignInWithGoogle
import ge.dakalebi.domain.usecase.SignOut
import ge.dakalebi.domain.usecase.SignUpWithEmail
import ge.dakalebi.presentation.CatalogStore
import ge.dakalebi.presentation.PreferencesStore
import ge.dakalebi.presentation.SessionStore
import ge.dakalebi.presentation.SettingsStore
import ge.dakalebi.presentation.ToastStore
import ge.dakalebi.web.device.WasmClock
import ge.dakalebi.web.device.WasmHashRouter
import ge.dakalebi.web.device.WasmKeyValueStore
import ge.dakalebi.web.device.WasmPreferencesRepository
import ge.dakalebi.web.firebase.WasmAccountRepository
import ge.dakalebi.web.firebase.WasmAdminRepository
import ge.dakalebi.web.firebase.WasmCatalogRepository
import ge.dakalebi.web.firebase.WasmProgressRepository
import ge.dakalebi.web.firebase.WasmSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/**
 * The composition root: the one place that says which implementation is which.
 *
 * Everything above this file names interfaces only. The screens are `commonMain` Compose UI
 * that reach their dependencies through the shared `CompositionLocal`s, so this file is the
 * whole of what a second platform would have to replace — which is the point of building the
 * 2.0 UI this way rather than as another browser-only app.
 *
 * Wiring by hand rather than through a DI container. There are six repositories and twenty
 * use cases; a container would add a dependency and a layer of indirection to save a page of
 * unambiguous constructor calls.
 */
class WebGraph(
    clock: Clock = WasmClock,
    /** Lives as long as the graph, which is as long as the page. */
    scope: CoroutineScope = MainScope(),
    catalogCache: CatalogCache = LocalCatalogCache(WasmKeyValueStore()),
    catalogRepository: CatalogRepository = WasmCatalogRepository(FormulaApi(), catalogCache),
    progressRepository: ProgressRepository = WasmProgressRepository(),
    settingsRepository: SettingsRepository = WasmSettingsRepository(clock),
    accountRepository: AccountRepository = WasmAccountRepository(),
    adminRepository: AdminRepository = WasmAdminRepository(),
    preferencesRepository: PreferencesRepository = WasmPreferencesRepository(),
) {
    /** Typed as the implementation, because only the entry point calls `start()`. */
    val router = WasmHashRouter()
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
        signInWithGoogleUseCase = SignInWithGoogle(accountRepository),
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

/**
 * Puts the whole graph in scope. Wraps the root composable, once.
 *
 * Separate from the graph itself for the same reason the root app separates them: the locals
 * in `:shared` name only interfaces and stores, so this is the only composable that knows a
 * concrete implementation exists.
 */
@Composable
fun WebGraph.Provide(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalRouter provides router,
        LocalToasts provides toasts,
        LocalSession provides session,
        LocalCatalog provides catalog,
        LocalSettings provides settings,
        LocalPreferences provides preferences,
        LocalResolveEpisodeVideo provides resolveEpisodeVideo,
        content = content,
    )
}
