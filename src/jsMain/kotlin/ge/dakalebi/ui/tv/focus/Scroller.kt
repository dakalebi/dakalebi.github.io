package ge.dakalebi.ui.tv.focus

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
    if (Axis.X in axes) {
        scrollableAncestor(item, Axis.X, within)?.let { scroller ->
            val itemRect = item.getBoundingClientRect()
            val viewRect = scroller.getBoundingClientRect()
            val offset = (itemRect.left - viewRect.left) - (scroller.clientWidth - itemRect.width) / 2
            scroller.scrollLeft = (scroller.scrollLeft + offset).coerceAtLeast(0.0)
        }
    }
    if (Axis.Y in axes) {
        scrollableAncestor(item, Axis.Y, within)?.let { scroller ->
            val itemRect = item.getBoundingClientRect()
            val viewRect = scroller.getBoundingClientRect()
            val offset = (itemRect.top - viewRect.top) - (scroller.clientHeight - itemRect.height) / 2
            scroller.scrollTop = (scroller.scrollTop + offset).coerceAtLeast(0.0)
        }
    }
}

/**
 * The closest ancestor that actually overflows on [axis], stopping at [within].
 *
 * "Actually overflows" rather than "is styled to scroll", because a rail whose
 * items happen to fit should not be scrolled at all — nudging a fitting row by a
 * fraction of a pixel is a visible wobble for no gain.
 *
 * **A scrolling container must be `overflow: auto` or `scroll`, not `hidden`.**
 * Measured: `hidden` still reports `scrollWidth > clientWidth`, so it looks
 * scrollable from here, and then silently ignores the assignment below — the ring
 * walks off the edge of a rail that never moves. Suppress the scrollbar with
 * `scrollbar-width: none` instead; see `tv.css`.
 */
private fun scrollableAncestor(from: HTMLElement, axis: Axis, within: Element): HTMLElement? {
    var node: HTMLElement? = from.parentElement as? HTMLElement
    while (node != null) {
        val overflows = when (axis) {
            Axis.X -> node.scrollWidth > node.clientWidth + 1
            Axis.Y -> node.scrollHeight > node.clientHeight + 1
        }
        if (overflows) return node
        if (node == within) return null
        node = node.parentElement as? HTMLElement
    }
    return null
}
