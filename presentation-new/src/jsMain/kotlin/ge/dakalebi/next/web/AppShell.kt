package ge.dakalebi.next.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ge.dakalebi.di.rememberDashboardViewModel
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
import ge.dakalebi.presentation.DashboardConfirm
import ge.dakalebi.presentation.DashboardViewModel
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import kotlinx.browser.window
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
 * The signed-in app: the tab bar on a phone, the side menu from 64rem, and the tab's
 * screen between them.
 *
 * One [DashboardViewModel] for the whole app, made here: the season menu and the
 * Settings tab both ask through it, and its question (a confirm dialog) is drawn here,
 * over whichever tab asked.
 *
 * An open episode (`#/watch/…`) draws the Home tab for now. The player arrives in the
 * next phase and opens over the tab.
 */
@Composable
internal fun AppShell(route: Route, build: AppBuild) {
    val dashboard = rememberDashboardViewModel()
    val tab = Tab.of(route)

    // A new tab starts at its top. Moving between seasons inside the tab does not.
    LaunchedEffect(tab) { window.scrollTo(0.0, 0.0) }

    Div({ classes("app") }) {
        SideNav(tab)
        Main({ classes("app-main") }) {
            when (tab) {
                Tab.Home -> HomeScreen(dashboard)
                Tab.Seasons -> SeasonsScreen((route as? Route.Season)?.number, dashboard)
                Tab.Settings -> SettingsScreen(dashboard, build)
            }
        }
        TabBar(tab)
        ToastRegion(toasts().items)
    }

    DashboardDialogs(dashboard)
}

/** The phone's tab bar, hidden from 64rem where the side menu takes over. */
@Composable
private fun TabBar(current: Tab) {
    Nav({
        classes("tabbar")
        attr("aria-label", S.menu)
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
