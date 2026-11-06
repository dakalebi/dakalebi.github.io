package ge.dakalebi.next.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.di.rememberDashboardViewModel
import ge.dakalebi.di.rememberNowPlaying
import ge.dakalebi.di.router
import ge.dakalebi.di.session
import ge.dakalebi.di.sessionState
import ge.dakalebi.di.toasts
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.Badge
import ge.dakalebi.next.components.Button
import ge.dakalebi.next.components.ButtonVariant
import ge.dakalebi.next.components.ConfirmDialog
import ge.dakalebi.next.components.Glyph
import ge.dakalebi.next.components.Icon
import ge.dakalebi.next.components.ToastRegion
import ge.dakalebi.domain.service.COMPACT_SCREEN_MAX_QUALITY
import ge.dakalebi.next.player.isAppleMobile
import ge.dakalebi.next.player.isCompactScreen
import ge.dakalebi.presentation.DashboardConfirm
import ge.dakalebi.presentation.DashboardViewModel
import ge.dakalebi.presentation.NowPlayingViewModel
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import kotlinx.browser.window
import org.w3c.dom.events.Event
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** The app's three places. Each is a route, so it has an address and Back reaches it. */
internal enum class Tab(val route: Route, val glyph: Glyph) {
    Home(Route.Dashboard, Glyph.House),
    Seasons(Route.Seasons, Glyph.LayoutGrid),
    Settings(Route.Settings, Glyph.Settings),
    ;

    /** In Mkhedruli, not caps: small caps text is hard to read (the label-s role). */
    val label: String
        get() = when (this) {
            Home -> S.home
            Seasons -> S.seasons
            Settings -> S.settings
        }

    companion object {
        /** The tab a route belongs to. An open episode belongs to the tab under it. */
        fun of(route: Route): Tab = when (route) {
            Route.Seasons, is Route.Season -> Seasons
            Route.Settings -> Settings
            Route.Dashboard, Route.Login, is Route.Watch -> Home
        }
    }
}

/**
 * The signed-in app: the tab bar on a phone, the side menu from 64rem, the tab's screen
 * between them, and the episode playing.
 *
 * One [DashboardViewModel] for the whole app, made here: the season menu and the
 * Settings tab both ask through it, and its question (a confirm dialog) is drawn here,
 * over whichever tab asked.
 *
 * **The player.** One [NowPlayingViewModel] and one [WebPlayer] for the whole app, so an
 * episode keeps playing while the viewer browses. A watch route (`#/watch/…`) names the
 * episode and opens the player; on a phone that is the sheet over the tabs, and leaving
 * the route (Back, or the collapse button) shrinks it to the mini-player on the tab bar.
 * From 64rem the player is the panel on the right whenever an episode is loaded, and the
 * route only says which one. Under the player the tab the viewer came from stays drawn:
 * the last route that was not a watch route.
 */
@Composable
internal fun AppShell(route: Route, build: AppBuild) {
    val dashboard = rememberDashboardViewModel()
    val router = router()
    val toasts = toasts()
    val viewerId = sessionState().viewerId
    val nowPlaying = rememberNowPlaying(
        viewerId = viewerId,
        // A phone starts at 720p: the 1080p file is the slowest to start and the screen
        // hides the difference.
        maxQuality = COMPACT_SCREEN_MAX_QUALITY.takeIf { isCompactScreen },
        appleMobile = isAppleMobile,
    )
    val playing = nowPlaying.state.collectAsState().value.playback != null
    val player = remember { WebPlayer(toasts) }
    val desktop = rememberMediaQuery(DESKTOP_QUERY)
    val memory = remember { ShellMemory() }

    DisposableEffect(player) {
        val teardown = player.install()
        onDispose { teardown() }
    }

    // The route the tab content follows: a watch route keeps the tab under it.
    if (route !is Route.Watch) {
        memory.tabRoute = route
        memory.sawTab = true
    }
    val tab = Tab.of(memory.tabRoute)

    LaunchedEffect(route, nowPlaying) {
        when {
            route is Route.Watch -> nowPlaying.play(route.episodeId)
            memory.pendingStop -> {
                memory.pendingStop = false
                nowPlaying.stop()
            }
        }
    }
    LaunchedEffect(playing) { if (!playing) player.bind(null) }

    // A new tab starts at its top. Moving between seasons inside the tab does not, and
    // neither does opening or shrinking the player.
    LaunchedEffect(tab) { window.scrollTo(0.0, 0.0) }

    /** Back to the tab: the history entry before the watch route, or the tab's own route. */
    fun collapse() {
        if (memory.sawTab) window.history.back() else router.replace(memory.tabRoute)
    }

    /** Stops the episode. An open player first goes back to the tab, then stops. */
    fun close() {
        if (router.current !is Route.Watch) {
            nowPlaying.stop()
            return
        }
        memory.pendingStop = true
        collapse()
        // `replace` lands at once; `history.back` lands with the next `hashchange`.
        if (router.current !is Route.Watch) {
            memory.pendingStop = false
            nowPlaying.stop()
        }
    }

    val mode = when {
        !playing -> null
        desktop -> PlayerMode.Dock
        route is Route.Watch -> PlayerMode.Sheet
        else -> PlayerMode.Mini
    }
    val covered = mode == PlayerMode.Sheet

    Div({
        classes("app")
        when (mode) {
            PlayerMode.Mini -> classes("app--mini")
            PlayerMode.Sheet -> classes("app--sheet")
            PlayerMode.Dock -> classes("app--docked")
            null -> Unit
        }
    }) {
        SideNav(tab)
        Main({
            classes("app-main")
            // Under the sheet: out of reach of focus and screen readers.
            if (covered) attr("inert", "")
        }) {
            when (tab) {
                Tab.Home -> HomeScreen(dashboard)
                Tab.Seasons -> SeasonsScreen((memory.tabRoute as? Route.Season)?.number, dashboard)
                Tab.Settings -> SettingsScreen(dashboard, build)
            }
        }
        TabBar(tab, covered)
        if (mode != null) {
            NowPlaying(nowPlaying, player, mode, onCollapse = ::collapse, onClose = ::close)
        }
        ToastRegion(toasts.items)
    }

    DashboardDialogs(dashboard)
}

