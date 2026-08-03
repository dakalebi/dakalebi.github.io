package ge.dakalebi.ui.tv

import androidx.compose.runtime.Composable
import ge.dakalebi.i18n.S
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import ge.dakalebi.ui.Icon
import ge.dakalebi.ui.Icons
import ge.dakalebi.ui.assetBase
import ge.dakalebi.ui.classNames
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** The key the rail's focus group is filed under, shared with the shell. */
internal const val NAV_GROUP = "nav"

/**
 * The left-edge navigation rail: collapsed to icons, expanded when the ring is in it.
 *
 * This replaces a top bar, and the swap is the single most defining change in making
 * this feel like a television app rather than a web page in a big font. A top bar puts
 * the app's chrome on the axis the content scrolls along, so reaching Settings means
 * travelling up through every shelf; a rail puts it on the free axis, one Left press
 * from anywhere.
 *
 * **Both states are always visible.** That is the documented difference from a phone:
 * "in contrast to the mobile navigation drawer, the navigation drawer on TV has both
 * expanded and collapsed states visible to the user". There is no drawer to open, no
 * hamburger, and nothing to discover — the icons sit there permanently, and pressing
 * Left widens them into labels.
 *
 * **Two destinations, because the app has two screens.** Guidance asks for three to
 * seven; YouTube fills that with Search, Music, Movies, Podcasts and the rest, none of
 * which exist here — there is one show, and the seasons are a strip inside Home rather
 * than a place of their own. A third item invented to reach a count would be a second
 * route to somewhere you can already get, which costs a press to step past forever.
 *
 * There is deliberately **no Back or Exit item**. Shown as a "Don't": "the back button
 * on the remote is used to navigate backward on a TV. It's not necessary to show a
 * virtual back button on the screen."
 */
@Composable
fun TvNavRail(route: Route) {
    Div({
        // The `open` class is not set here. It is owned by the focus subscriber in
        // `TvApp`, which writes it straight to the DOM so expansion does not depend on a
        // recomposition — and therefore not on a running frame clock.
        classes("tv-rail-nav")
        // A `Y` group, so Up and Down walk the rail and a horizontal press leaves it.
        // `Y` is also the only axis `mayLeave` permits horizontal escape from, which
        // is exactly what makes one Right press return to the content.
        focusGroup(NAV_GROUP, FocusAxis.Y)
    }) {
        // The mark, as the rail's header rather than a destination. Not focusable:
        // a logo that takes a press to step past is a press wasted every time.
        Div({ classes("tv-rail-head") }) {
            Img(src = "${assetBase}logo.png", alt = S.appName) { classes("tv-mark") }
        }

        NavItem(
            key = "nav-home",
            route = Route.Dashboard,
            icon = Icons.home,
            label = S.home,
            // Active whenever we are not in Settings, so an episode opened from a
            // shelf still reads as "you are in Home".
            active = route !is Route.Settings,
        )

        Div({ classes("tv-nav-foot") }) {
            NavItem(
                key = "nav-settings",
                route = Route.Settings,
                icon = Icons.gear,
                label = S.settings,
                active = route is Route.Settings,
            )
        }
    }
}

/**
 * One rail entry.
 *
 * The label is always in the DOM and hidden by the rail's own width when collapsed,
 * rather than being added and removed. Swapping the content would reflow the rail
 * during the width animation, which reads as the icons jittering as it opens.
 */
@Composable
private fun NavItem(
    key: String,
    route: Route,
    icon: String,
    label: String,
    active: Boolean,
) {
    A(href = Router.href(route), attrs = {
        classNames("tv-nav-item", if (active) "on" else null)
        focusItem(key)
    }) {
        Icon(icon)
        Span({ classes("tv-nav-label") }) { Text(label) }
    }
}
