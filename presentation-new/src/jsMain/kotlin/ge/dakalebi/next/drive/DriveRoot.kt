package ge.dakalebi.next.drive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import ge.dakalebi.di.LocalPlayback
import ge.dakalebi.di.catalog
import ge.dakalebi.di.router
import ge.dakalebi.di.sessionState
import ge.dakalebi.di.toasts
import ge.dakalebi.i18n.S
import ge.dakalebi.next.components.Spinner
import ge.dakalebi.next.components.ToastRegion
import ge.dakalebi.next.gallery.Gallery
import ge.dakalebi.next.showGallery
import ge.dakalebi.next.web.SignInScreen
import ge.dakalebi.presentation.Route
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div

/**
 * The driving-mode root, drawn on `/drive/`.
 *
 * A phone in a car mount, read at a glance and pressed with one thumb. No browsing:
 * the home screen offers one episode ([DriveHome]) and the player screen is the
 * native player with big controls around it ([DriveWatch]).
 *
 * The session decides the screen, as in the old driving mode: a spinner until it has
 * reported, the sign-in form for nobody (the web page's, at drive's sizes), and the
 * two screens for an account or a guest. The route is left alone while signed out, so
 * a link to an episode still opens it after the sign-in.
 *
 * The player is made here, not in the player screen, so one `<video>` outlives the
 * route: the tap on the home screen starts it before the player screen exists, and
 * that first start is what lets every later one work on iOS without another tap
 * (see [DrivePlayer]).
 *
 * `?gallery` draws the component gallery instead, at drive's sizes.
 */
@Composable
fun DriveRoot() {
    if (showGallery) {
        Gallery(shell = "drive")
        return
    }

    val router = router()
    val catalog = catalog()
    val playback = LocalPlayback.current
    val player = remember { DrivePlayer(catalog = catalog, router = router, playback = playback) }

    // Synchronous, not a LaunchedEffect: the listeners must be on the element before the
    // first tap, and a LaunchedEffect waits for a frame.
    DisposableEffect(player) {
        val remove = player.install()
        onDispose { remove() }
    }

    val state = sessionState()
    val viewer = state.viewer
    val route = router.current

    // Nobody watching stops the car's player. The catalog and settings follow the
    // session on their own (`SessionViewModel`).
    LaunchedEffect(viewer?.id) {
        if (viewer == null) player.stop()
    }

    // What a screen reader picks its voice from, and the tab's name.
    LaunchedEffect(S.tag) { document.documentElement?.setAttribute("lang", S.tag) }
    LaunchedEffect(S.documentTitle) { document.title = "${S.driveMode} - ${S.seriesInitials}" }

    when {
        state.loading -> Div({ classes("drive-starting") }) { Spinner() }
        viewer == null -> SignInScreen()
        route is Route.Watch -> DriveWatch(route.episodeId, player)
        else -> DriveHome(player)
    }

    ToastRegion(toasts().items)
}
