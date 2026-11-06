package ge.dakalebi.next.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.di.catalog
import ge.dakalebi.di.rememberDashboardViewModel
import ge.dakalebi.di.router
import ge.dakalebi.di.session
import ge.dakalebi.di.sessionState
import ge.dakalebi.di.toasts
import ge.dakalebi.i18n.S
import ge.dakalebi.next.components.Spinner
import ge.dakalebi.next.components.ToastRegion
import ge.dakalebi.next.gallery.Gallery
import ge.dakalebi.next.showGallery
import ge.dakalebi.next.tv.focus.FocusMemory
import ge.dakalebi.next.tv.focus.SpatialNav
import ge.dakalebi.next.tv.input.LocalTvInput
import ge.dakalebi.next.tv.input.TvInput
import ge.dakalebi.next.tv.input.TvLayer
import ge.dakalebi.next.web.AppBuild
import ge.dakalebi.next.web.DashboardDialogs
import ge.dakalebi.next.web.Tab
import ge.dakalebi.presentation.Route
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Main
import org.w3c.dom.HTMLElement

/** The stage element, set by `ref`. */
private class StageRef {
    var element: HTMLElement? = null
}

/**
 * What the shell keeps between routes, and what the root layer's Back asks.
 *
 * [railOpen] is state: the rail and the page under it draw differently while the ring
 * is in the rail. The rest are plain fields, read by callbacks rather than drawn.
 */
private class TvShellState {
    var railOpen by mutableStateOf(false)

    /** The last route that was not a watch route or the sign-in: where Back from an episode goes. */
    var browseRoute: Route = Route.Dashboard

    /** A rail item was pressed: once the new place is drawn, the ring goes into it. */
    var enterContent: Boolean = false

    /** The Back ladder of the screen on show. See [TvScreens]. */
    var onBack: () -> Boolean = { false }
}

/**
 * The redesign's TV root, drawn on `/next/tv/`.
 *
 * Owns the one [TvInput]: it installs the window listener, pushes the screen's base
 * layer (not dismissible, so Back at the top asks the input layer what to do), and
 * provides the input to everything below as [LocalTvInput], which is how components
 * know to draw D-pad stops instead of web keyboard handling.
 *
 * The stage is its own scroller, because the focus engine scrolls the nearest
 * overflowing ancestor to centre the ring and the page itself never scrolls on TV.
 *
 * `?gallery` draws the component gallery instead of the app.
 */
@Composable
fun TvRoot(build: AppBuild) {
    val input = remember { TvInput() }
    val stage = remember { StageRef() }
    val shell = remember { TvShellState() }

    // A DisposableEffect, not a LaunchedEffect: it runs in the same frame as the first
    // composition, so no key press arrives before the listener is there.
    DisposableEffect(input) {
        val removeListeners = input.install()
        val layer = input.push(
            TvLayer(
                key = "tv-root",
                root = { stage.element },
                dismissible = false,
                onBack = { shell.onBack() },
            ),
        )
        // `setTimeout`, not the frame clock, which a background WebView stops.
        val landFocus = window.setTimeout({ stage.element?.let { SpatialNav.ensureFocused(it) } }, 0)

        onDispose {
            window.clearTimeout(landFocus)
            layer.dismiss()
            removeListeners()
        }
    }

    CompositionLocalProvider(LocalTvInput provides input) {
        Div({
            classes("tv-stage")
            ref { element ->
                stage.element = element
                onDispose { stage.element = null }
            }
        }) {
            if (showGallery) Gallery(shell = "tv") else TvScreens(input, stage, shell, build)
        }
    }
}

/**
 * The app: a spinner until the session has reported, the sign-in for nobody, the
 * player on a watch route, and otherwise the rail with Home, Seasons or Settings.
 *
 * Not `TvApp`: that is the old TV root's name, and the CSS class check starts `/tv/`
 * from every top-level declaration called that.
 *
 * **Back**, after the layers above have had their say (a dialog, a menu, the player's
 * own chrome):
 * - on an episode, to the place it was opened from;
 * - in the rail, to Home, and from Home's rail item out of the app (or the hint);
 * - anywhere else, into the rail on the item for the place on show.
 */
