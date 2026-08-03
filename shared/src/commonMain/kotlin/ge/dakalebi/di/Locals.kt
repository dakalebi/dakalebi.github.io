package ge.dakalebi.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import ge.dakalebi.domain.usecase.ResolveEpisodeVideo
import ge.dakalebi.presentation.CatalogStore
import ge.dakalebi.presentation.PreferencesStore
import ge.dakalebi.presentation.Router
import ge.dakalebi.presentation.SessionStore
import ge.dakalebi.presentation.SettingsStore
import ge.dakalebi.presentation.ToastStore

/**
 * How a screen reaches its dependencies.
 *
 * `staticCompositionLocalOf` rather than `compositionLocalOf`: these are
 * provided exactly once at the root and never change for the life of the page,
 * and the static variant skips the per-read tracking that would otherwise cost
 * a recomposition scope on every access for no benefit.
 *
 * Each local has no default. A missing provider is then a loud error at the
 * first read rather than a second, silently-empty store that would look like
 * the app simply having no data.
 *
 * Which graph fills these in is [Provide]'s business, and that is the only part
 * of this file that knows a concrete implementation exists.
 */
private fun <T> local(name: String) = staticCompositionLocalOf<T> {
    error("$name was read outside AppGraph.Provide")
}

val LocalRouter = local<Router>("LocalRouter")
val LocalToasts = local<ToastStore>("LocalToasts")
val LocalSession = local<SessionStore>("LocalSession")
val LocalCatalog = local<CatalogStore>("LocalCatalog")
val LocalSettings = local<SettingsStore>("LocalSettings")
val LocalPreferences = local<PreferencesStore>("LocalPreferences")
val LocalResolveEpisodeVideo = local<ResolveEpisodeVideo>("LocalResolveEpisodeVideo")

/**
 * Shorthands, so a screen reads `catalog()` rather than `LocalCatalog.current`.
 * Purely cosmetic, but these appear on nearly every line of the UI.
 */
@Composable @ReadOnlyComposable fun router(): Router = LocalRouter.current

@Composable @ReadOnlyComposable fun toasts(): ToastStore = LocalToasts.current

@Composable @ReadOnlyComposable fun session(): SessionStore = LocalSession.current

@Composable @ReadOnlyComposable fun catalog(): CatalogStore = LocalCatalog.current

@Composable @ReadOnlyComposable fun settings(): SettingsStore = LocalSettings.current

@Composable @ReadOnlyComposable fun preferences(): PreferencesStore = LocalPreferences.current
