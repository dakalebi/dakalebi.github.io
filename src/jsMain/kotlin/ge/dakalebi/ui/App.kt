package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import ge.dakalebi.app.Route
import ge.dakalebi.app.Router
import ge.dakalebi.auth.AuthStore
import ge.dakalebi.data.Library
import ge.dakalebi.firebase.FirebaseConfig
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

@Composable
fun App() {
    if (!FirebaseConfig.isConfigured) {
        SetupNotice()
        return
    }

    val route = Router.current
    val loading = AuthStore.loading
    val user = AuthStore.user

    LaunchedEffect(loading, user, route) {
        if (loading) return@LaunchedEffect
        if (user == null) {
            // Drop cached rows on sign-out rather than refetching: a refetch
            // races the session teardown and comes back as permission errors.
            Library.reset()
            if (route !is Route.Login) Router.replace(Route.Login)
        } else if (route is Route.Login) {
            Router.replace(Route.Dashboard)
        }
    }

    // The tab names whatever is open, so a row of tabs still says which episode
    // is playing. Derived from the route here rather than pushed from the watch
    // screen: a single writer cannot strand a stale title on the way out, and
    // the episode itself is only known once the catalog has loaded.
    val title = (route as? Route.Watch)
        ?.let { Library.byId(it.episodeId) }
        ?.let { S.episodeDocumentTitle(it.seasonNumber, it.episodeNumber) }
        ?: S.documentTitle
    LaunchedEffect(title) { document.title = title }

    when {
        loading -> Div({ classes("center-note") }) { Text(S.loading) }
        user == null -> LoginScreen()
        else -> when (route) {
            is Route.Watch -> WatchScreen(route.episodeId)
            else -> DashboardScreen()
        }
    }

    ToastHost()
}

/**
 * Shown instead of a stack trace when the Firebase config is still the
 * placeholder, which is the state a fresh clone starts in.
 */
@Composable
private fun SetupNotice() {
    Div({ classes("login-wrap") }) {
        Div({ classes("login-bg") })
        Div({ classes("login-card") }) {
            Div({ classes("eyebrow") }) { Text(S.setupEyebrow.caps) }
            H1({ classes("login-h") }) { Text(S.setupTitle.caps) }
            P({
                style {
                    property("margin", "0")
                    property("font-size", "13.5px")
                    property("color", "var(--tx-dim)")
                }
            }) {
                Text(S.setupBody)
            }
            P({
                classes("mono")
                style {
                    property("margin", "0")
                    property("font-size", "11.5px")
                    property("color", "var(--mut)")
                    property("word-break", "break-all")
                }
            }) { Text("src/jsMain/kotlin/ge/dakalebi/firebase/FirebaseConfig.kt") }
        }
    }
}
