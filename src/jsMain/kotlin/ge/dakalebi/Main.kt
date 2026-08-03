package ge.dakalebi

import ge.dakalebi.core.Log
import ge.dakalebi.data.firebase.FirebaseConfig
import ge.dakalebi.di.AppGraph
import ge.dakalebi.di.Provide
import ge.dakalebi.i18n.S
import ge.dakalebi.ui.App
import ge.dakalebi.ui.SetupNotice
import ge.dakalebi.ui.Shell
import ge.dakalebi.ui.shell
import ge.dakalebi.ui.tv.TvApp
import ge.dakalebi.ui.tv.dev.tvFixtureGraph
import ge.dakalebi.ui.useFixtures
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
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

    // `?ui=tv-demo` swaps the outside world for fixtures and never touches
    // Firebase. It is how the TV screens are verified at all: an automated
    // browser has no session and cannot sign in, so every screen past the
    // sign-in form would otherwise be reachable only on a real television.
    val graph = if (useFixtures) {
        Log.w("boot", "fixture graph: no Firebase, no real data")
        tvFixtureGraph()
    } else {
        // Touching Firebase before the config is filled in throws on init, so the
        // setup notice has to render without a graph at all.
        if (!FirebaseConfig.isConfigured) {
            renderComposable(rootElementId = "root") { SetupNotice() }
            return
        }
        AppGraph()
    }
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

    // Which front end this page is comes from the document, not from the device.
    // Both entry points load the same bundle; see `ui/Shell.kt`.
    val render = {
        renderComposable(rootElementId = "root") {
            graph.Provide {
                if (shell == Shell.Tv) TvApp() else App()
            }
        }
    }

    if (useFixtures) {
        // Load before the first composition rather than from an effect inside it.
        //
        // Recomposition is driven by `requestAnimationFrame`, which a headless or
        // occluded browser stops — so a screen that needs a second frame to show
        // its data shows nothing there, which is exactly the environment this
        // fixture exists to be verified in. Loading first means the first paint is
        // already the finished screen.
        MainScope().launch {
            runCatching { graph.catalog.ensureLoaded(FIXTURE_UID) }
                .onFailure { Log.e("boot", "fixture load failed", it) }
            render()
        }
    } else {
        render()
    }
}

/** Matches the account the fixture graph reports. */
private const val FIXTURE_UID = "demo"
