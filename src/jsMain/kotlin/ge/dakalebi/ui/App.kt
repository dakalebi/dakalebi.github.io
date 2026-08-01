package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import ge.dakalebi.app.Route
import ge.dakalebi.app.Router
import ge.dakalebi.auth.AuthStore
import ge.dakalebi.data.Library
import ge.dakalebi.data.Settings
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

    // Follows the language rather than being stamped once at startup: `lang` is
    // what a screen reader picks a voice from, and reading Georgian aloud in an
    // English one is worse than no attribute at all.
    LaunchedEffect(S.tag) { document.documentElement?.setAttribute("lang", S.tag) }

    // Keyed on the uid alone so navigating does not re-run it. Tearing the
    // listener down on sign-out matters: left attached, it keeps querying a
    // document the signed-out client may no longer read.
    val scope = rememberCoroutineScope()
    LaunchedEffect(user?.uid) {
        val uid = user?.uid
        if (uid == null) Settings.stop() else Settings.start(scope, uid)
    }

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
