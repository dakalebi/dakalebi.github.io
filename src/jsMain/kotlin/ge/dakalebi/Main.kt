package ge.dakalebi

import ge.dakalebi.app.Log
import ge.dakalebi.app.Prefs
import ge.dakalebi.app.Router
import ge.dakalebi.auth.AuthStore
import ge.dakalebi.firebase.FirebaseConfig
import ge.dakalebi.ui.App
import org.jetbrains.compose.web.renderComposable

fun main() {
    // First, so a crash during the rest of startup is still reported.
    Log.installGlobalHandlers()
    Router.start()
    Prefs.start()
    // Touching Firebase before the config is filled in would throw on init.
    if (FirebaseConfig.isConfigured) AuthStore.start()

    renderComposable(rootElementId = "root") { App() }
}
