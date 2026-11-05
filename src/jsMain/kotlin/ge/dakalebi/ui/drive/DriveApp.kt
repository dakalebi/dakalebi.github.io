package ge.dakalebi.ui.drive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import ge.dakalebi.di.LocalResolveEpisodeVideo
import ge.dakalebi.di.catalog
import ge.dakalebi.di.preferences
import ge.dakalebi.di.router
import ge.dakalebi.di.session
import ge.dakalebi.di.settings
import ge.dakalebi.i18n.S
import ge.dakalebi.presentation.Route
import ge.dakalebi.ui.LoginScreen
import ge.dakalebi.ui.ToastHost
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * Driving mode's root: the auth gate, the one video player, and two screens.
 *
 * A third front end on the same bundle and the same graph, declared by
 * `drive/index.html` the way `/tv/` is. What it changes is what is on screen and how
 * much of it: a phone in a car mount, read at a glance, pressed with one thumb. There is
 * no browsing here. The home screen offers one episode, and the player screen is the
 * native player with big controls around it, because every extra choice is time with
 * eyes off the road.
 *
 * The player is built here rather than inside the player screen, so one `<video>`
 * outlives the route. The tap on the home screen starts it before the player screen
 * exists, and that first start is what lets every later one, autoplay included, work
 * on iOS without another tap.
 */
@Composable
fun DriveApp() {
    val router = router()
    val session = session()
    val catalog = catalog()
    val settings = settings()
    val preferences = preferences()
    val resolveVideo = LocalResolveEpisodeVideo.current
    val scope = rememberCoroutineScope()

    val player = remember {
        DrivePlayer(
            catalog = catalog,
            settings = settings,
            preferences = preferences,
            router = router,
            resolveVideo = resolveVideo,
            uid = { session.uid },
        )
    }

    // Synchronous, not a LaunchedEffect: the listeners must be on the element before the
    // first tap, and a LaunchedEffect waits for a frame.
    DisposableEffect(player) {
        val remove = player.install()
        onDispose { remove() }
    }

    val route = router.current
    val account = session.account

    // The same gate and loading the TV root has. Settings carry the account-synced
    // autoplay switch, which is the only setting this shell reads.
    LaunchedEffect(account?.uid) {
        val uid = account?.uid
        if (uid == null) {
            player.stop()
            catalog.reset()
            settings.stop()
        } else {
            settings.start(scope, uid)
            catalog.ensureLoaded(uid)
        }
    }

    LaunchedEffect(S.tag) { document.documentElement?.setAttribute("lang", S.tag) }
    LaunchedEffect(S.documentTitle) { document.title = "${S.driveMode} - ${S.seriesInitials}" }

    Div({ classes("drive-root") }) {
        when {
            session.loading -> Div({ classes("drive-note") }) { Text(S.loading) }
            account == null -> LoginScreen()
            route is Route.Watch -> DrivePlayerScreen(route.episodeId, player)
            else -> DriveHomeScreen(player)
        }
    }

    ToastHost()
}
