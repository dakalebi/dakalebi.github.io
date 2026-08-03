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
import org.w3c.dom.HTMLElement

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

    // Watching, not browsing: the rail is chrome over content, and there is no content
    // behind a full-screen video for it to sit beside.
    val showRail = account != null && route !is Route.Watch

    DisposableEffect(Unit) {
        val removeListeners = input.install()
        val layer = input.push(
            TvLayer(
                key = "tv-root",
                root = { refs.root },
                /*
                 * **This layer is the app.** Popping it removes the only global input
                 * handler there is, leaving a UI that draws but does not respond, so it
                 * must never be dismissed — the same reason the player sets this, one
                 * level down.
                 *
                 * It was the default `true` until Copilot's review of #12 pointed at it,
                 * and the hole was real and reachable: on the watch route `onBack` below
                 * returns false (there is no rail to jump to), the player above declines
                 * as well, and `TvInput.back` then pops the first dismissible layer it
                 * finds — this one. The previous ladder happened to return true on that
                 * route, which masked the missing flag rather than removing the need for
                 * it.
                 */
                dismissible = false,
                /*
                 * The documented Back ladder for a left-navigation app, and it is not
                 * a history pop:
                 *
                 *   "App uses left navigation: activate the left side menu and focus
                 *    on the currently active menu item."
                 *
                 * So Back from content jumps out to the rail and lands on the item for
                 * the page you are on — not the first item, and not the previous URL.
                 * Back again, now already in the rail, falls through to the exit
                 * protocol. That gives a two-press exit from anywhere, which is what
                 * the guidance describes, and it replaces the old behaviour of routing
                 * Back to the dashboard.
                 */
                onBack = {
                    val root = refs.root
                    val active = document.activeElement
                    val inRail = active?.closest("[data-tv-group=\"$NAV_GROUP\"]") != null
                    when {
                        root == null -> false
                        /*
                         * The player is the one screen with no rail, so it has no rail
                         * item to jump to and the ladder's first rung does not exist
                         * there. Back leaves it instead, which is the rung the browse
                         * screen reaches by a different route.
                         */
                        router.current is Route.Watch -> {
                            router.replace(Route.Dashboard)
                            true
                        }
                        // Already in the rail: let the exit protocol run.
                        inRail -> false
                        // Settings is a destination, not a modal, so Back from it goes
                        // to the rail like anywhere else. The rail's active item is
                        // Settings, which is where the ring lands.
                        else -> focusRailActiveItem(root)
                    }
                },
            ),
        )

        /*
         * The rail's whole behaviour, in one subscription: it is open exactly while the
         * ring is inside it.
         *
         * Written straight to `classList` rather than through Compose state, for the
         * same reason the rest of the focus layer bypasses Compose. Two reasons here
         * specifically, and the second one is not a micro-optimisation:
         *
         * 1. A recomposition is dispatched through the frame clock, and Compose HTML's
         *    frame clock is `requestAnimationFrame`, which a browser stops entirely when
         *    the page is not visible. State-driven expansion is therefore a rail that
         *    silently stops responding whenever the frame clock does — the same class of
         *    bug the input layer is installed from a `DisposableEffect` to avoid.
         * 2. It keeps the promise the focus engine makes: a keypress touches DOM nodes
         *    and nothing else.
         */
        SpatialNav.onFocusChanged = { item ->
            val open = item.closest("[data-tv-group=\"$NAV_GROUP\"]") != null
            refs.root?.let { root ->
                root.classList.toggle("rail-open", open)
                (root.querySelector(".tv-rail-nav") as? HTMLElement)
                    ?.classList?.toggle("open", open)
            }
        }

        // Deferred by a turn of the event loop so the children exist to focus.
        // `setTimeout`, not `requestAnimationFrame`: see the note above.
        val landFocus = window.setTimeout({ refs.root?.let { SpatialNav.ensureFocused(it) } }, 0)

        onDispose {
            window.clearTimeout(landFocus)
            SpatialNav.onFocusChanged = null
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
            // No `rail-open` here: that class is owned by the focus subscriber above, and
            // listing it in the composition would make Compose fight it on every render.
            classNames(
                "tv-root",
                // The watch screen fills the panel; every other screen is padded.
                if (route is Route.Watch && account != null) "tv-root-bare" else null,
            )
            ref { element ->
                refs.root = element
                onDispose { refs.root = null }
            }
        }) {
            // Inside the root, so the rail is in the focus engine's scope and one Left
            // press reaches it from anywhere on the screen.
            if (showRail) TvNavRail(route = route)

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

/**
 * Moves the ring to the rail's active destination.
 *
 * Returns whether it found one, so an unhandled Back can fall through to the exit
 * protocol rather than silently doing nothing.
 */
private fun focusRailActiveItem(root: Element): Boolean {
    val rail = root.querySelector("[data-tv-group=\"$NAV_GROUP\"]") ?: return false
    val target = rail.querySelector(".tv-nav-item.on") as? HTMLElement
        ?: rail.querySelector("[data-tv-item]") as? HTMLElement
        ?: return false
    SpatialNav.focus(target, direction = null, scope = root)
    return true
}
