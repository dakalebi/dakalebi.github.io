package ge.dakalebi.ui.tv.focus

import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

internal enum class Axis { X, Y }

/**
 * Focus without letting the browser scroll for us.
 *
 * `element.focus()` performs its own scroll-into-view, and that is the thing
 * [centre] exists to replace: it moves every scrollable ancestor at once, so
 * stepping sideways along a rail also jumps the page. Measured on the fixture —
 * the page scrolled vertically on a horizontal move, and the per-axis centring
 * below was not the cause.
 *
 * `preventScroll` is widely but not universally honoured, so the plain call is
 * kept as a fallback. Losing the option costs the page a jump, not the move.
 */
internal fun HTMLElement.focusWithoutScrolling() {
    runCatching { asDynamic().focus(js("({ preventScroll: true })")) }
        .onFailure { focus() }
}

/**
 * Centres [item] in the nearest scrollable ancestor, one axis at a time.
 *
 * `scrollIntoView` cannot do this. It walks *every* scrollable ancestor at once,
 * so centring a tile inside a horizontal rail also scrolls the page vertically
 * and the whole screen bobs while you sweep sideways. Doing it per axis is the
 * only way to say "move the rail, leave the page alone".
 *
 * Set directly rather than through `scrollTo({behavior})`: whether the sheet asks
 * for smooth scrolling is a styling decision, and `scroll-behavior` on the
 * container already expresses it.
 */
internal fun centre(item: HTMLElement, axes: Set<Axis>, within: Element) {
    val xScroller = if (Axis.X in axes) scrollableAncestor(item, Axis.X, within) else null
    val yScroller = if (Axis.Y in axes) scrollableAncestor(item, Axis.Y, within) else null
    if (xScroller == null && yScroller == null) return

    // Read every rectangle first, then write both offsets. A scroll write dirties
    // layout, so a `getBoundingClientRect` after it forces a reflow; batching the reads
    // ahead of the writes turns two reflows into one — which is felt on a weak TV GPU
    // where every D-pad press pays this.
    val itemRect = item.getBoundingClientRect()
    val left = xScroller?.let {
        val viewRect = it.getBoundingClientRect()
        (it.scrollLeft + (itemRect.left - viewRect.left) - (it.clientWidth - itemRect.width) / 2)
            .coerceAtLeast(0.0)
    }
    val top = yScroller?.let {
        val viewRect = it.getBoundingClientRect()
        (it.scrollTop + (itemRect.top - viewRect.top) - (it.clientHeight - itemRect.height) / 2)
            .coerceAtLeast(0.0)
    }
    if (left != null) xScroller.scrollLeft = left
    if (top != null) yScroller.scrollTop = top
}

/**
 * Centres [xItem] horizontally and [yItem] vertically in one read-then-write pass.
 *
 * A vertical D-pad move scrolls the rail (on X, to re-centre the item) and the page (on
 * Y, to bring the band into view), and the two targets differ: the item on X, the whole
 * band or the item on Y (see [verticalTarget]). Doing them as two separate [centre]
 * calls writes `scrollLeft` and then reads the Y target's rectangle, forcing a reflow.
 * This reads both rectangles before either write, so a vertical press costs one reflow
 * rather than two.
 */
internal fun centreAxes(xItem: HTMLElement, yItem: HTMLElement, within: Element) {
    val xScroller = scrollableAncestor(xItem, Axis.X, within)
    val yScroller = scrollableAncestor(yItem, Axis.Y, within)
    if (xScroller == null && yScroller == null) return

    val left = xScroller?.let {
        val itemRect = xItem.getBoundingClientRect()
        val viewRect = it.getBoundingClientRect()
        (it.scrollLeft + (itemRect.left - viewRect.left) - (it.clientWidth - itemRect.width) / 2)
            .coerceAtLeast(0.0)
    }
    val top = yScroller?.let {
        val itemRect = yItem.getBoundingClientRect()
        val viewRect = it.getBoundingClientRect()
        (it.scrollTop + (itemRect.top - viewRect.top) - (it.clientHeight - itemRect.height) / 2)
            .coerceAtLeast(0.0)
    }
    if (left != null) xScroller.scrollLeft = left
    if (top != null) yScroller.scrollTop = top
}

