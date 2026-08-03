package ge.dakalebi.ui.tv.focus

import org.jetbrains.compose.web.attributes.AttrsScope
import org.w3c.dom.Element

/** Which way a D-pad press is asking to go. */
enum class Direction {
    Up, Down, Left, Right;

    val isHorizontal: Boolean get() = this == Left || this == Right
}

/**
 * How movement works inside one group.
 *
 * [X] a rail: Left/Right move within it, Up/Down leave it.
 * [Y] a stacked list: Up/Down move within it, Left/Right leave it.
 * [Grid] a wrapping grid: all four move within it until an edge, so the number of
 *   columns never has to be declared. `repeat(auto-fill, minmax(...))` decides
 *   that at layout time and the rectangles report it.
 */
enum class FocusAxis(internal val attr: String) { X("x"), Y("y"), Grid("grid") }

internal const val GROUP_ATTR = "data-tv-group"
internal const val AXIS_ATTR = "data-tv-axis"
internal const val ITEM_ATTR = "data-tv-item"

/**
 * Marks the one item the ring is on, maintained by the engine.
 *
 * The ring is styled from this rather than from `:focus` alone, because `:focus`
 * stops matching when the *document* loses focus — measured: `activeElement` was
 * still correct while `document.hasFocus()` was false and the pseudo-class did
 * not match, so the ring vanished. On a television that is not a hypothetical: an
 * Android WebView that has not been given Android focus is exactly that state, and
 * a cursor that disappears because a window is not frontmost is a cursor that
 * cannot be trusted. `:focus` is still styled too; this is the belt.
 */
internal const val FOCUS_ATTR = "data-tv-focus"

/**
 * Where the ring should start when a screen opens for the first time.
 *
 * Without it the ring lands on whatever comes first in the document, which on the
 * browse screen is the top bar — so arriving at the app and pressing OK would take
 * you straight to Settings. A screen knows what a viewer came for; the engine does
 * not. Memory still wins over this, because on the way *back* from an episode the
 * tile you launched from is what you came for.
 */
internal const val ENTRY_ATTR = "data-tv-entry"

/**
 * Marks one band a screen is built out of.
 *
 * [key] must be stable across recompositions and across leaving the screen: it is
 * what focus memory is filed under, and it is why coming back from an episode
 * puts the ring on the tile you launched from rather than on the first tile of
 * the season.
 */
fun <T : Element> AttrsScope<T>.focusGroup(key: String, axis: FocusAxis) {
    attr(GROUP_ATTR, key)
    attr(AXIS_ATTR, axis.attr)
}

/**
 * Marks one stop inside the enclosing group.
 *
 * Always `tabindex="-1"`, never `0`. Everything here is reachable by the D-pad,
 * which means nothing needs to be reachable by Tab — and a season of sixty tiles
 * in the native tab ring is sixty stops nobody asked for. The engine raises
 * exactly one item to `0` at a time, so a paired keyboard's Tab leaves the page
 * instead of walking it.
 */
fun <T : Element> AttrsScope<T>.focusItem(key: String, entry: Boolean = false) {
    attr(ITEM_ATTR, key)
    attr("tabindex", "-1")
    if (entry) attr(ENTRY_ATTR, "")
}
