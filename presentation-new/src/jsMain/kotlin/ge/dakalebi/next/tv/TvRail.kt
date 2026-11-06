package ge.dakalebi.next.tv

import androidx.compose.runtime.Composable
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.Icon
import ge.dakalebi.next.tv.focus.FocusAxis
import ge.dakalebi.next.tv.focus.SpatialNav
import ge.dakalebi.next.tv.focus.focusGroup
import ge.dakalebi.next.tv.focus.focusItem
import ge.dakalebi.next.web.Tab
import ge.dakalebi.presentation.Router
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/** The rail's focus group. The shell reads it to know whether the ring is in the rail. */
internal const val NAV_GROUP = "nav"

/**
 * The rail on the left: the mark, then Home, Seasons and Settings as icons.
 *
 * While the ring is in it ([open]) it widens over the page and shows the names. It is
 * `position: fixed`, which is what the focus engine reads as chrome: Up and Down stay in
 * it, Left goes nowhere, and Right goes back to where the ring came from.
 *
 * OK on an item opens that place with the ring in it ([onOpen]); the item is still a
 * link, so the place has an address.
 */
@Composable
internal fun TvRail(current: Tab, open: Boolean, onOpen: (Tab) -> Unit) {
    Nav({
        classes("tv-rail")
        if (open) classes("tv-rail--open")
        attr("aria-label", S.menu)
        focusGroup(NAV_GROUP, FocusAxis.Y)
    }) {
        Div({ classes("tv-rail-mark"); attr("aria-hidden", "true") }) { Text(S.seriesInitials.caps) }
        Div({ classes("tv-rail-items") }) {
            Tab.entries.forEach { tab ->
                A(Router.href(tab.route), {
                    classes("tv-rail-item")
                    if (tab == current) attr("aria-current", "page")
                    focusItem(railKey(tab))
                    onClick { event ->
                        event.preventDefault()
                        onOpen(tab)
                    }
                }) {
                    Icon(tab.glyph)
                    Span({ classes("tv-rail-label") }) { Text(tab.label) }
                }
            }
        }
    }
}

private fun railKey(tab: Tab): String = "nav-${tab.name.lowercase()}"

/**
 * Puts the ring on the rail's item for the place on show, and remembers the item it
 * left so Right returns there. False when there is no rail.
 */
internal fun focusRailCurrent(root: Element): Boolean {
    val rail = root.querySelector("[data-tv-group=\"$NAV_GROUP\"]") ?: return false
    val target = rail.querySelector("[aria-current=\"page\"]") as? HTMLElement
        ?: rail.querySelector("[data-tv-item]") as? HTMLElement
        ?: return false
    SpatialNav.seatRailReturn(document.activeElement as? HTMLElement)
    SpatialNav.focus(target, direction = null, scope = root)
    return true
}

/** Puts the ring on [tab]'s rail item. */
internal fun focusRailItem(root: Element, tab: Tab): Boolean {
    val target = root.querySelector("[data-tv-item=\"${railKey(tab)}\"]") as? HTMLElement ?: return false
    SpatialNav.focus(target, direction = null, scope = root)
    return true
}
