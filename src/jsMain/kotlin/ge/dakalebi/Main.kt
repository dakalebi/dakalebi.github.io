package ge.dakalebi

import ge.dakalebi.app.Log
import ge.dakalebi.app.Prefs
import ge.dakalebi.app.Router
import ge.dakalebi.auth.AuthStore
import ge.dakalebi.firebase.FirebaseConfig
import ge.dakalebi.i18n.S
import ge.dakalebi.ui.App
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable

fun main() {
    // First, so a crash during the rest of startup is still reported.
    Log.installGlobalHandlers()
    // `lang` is outside the composition, so it is the one attribute the i18n
    // layer has to push rather than be read from. The title is seeded here to
    // cover the frames before the first composition; after that App owns it.
    document.title = S.documentTitle
    document.documentElement?.setAttribute("lang", S.tag)
    Router.start()
    Prefs.start()
    // Touching Firebase before the config is filled in would throw on init.
    if (FirebaseConfig.isConfigured) AuthStore.start()

    renderComposable(rootElementId = "root") { App() }
}
