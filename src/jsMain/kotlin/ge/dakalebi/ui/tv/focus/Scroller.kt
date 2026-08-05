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
 * Which element a vertical move should bring into view: the whole [group] when it fits
 * its scroller, otherwise the [item] itself. Decides *what* to centre; the caller
 * ([centreAxes]) does the scrolling.
 *
 * Centring the group keeps a rail's heading on screen above the focused row rather than
 * scrolling off. That is right only while the group fits its scroller. A group taller
 * than the viewport — a full season is six rows — has no single scroll position that
 * shows every row, so centring it parks a fixed midpoint and the ring walks straight
 * off the bottom. Measured: a 548px grid in a 540px viewport pinned at `scrollTop` 553
 * and the row below the fold never returned. So the item is centred instead once the
 * group overflows.
 *
 * Returning the target rather than scrolling here is what lets [centreAxes] read the X
 * and Y rectangles together before any write, saving a reflow on every vertical press.
 */
internal fun verticalTarget(item: HTMLElement, group: HTMLElement, within: Element): HTMLElement {
    val scroller = scrollableAncestor(group, Axis.Y, within)
    val groupFits = scroller == null ||
        group.getBoundingClientRect().height <= scroller.clientHeight
    return if (groupFits) group else item
}

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
 */
internal fun scrollableAncestor(from: HTMLElement, axis: Axis, within: Element): HTMLElement? {
    var node: HTMLElement? = from.parentElement as? HTMLElement
    while (node != null) {
        val overflows = when (axis) {
            Axis.X -> node.scrollWidth > node.clientWidth + 1
            Axis.Y -> node.scrollHeight > node.clientHeight + 1
        }
        if (overflows && node.scrollsOn(axis)) return node
        if (node == within) return null
        node = node.parentElement as? HTMLElement
    }
    return null
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
private fun HTMLElement.scrollsOn(axis: Axis): Boolean {
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
