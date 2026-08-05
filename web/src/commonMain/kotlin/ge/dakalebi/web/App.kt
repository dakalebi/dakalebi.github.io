package ge.dakalebi.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import ge.dakalebi.di.catalog
import ge.dakalebi.di.router
import ge.dakalebi.di.session
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.i18n.S
import ge.dakalebi.presentation.Route
import ge.dakalebi.web.dashboard.DashboardScreen
import ge.dakalebi.web.ui.Tokens
import ge.dakalebi.web.ui.ToastHost
import ge.dakalebi.web.watch.WatchScreen

/**
 * The root of the 2.0 app.
 *
 * The same shape as the DOM app's `App`: a session gate, a route switch, and the toast host over
 * everything. What changed is only how it draws — every effect below is the DOM version's,
 * including the reasons they are keyed the way they are.
 */
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
            // Drop cached rows on sign-out rather than refetching: a refetch races the session
            // teardown and comes back as permission errors.
            catalog.reset()
            if (route !is Route.Login) router.replace(Route.Login)
        } else if (route is Route.Login) {
            router.replace(Route.Dashboard)
        }
    }

    // Derived from the route rather than pushed from the watch screen: a single writer cannot
    // strand a stale title on the way out, and the episode itself is only known once the catalog
    // has loaded.
    val title = (route as? Route.Watch)
        ?.let { catalog.byId(it.episodeId) }
        ?.let { S.episodeDocumentTitle(it.seasonNumber, it.episodeNumber) }
        ?: S.documentTitle
    LaunchedEffect(title) { setDocumentTitle(title) }
    LaunchedEffect(S.tag) { setDocumentLang(S.tag) }

    // Keyed on the uid alone so navigating does not re-run it. Tearing the listener down on
    // sign-out matters: left attached, it keeps querying a document the signed-out client may no
    // longer read.
    val scope = rememberCoroutineScope()
    LaunchedEffect(account?.uid) {
        val uid = account?.uid
        if (uid == null) settings.stop() else settings.start(scope, uid)
        // Costs one document read, and only on sign-in or sign-out.
        session.refreshAdminRights()
    }

    // Opaque, all the way across. The canvas is the app: the only things not drawn here are the
    // `<video>` and the episode stills, and those are real elements laid *over* this surface rather
    // than showing through it (see `Overlay`).
    Box(Modifier.fillMaxSize().background(Tokens.bg)) {
        when {
            loading -> CenterNote(S.loading)
            account == null -> LoginScreen()
            else -> when (route) {
                is Route.Watch -> WatchScreen(route.episodeId)
                else -> DashboardScreen()
            }
        }

        ToastHost(toasts().items)
    }
}

/** One line of dim text in the middle of the screen: loading, or nothing to show. */
@Composable
fun CenterNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Tokens.mut, fontSize = 13.sp)
    }
}

/** The same, with a spinner: work is in flight rather than absent. */
@Composable
fun CenterSpinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Tokens.red)
    }
}
