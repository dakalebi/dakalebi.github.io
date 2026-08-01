package ge.dakalebi

import ge.dakalebi.app.Log
import ge.dakalebi.app.Prefs
import ge.dakalebi.app.Router
import ge.dakalebi.auth.AuthStore
import ge.dakalebi.data.Settings
import ge.dakalebi.firebase.FirebaseConfig
import ge.dakalebi.i18n.S
import ge.dakalebi.ui.App
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable

fun main() {
    // First, so a crash during the rest of startup is still reported.
    Log.installGlobalHandlers()
    Router.start()
    Prefs.start()
    // Before the first paint, so a reload does not flash Georgian at someone who
    // chose English. The account's copy arrives later and wins if it disagrees;
    // this is only what the device last saw.
    Settings.applyCachedLanguage()
    // Seeded to cover the frames before the first composition; from then on App
    // owns both of these and keeps them following the language.
    document.title = S.documentTitle
    document.documentElement?.setAttribute("lang", S.tag)
    // Touching Firebase before the config is filled in would throw on init.
    if (FirebaseConfig.isConfigured) AuthStore.start()

    renderComposable(rootElementId = "root") { App() }
}