/**
 * Centres [item] horizontally in its rail and, only if it is off screen, brings it
 * into view vertically.
 *
 * The rule a sideways press has always followed is "move the rail, leave the page
 * alone", and it is right up to the moment the ring lands somewhere the page is not
 * showing. Escaping the navigation rail rightward does exactly that: the rail is
 * fixed chrome that spans the panel, so the band the ring arrives in may be far below
 * the fold, and centring on X alone left the ring at y=628 in a 540px viewport with
 * nothing visibly focused at all. That is the reported "nothing lights up".
 *
 * Conditional, not unconditional, and that is the whole design of this function. A
 * horizontal move that centred the page vertically as well would make the screen bob
 * on every step along a shelf, which is the bug [centre] was split per axis to avoid.
 * Acting only when the item is not fully inside its scroller costs one press — the one
 * that arrives — and nothing afterwards, because by then the band is on screen.
 *
 * Both rectangles are read before either write, so this still costs one reflow.
 */
internal fun centreXRevealY(item: HTMLElement, within: Element) {
    val xScroller = scrollableAncestor(item, Axis.X, within)
    val yScroller = scrollableAncestor(item, Axis.Y, within)
    if (xScroller == null && yScroller == null) return

    val itemRect = item.getBoundingClientRect()
    val left = xScroller?.let {
        val viewRect = it.getBoundingClientRect()
        (it.scrollLeft + (itemRect.left - viewRect.left) - (it.clientWidth - itemRect.width) / 2)
            .coerceAtLeast(0.0)
    }
    val top = yScroller?.let {
        val viewRect = it.getBoundingClientRect()
        // Fully inside already: leave the page exactly where the viewer put it.
        if (itemRect.top >= viewRect.top - 1 && itemRect.bottom <= viewRect.bottom + 1) {
            null
        } else {
            (it.scrollTop + (itemRect.top - viewRect.top) - (it.clientHeight - itemRect.height) / 2)
                .coerceAtLeast(0.0)
        }
    }
    if (left != null) xScroller.scrollLeft = left
    if (top != null) yScroller.scrollTop = top
}

/**
 * Which element a vertical move should bring into view: the whole [group] for a single
 * row of items, otherwise the [item] itself. Decides *what* to centre; the caller
 * ([centreAxes]) does the scrolling.
 *
 * Centring the group keeps a rail's heading on screen above the focused row rather than
 * scrolling off, and holds the page still while the ring sweeps sideways. That only
 * makes sense for a group that *is* one row — an `X` rail, where the group and the
 * focused tile occupy the same band of the screen anyway.
 *
 * A multi-row group must centre the item instead, and merely fitting the viewport is
 * not enough to make group-centring safe. Measured at 960x540: a three-row season grid
 * 498px tall fits, so centring it parked the page with 21px of clearance above and
 * below — the seasons strip sat at y -83 and the "to top" button at y 566, both fully
 * outside the viewport, and since geometric navigation only sees what is visible the
 * ring could not leave the grid in either direction. Centring the focused row instead
 * puts a neighbour on screen at both ends. A group taller than the viewport was already
 * broken for the stronger reason that no single scroll position shows every row.
 *
 * Returning the target rather than scrolling here is what lets [centreAxes] read the X
 * and Y rectangles together before any write, saving a reflow on every vertical press.
 */
internal fun verticalTarget(item: HTMLElement, group: HTMLElement): HTMLElement =
    if (SpatialNav.axisOf(group) == FocusAxis.X) group else item

