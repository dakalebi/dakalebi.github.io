package ge.dakalebi.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue

/**
 * Puts the whole graph in scope. Wraps the root composable, once.
 *
 * Separate from the locals themselves because this is the one part that names
 * [AppGraph], and the graph is where the platform's choices live. The locals in
 * `Locals.kt` name only interfaces and stores, so any platform that can build a
 * graph gets the same `catalog()` / `session()` shorthands for free.
 */
@Composable
fun AppGraph.Provide(content: @Composable () -> Unit) {
    CompositionLocalProvider(*providedValues(), content = content)
}

private fun AppGraph.providedValues(): Array<ProvidedValue<*>> = arrayOf(
    LocalRouter provides router,
    LocalToasts provides toasts,
    LocalSession provides session,
    LocalCatalog provides catalog,
    LocalSettings provides settings,
    LocalPreferences provides preferences,
    LocalResolveEpisodeVideo provides resolveEpisodeVideo,
)