/** From here the side menu and the player's panel replace the tab bar and the mini-player. */
private const val DESKTOP_QUERY = "(min-width: 64rem)"

/**
 * What the shell remembers about the routes it has been on. Plain fields: they are read in
 * the same composition that writes them, and a change never needs a redraw of its own.
 */
private class ShellMemory {
    /** The last route that was not a watch route: the tab drawn under the player. */
    var tabRoute: Route = Route.Dashboard

    /**
     * A tab route has been on this page before, so the watch route has a history entry
     * under it and collapsing can go Back to it. A watch link opened cold has none.
     */
    var sawTab: Boolean = false

    /** Close was pressed on the open player: stop once the route has left it. */
    var pendingStop: Boolean = false
}

/** Whether [query] matches, kept current as the window changes. */
@Composable
private fun rememberMediaQuery(query: String): Boolean {
    val list = remember(query) { window.matchMedia(query) }
    var matches by remember(list) { mutableStateOf(list.matches) }
    DisposableEffect(list) {
        val listener: (Event) -> Unit = { matches = list.matches }
        list.addEventListener("change", listener)
        onDispose { list.removeEventListener("change", listener) }
    }
    return matches
}

/** The phone's tab bar, hidden from 64rem where the side menu takes over. */
@Composable
private fun TabBar(current: Tab, covered: Boolean) {
    Nav({
        classes("tabbar")
        attr("aria-label", S.menu)
        if (covered) attr("inert", "")
    }) {
        Tab.entries.forEach { tab ->
            A(Router.href(tab.route), {
                classes("tabbar-tab")
                if (tab == current) attr("aria-current", "page")
            }) {
                Icon(tab.glyph)
                Span({ classes("tabbar-label") }) { Text(tab.label) }
            }
        }
    }
}

/**
 * The desktop's side menu: the mark, the three places, and at the foot who is
 * watching. A guest gets the way to an account there, as the old top bar offered.
 */
@Composable
private fun SideNav(current: Tab) {
    val session = session()
    val state = sessionState()

    Nav({
        classes("sidenav")
        attr("aria-label", S.menu)
    }) {
        A(Router.href(Route.Dashboard), {
            classes("sidenav-mark")
            attr("aria-label", S.seriesTitle)
        }) {
            Text(S.seriesInitials.caps)
        }
        Div({ classes("sidenav-items") }) {
            Tab.entries.forEach { tab ->
                A(Router.href(tab.route), {
                    classes("sidenav-item")
                    if (tab == current) attr("aria-current", "page")
                }) {
                    Icon(tab.glyph)
                    Span { Text(tab.label) }
                }
            }
        }
        Div({ classes("sidenav-account") }) {
            if (state.isGuest) {
                Badge(S.guest)
                P({ classes("sidenav-note") }) { Text(S.guestProgressNote) }
                Button(S.signIn, onClick = session::leaveGuestMode, variant = ButtonVariant.Primary)
            } else {
                P({ classes("sidenav-email") }) { Text(state.email.orEmpty()) }
            }
        }
    }
}

/** The question the dashboard is asking, if any. */
@Composable
private fun DashboardDialogs(dashboard: DashboardViewModel) {
    val ui by dashboard.state.collectAsState()
    when (val confirm = ui.confirm) {
        DashboardConfirm.ResetAll -> ConfirmDialog(
            title = S.resetAllTitle,
            body = S.resetAllBody,
            confirmLabel = S.resetAllConfirm,
            onConfirm = dashboard::confirm,
            onDismiss = dashboard::dismiss,
        )
        is DashboardConfirm.MarkSeason -> ConfirmDialog(
            title = S.markSeasonTitle,
            body = S.markSeasonBody("${confirm.season}"),
            confirmLabel = S.markSeasonConfirm,
            onConfirm = dashboard::confirm,
            onDismiss = dashboard::dismiss,
            danger = false,
        )
        is DashboardConfirm.ResetSeason -> ConfirmDialog(
            title = S.resetSeasonTitle,
            body = S.resetSeasonBody("${confirm.season}"),
            confirmLabel = S.delete,
            onConfirm = dashboard::confirm,
            onDismiss = dashboard::dismiss,
        )
        null -> Unit
    }
}