/**
 * The closest ancestor that can actually scroll [from] on [axis], stopping at
 * [within].
 *
 * Two conditions, and the second one is the whole point of this function existing
 * rather than a one-line `scrollWidth > clientWidth` at the call site:
 *
 * 1. **It must overflow.** A rail whose items happen to fit should not be scrolled
 *    at all — nudging a fitting row by a fraction of a pixel is a visible wobble for
 *    no gain.
 * 2. **It must be styled to scroll on that axis** — `overflow` of `auto` or
 *    `scroll`. This is not belt-and-suspenders; it is the fix for a bug that
 *    disabled *all* vertical scrolling in the app. `scrollTop`/`scrollLeft` are only
 *    honoured on a scroll container: assign them on an `overflow: visible` or
 *    `hidden` box and the browser reports success and moves nothing. Yet such a box
 *    still reports `scrollHeight > clientHeight` whenever its content spills past its
 *    padding box — which a `.tv-band` does by design, because every rail inside it
 *    carries a negative margin to give the focus ring room. So condition 1 alone
 *    matched `.tv-band` (a `visible` box overflowing by nine pixels), returned it as
 *    the scroller, and the walk stopped there — never reaching `.tv-root`, the one
 *    element that could actually move. Measured: the ring marched down the page while
 *    `scrollTop` stayed pinned at zero. Requiring the style as well walks straight
 *    past the false positive to the real scroller.
 *
 * The scrollbar itself is suppressed with `scrollbar-width: none`, so "styled to
 * scroll" costs nothing visually; see `tv.css`.
 *
 * **The walk also stops at fixed chrome**, because scrolling a page cannot move
 * something pinned to the viewport. The navigation rail is `position: fixed`, so
 * without this the walk sailed past it to `.tv-root` and centring a rail item
 * scrolled the *content* to put a stationary icon in the middle of the screen —
 * measured: landing on the rail dragged the page from 590 to 430 and the shelf the
 * viewer had been reading slid away under them. There is nothing to fix by scrolling
 * here: a fixed element is always exactly where it will be.
 */
internal fun scrollableAncestor(from: HTMLElement, axis: Axis, within: Element): HTMLElement? {
    if (from.isFixed()) return null
    var node: HTMLElement? = from.parentElement as? HTMLElement
    while (node != null) {
        val overflows = when (axis) {
            Axis.X -> node.scrollWidth > node.clientWidth + 1
            Axis.Y -> node.scrollHeight > node.clientHeight + 1
        }
        // Asked before the fixed test, so a fixed box that genuinely scrolls its own
        // children still serves as their scroller.
        if (overflows && node.scrollsOn(axis)) return node
        if (node.isFixed()) return null
        if (node == within) return null
        node = node.parentElement as? HTMLElement
    }
    return null
}

/**
 * Whether this element is pinned to the viewport rather than carried by the page.
 *
 * Memoised like [scrollsOn], and for the same reason: `tv.css` sets `position` by
 * class and nothing toggles it at runtime — the rail animates its *width* when the
 * ring arrives, never how it is positioned.
 */
internal fun HTMLElement.isFixed(): Boolean {
    (asDynamic()["__tvFixed"] as? Boolean)?.let { return it }
    val result = window.getComputedStyle(this).getPropertyValue("position").trim() == "fixed"
    asDynamic()["__tvFixed"] = result
    return result
}

/**
 * Whether this element's computed `overflow` on [axis] actually permits scrolling.
 *
 * Memoised on the node itself, because `getComputedStyle` forces a style recalc and this
 * ran ~5 times per D-pad press (the ancestor walk happens for X once and Y twice), yet
 * the answer never changes — `tv.css` sets each container's `overflow` once by class and
 * nothing toggles it at runtime. The cached flag is an expando on the element, so it is
 * collected with the node and cannot leak across Compose re-creating the DOM.
 */
internal fun HTMLElement.scrollsOn(axis: Axis): Boolean {
    val key = if (axis == Axis.X) "__tvScrollsX" else "__tvScrollsY"
    (asDynamic()[key] as? Boolean)?.let { return it }
    val property = if (axis == Axis.X) "overflow-x" else "overflow-y"
    // Trimmed: computed values are normally whitespace-free, but a stray space would
    // fail the exact-match below, miss a real scroller, and re-break the vertical
    // scrolling this function exists to keep working — a cheap guard against that.
    val value = window.getComputedStyle(this).getPropertyValue(property).trim()
    val result = value == "auto" || value == "scroll"
    asDynamic()[key] = result
    return result
}
