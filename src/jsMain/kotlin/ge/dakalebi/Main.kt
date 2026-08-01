package ge.dakalebi

import ge.dakalebi.core.Log
import ge.dakalebi.data.firebase.FirebaseConfig
import ge.dakalebi.di.AppGraph
import ge.dakalebi.di.Provide
import ge.dakalebi.i18n.S
import ge.dakalebi.ui.App
import ge.dakalebi.ui.SetupNotice
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable

/**
 * Entry point, and the only place that builds anything.
 *
 * Everything below is handed its dependencies: this function constructs the
 * graph, starts the pieces that need a browser, and gives the whole thing to
 * the composition. Nothing else calls a constructor.
 */
fun main() {
    // First, so a crash during the rest of startup is still reported.
    Log.installGlobalHandlers()

    // Touching Firebase before the config is filled in throws on init, so the
    // setup notice has to render without a graph at all.
    if (!FirebaseConfig.isConfigured) {
        renderComposable(rootElementId = "root") { SetupNotice() }
        return
    }

    val graph = AppGraph()
    graph.router.start()
    graph.preferences.start()
    // Before the first paint, so a reload does not flash Georgian at someone who
    // chose English. The account's copy arrives later and wins if it disagrees;
    // this is only what the device last saw.
    graph.settings.applyCachedLanguage()
    // Seeded to cover the frames before the first composition; from then on App
    // owns both of these and keeps them following the language.
    document.title = S.documentTitle
    document.documentElement?.setAttribute("lang", S.tag)
    graph.session.start()

    renderComposable(rootElementId = "root") {
        graph.Provide { App() }
    }
}