@Composable
private fun TvScreens(input: TvInput, stage: StageRef, shell: TvShellState, build: AppBuild) {
    val router = router()
    val session = session()
    val state = sessionState()
    val catalog = catalog()
    val toasts = toasts()
    val route = router.current
    val viewer = state.viewer

    // Before the screen below is drawn: a landing made while it mounts is filed under it.
    SpatialNav.screenKey = screenKeyOf(route)
    if (route !is Route.Watch && route !is Route.Login) shell.browseRoute = route

    // Replaced, not pushed, so Back does not bounce between the form and the app.
    LaunchedEffect(state.loading, viewer, route) {
        if (state.loading) return@LaunchedEffect
        if (viewer == null) {
            if (route !is Route.Login) router.replace(Route.Login)
        } else if (route is Route.Login) {
            router.replace(Route.Dashboard)
        }
    }

    // A new viewer starts from the top of every screen, not from where the last one was.
    LaunchedEffect(viewer?.id) { if (viewer == null) FocusMemory.clear() }

    val title = (route as? Route.Watch)
        ?.let { catalog.byId(it.episodeId) }
        ?.let { S.episodeDocumentTitle(it.seasonNumber, it.episodeNumber) }
        ?: S.documentTitle
    LaunchedEffect(title) { document.title = title }
    LaunchedEffect(S.tag) { document.documentElement?.setAttribute("lang", S.tag) }
    LaunchedEffect(state.account?.uid) { session.refreshAdminRights() }

    DisposableEffect(input) {
        // The Android TV app's bridge, when there is one: Back at the top closes the app.
        val host = window.asDynamic().AndroidTvHost
        if (host != null && host.exit != null) input.onExitRequested = { host.exit(); Unit }
        input.onTopLevelBack = { toasts.show(S.backAtStart) }

        // The rail opens while the ring is in it. Derived from where the ring is, so
        // every way in (Left, Back, a landing) opens it and every way out closes it.
        val unsubscribe = SpatialNav.onFocusChanged { item ->
            val open = item.closest("[data-tv-group=\"$NAV_GROUP\"]") != null
            if (open != shell.railOpen) shell.railOpen = open
        }
        onDispose {
            unsubscribe()
            input.onExitRequested = null
            input.onTopLevelBack = null
        }
    }

    shell.onBack = {
        val root = stage.element
        val inRail = document.activeElement?.closest("[data-tv-group=\"$NAV_GROUP\"]") != null
        when {
            root == null -> false
            router.current is Route.Watch -> {
                router.replace(shell.browseRoute)
                true
            }
            // The sign-in and the spinner have nowhere to go back to.
            session.state.value.viewer == null -> false
            inRail && router.current != Route.Dashboard -> {
                router.replace(Route.Dashboard)
                focusRailItem(root, Tab.Home)
                true
            }
            inRail -> false
            else -> focusRailCurrent(root)
        }
    }

    // A rail item opens its place with the ring in it, at the place's start.
    LaunchedEffect(route) {
        if (!shell.enterContent) return@LaunchedEffect
        shell.enterContent = false
        window.setTimeout({ stage.element?.let { SpatialNav.focusEntry(it) } }, 0)
    }

    when {
        state.loading -> Div({ classes("tv-starting") }) { Spinner() }
        viewer == null -> TvSignIn()
        route is Route.Watch -> TvWatch(route.episodeId)
        else -> TvSignedIn(route, shell, build, stage)
    }
    ToastRegion(toasts.items)
}

/** The rail and the place on show. */
@Composable
private fun TvSignedIn(route: Route, shell: TvShellState, build: AppBuild, stage: StageRef) {
    val dashboard = rememberDashboardViewModel()
    val router = router()
    val tab = Tab.of(route)

    // A new place starts at its top. Moving between seasons inside Seasons does not.
    LaunchedEffect(tab) { stage.element?.scrollTop = 0.0 }

    TvRail(
        current = tab,
        open = shell.railOpen,
        onOpen = { target ->
            if (router.current == target.route) {
                stage.element?.let { SpatialNav.focusEntry(it) }
            } else {
                shell.enterContent = true
                router.replace(target.route)
            }
        },
    )
    Main({
        classes("tv-main")
        if (shell.railOpen) classes("tv-main--behind")
    }) {
        when (tab) {
            Tab.Home -> TvHome(dashboard)
            Tab.Seasons -> TvSeasons((route as? Route.Season)?.number, dashboard)
            Tab.Settings -> TvSettings(dashboard, build)
        }
    }
    DashboardDialogs(dashboard)
}

/** What focus memory files a screen under: one key per place, not per address. */
private fun screenKeyOf(route: Route): String = when (route) {
    Route.Dashboard -> "home"
    Route.Seasons, is Route.Season -> "seasons"
    Route.Settings -> "settings"
    Route.Login -> "login"
    is Route.Watch -> "watch"
}
