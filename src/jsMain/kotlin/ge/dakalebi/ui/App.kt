package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import ge.dakalebi.di.catalog
import ge.dakalebi.di.router
import ge.dakalebi.di.session
import ge.dakalebi.di.settings
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.Route
import ge.dakalebi.ui.dashboard.DashboardScreen
import ge.dakalebi.ui.watch.WatchScreen
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

@Composable
fun App() {
    val router = router()
    val session = session()
    val catalog = catalog()
    val settings = settings()

    val route = router.current
    val loading = session.loading
    val account = session.account

    LaunchedEffect(loading, account, route) {
        if (loading) return@LaunchedEffect
        if (account == null) {
            // Drop cached rows on sign-out rather than refetching: a refetch
            // races the session teardown and comes back as permission errors.
            catalog.reset()
            if (route !is Route.Login) router.replace(Route.Login)
        } else if (route is Route.Login) {
            router.replace(Route.Dashboard)
        }
    }

    // The tab names whatever is open, so a row of tabs still says which episode
    // is playing. Derived from the route here rather than pushed from the watch
    // screen: a single writer cannot strand a stale title on the way out, and
    // the episode itself is only known once the catalog has loaded.
    val title = (route as? Route.Watch)
        ?.let { catalog.byId(it.episodeId) }
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
    LaunchedEffect(account?.uid) {
        val uid = account?.uid
        if (uid == null) settings.stop() else settings.start(scope, uid)
        // Costs one document read, and only on sign-in or sign-out.
        session.refreshAdminRights()
    }

    when {
        loading -> Div({ classes("center-note") }) { Text(S.loading) }
        account == null -> LoginScreen()
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
 *
 * Public, and free of every store, because it is the one screen that renders
 * before there is a graph to read them from.
 */
@Composable
fun SetupNotice() {
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
            }) { Text("src/jsMain/kotlin/ge/dakalebi/data/firebase/FirebaseConfig.kt") }
        }
    }
}
