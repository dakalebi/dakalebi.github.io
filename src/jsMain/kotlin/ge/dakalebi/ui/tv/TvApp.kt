package ge.dakalebi.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import ge.dakalebi.di.catalog
import ge.dakalebi.di.router
import ge.dakalebi.di.session
import ge.dakalebi.di.settings
import ge.dakalebi.i18n.S
import ge.dakalebi.presentation.Route
import ge.dakalebi.ui.ToastHost
import ge.dakalebi.ui.classNames
import ge.dakalebi.ui.tv.focus.FocusMemory
import ge.dakalebi.ui.tv.focus.SpatialNav
import ge.dakalebi.ui.tv.input.TvInput
import ge.dakalebi.ui.tv.input.TvLayer
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.Element

/** Mutable holders that must not trigger recomposition when they change. */
internal class TvRefs {
    var root: Element? = null
}

/**
 * The TV UI's root: the input layer, the auth gate, and the route table.
 *
 * Two deliberate choices about *how* this is wired, both of which matter more
 * than they look:
 *
 * 1. **The root element is not Compose state.** Storing it in a `mutableStateOf`
 *    would make every consumer wait for a recomposition to see it. It is read
 *    through a lambda instead, evaluated when a key is actually pressed.
 * 2. **The input layer is installed from `DisposableEffect`, not
 *    `LaunchedEffect`.** A `DisposableEffect` is invoked synchronously while the
 *    composition is applied; a `LaunchedEffect` is dispatched through the frame
 *    clock, and Compose HTML's frame clock is `requestAnimationFrame`, which a
 *    browser stops entirely when the page is not visible. The D-pad has to work on
 *    the first paint alone.
 */
@Composable
fun TvApp() {
    val refs = remember { TvRefs() }
    val input = remember { TvInput() }
    val router = router()
    val session = session()
    val catalog = catalog()
    val settings = settings()
    val scope = rememberCoroutineScope()

    val route = router.current
    val account = session.account

    DisposableEffect(Unit) {
        val removeListeners = input.install()
        val layer = input.push(
            TvLayer(
                key = "tv-root",
                root = { refs.root },
                // Back at the top of a screen means "up a level", and the only
                // level above anything here is the browse screen. Returning false
                // at the browse screen itself lets the exit protocol run.
                onBack = {
                    if (router.current == Route.Dashboard) {
                        false
                    } else {
                        router.replace(Route.Dashboard)
                        true
                    }
                },
            ),
        )
        // Deferred by a turn of the event loop so the children exist to focus.
        // `setTimeout`, not `requestAnimationFrame`: see the note above.
        val landFocus = window.setTimeout({ refs.root?.let { SpatialNav.ensureFocused(it) } }, 0)

        onDispose {
            window.clearTimeout(landFocus)
            layer.dismiss()
            removeListeners()
        }
    }

    // The signed-in account's data, and the account's own settings. Both are
    // LaunchedEffects because both are ordinary app behaviour that wants the real
    // frame clock; the fixture path pre-loads instead so the screens are complete
    // on the first paint. See `Main.kt`.
    LaunchedEffect(account?.uid) {
        val uid = account?.uid
        if (uid == null) {
            catalog.reset()
            settings.stop()
            // One account's place in a rail is not another's.
            FocusMemory.clear()
        } else {
            settings.start(scope, uid)
            session.refreshAdminRights()
            catalog.ensureLoaded(uid)
        }
    }

    LaunchedEffect(S.tag) { document.documentElement?.setAttribute("lang", S.tag) }
    LaunchedEffect(S.documentTitle) { document.title = S.documentTitle }

    // The player needs the input stack, and it is three screens down.
    CompositionLocalProvider(LocalTvInput provides input) {
        Div({
            // The watch screen fills the panel; every other screen is padded.
            classNames("tv-root", if (route is Route.Watch && account != null) "tv-root-bare" else null)
            ref { element ->
                refs.root = element
                onDispose { refs.root = null }
            }
        }) {
            when {
                session.loading -> Div({ classes("tv-note") }) { Text(S.loading) }
                account == null -> TvSignInScreen()
                route is Route.Settings -> TvSettingsScreen()
                route is Route.Watch -> TvWatchScreen(route.episodeId)
                else -> TvBrowseScreen()
            }
        }

        ToastHost()
    }
}
