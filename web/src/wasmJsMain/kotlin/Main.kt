package ge.dakalebi.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import ge.dakalebi.core.Log
import ge.dakalebi.web.di.Provide
import ge.dakalebi.web.di.WebGraph
import ge.dakalebi.web.di.demoGraph
import kotlinx.browser.document
import kotlinx.browser.window

/**
 * Entry point for the Compose Multiplatform web app (wasmJs / canvas).
 *
 * The same startup order as the DOM app's `main`: global error handlers first, then the graph,
 * then the three things that have to be attached before the first frame — the router's history
 * listener, the auth listener, and the cached language. Composition comes last, so nothing
 * renders against a graph that is only half awake.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    Log.installGlobalHandlers()

    // `?demo` swaps Firebase for fixtures, which is the only way to reach the signed-in screens
    // without an account. See `demoGraph`.
    val graph = if ("demo" in window.location.search) demoGraph() else WebGraph()
    graph.router.start()
    graph.session.start()
    graph.preferences.start()
    // Before the first frame: otherwise the app paints Georgian for one frame on a device whose
    // owner chose English.
    graph.settings.applyCachedLanguage()

    ComposeViewport(document.body!!) {
        graph.Provide {
            AppTheme {
                App()
            }
        }
    }
}
