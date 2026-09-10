package ge.dakalebi.ui.tv.focus

import kotlinx.browser.window
import org.w3c.dom.DOMRect
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import kotlin.math.abs

/**
 * One element's box, read once.
 *
 * Every rectangle a move needs is collected before anything is focused or
 * scrolled, because interleaving reads and writes forces a layout flush per
 * element instead of one for the whole pass.
 *
 * The edges are constructor parameters rather than a rectangle's own, because
 * [visibleBox] builds one out of the *clipped* extent rather than the raw
 * rectangle, and every distance in this file has to be measured against what is
 * on screen.
 */
internal class Box(
    val el: HTMLElement,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    constructor(el: HTMLElement, rect: DOMRect) : this(el, rect.left, rect.top, rect.right, rect.bottom)

    val centreX = left + (right - left) / 2
    val centreY = top + (bottom - top) / 2
}

/**
 * Whether an element can be focused at all right now.
 *
 * `offsetParent` is null for anything `display: none`, which is how a
 * conditionally-rendered control drops out of navigation without the screen
 * having to tell the engine. A zero-size box catches the rest.
 */
internal fun HTMLElement.isNavigable(): Boolean {
    if (offsetParent == null) return false
    val r = getBoundingClientRect()
    return r.width > 0 && r.height > 0
}

internal fun HTMLElement.box(): Box = Box(this, getBoundingClientRect())

/**
 * The element's [Box] if it can be focused right now, else null — reading its
 * rectangle exactly once.
 *
 * Folds [isNavigable]'s visibility test into the same measurement, so a move that
 * needs both the "can I land here" answer and the geometry pays one
 * `getBoundingClientRect` per item instead of two. On a large season grid that
 * halves the rectangle reads a keypress forces.
 */
internal fun HTMLElement.navigableBox(): Box? {
    if (offsetParent == null) return null
    val rect = getBoundingClientRect()
    if (rect.width <= 0.0 || rect.height <= 0.0) return null
    return Box(this, rect)
}

/**
 * Rectangles of the clipping ancestors met during one pass, read once each.
 *
 * Every tile in a rail shares that rail, and every band shares the page scroller, so
 * a cross-band move that measures sixty items would otherwise read the same three or
 * four rectangles sixty times over.
 */
internal class ClipCache {
    private val rects = mutableMapOf<HTMLElement, DOMRect>()

    fun rectOf(el: HTMLElement): DOMRect = rects.getOrPut(el) { el.getBoundingClientRect() }
}

/**
 * The part of this element that is actually on screen, or null when none of it is.
 *
 * **This is the difference between what an element claims and what a viewer can
 * see, and getting it wrong is the whole of two reported bugs.** A season chip
 * scrolled out of its strip still reports a full rectangle: `getBoundingClientRect`
 * describes where the box *is*, not whether an ancestor's `overflow` is painting it.
 * So a chip parked twenty pixels off the left of its rail measured as the nearest
 * thing to the navigation rail, won the cross-band contest against every visible
 * band, and took the ring somewhere the viewer could not see it. Measured on the
 * fixture: Right out of the rail landed on `season-3` at y=628 in a 540px viewport,
 * and Left from a continue tile landed on the season strip instead of the rail.
 *
 * Clipping rather than merely rejecting is deliberate. A half-visible chip is still
 * a legitimate destination, but the honest distance to it is the distance to the
 * sliver on screen — not to the edge hiding behind the scroller. Clamping the box to
 * the visible region gives every band at the same content edge the same distance,
 * which is what lets the caller break the tie on something meaningful instead of on
 * document order.
 *
 * The viewport clamp at the end is what keeps a band below the fold out of a
 * sideways press: there is nothing to the right of the navigation rail on a screen
 * scrolled past it except things the viewer would have to be shown first.
 *
 * [reachableOn] is the exception, and it names the axis the press is travelling
 * along. A clip only means "the viewer cannot get there" when nothing will move to
 * reveal it, and a scroll container about to be scrolled *by this very move* is not
 * that. Down means "the next band", and on a page whose bands are taller than the fold
 * the next band is entirely below it: measured at 960x540 with the interface size on
 * its 130% step, the continue rail sat at y 552 in a 540px viewport and Down from the
 * hero found no candidate at all, so the ring could not leave the hero. Passing
 * `Axis.Y` for a vertical press keeps every clip that a scroll cannot undo —
 * `overflow: hidden`, which is how the player's shelf hides the rows it has moved out
 * of its window — and drops the ones it can.
 */
internal fun HTMLElement.visibleBox(
    within: Element,
    clips: ClipCache,
    reachableOn: Axis? = null,
): Box? {
    if (offsetParent == null) return null
    val rect = getBoundingClientRect()
    var left = rect.left
    var top = rect.top
    var right = rect.right
    var bottom = rect.bottom
    if (right - left <= 0.0 || bottom - top <= 0.0) return null

    var node = parentElement as? HTMLElement
    while (node != null) {
        val clipsX = node.clipsOn(Axis.X) && !node.revealableOn(Axis.X, reachableOn)
        val clipsY = node.clipsOn(Axis.Y) && !node.revealableOn(Axis.Y, reachableOn)
        if (clipsX || clipsY) {
            val clip = clips.rectOf(node)
            if (clipsX) {
                if (clip.left > left) left = clip.left
                if (clip.right < right) right = clip.right
            }
            if (clipsY) {
                if (clip.top > top) top = clip.top
                if (clip.bottom < bottom) bottom = clip.bottom
            }
            if (right - left < MIN_VISIBLE || bottom - top < MIN_VISIBLE) return null
        }
        if (node == within) break
        node = node.parentElement as? HTMLElement
    }

    if (reachableOn != Axis.X) {
        if (left < 0.0) left = 0.0
        right = minOf(right, window.innerWidth.toDouble())
    }
    if (reachableOn != Axis.Y) {
        if (top < 0.0) top = 0.0
        bottom = minOf(bottom, window.innerHeight.toDouble())
    }
    if (right - left < MIN_VISIBLE || bottom - top < MIN_VISIBLE) return null

    return Box(this, left, top, right, bottom)
}

/**
 * Whether this ancestor's clip on [axis] is one the move about to happen will undo —
 * true only for the axis the press travels along, and only for a box that genuinely
 * scrolls. See [visibleBox].
 */
private fun HTMLElement.revealableOn(axis: Axis, reachableOn: Axis?): Boolean =
    axis == reachableOn && scrollsOn(axis)

/**
 * Whether this element's computed `overflow` on [axis] hides what spills past it.
 *
 * Memoised on the node for the same reason [scrollsOn] is: `getComputedStyle` forces
 * a style recalc, this is asked once per ancestor per measured item, and `tv.css`
 * sets each container's `overflow` once by class with nothing toggling it at runtime.
 *
 * `hidden`, `clip`, `auto` and `scroll` all clip; only `visible` does not. Note that
 * a box with one axis `visible` and the other not computes the `visible` one to
 * `auto`, so a rail declaring only `overflow-x` clips on both — which is correct,
 * and is why this asks the computed value rather than the declared one.
 */
private fun HTMLElement.clipsOn(axis: Axis): Boolean {
    val key = if (axis == Axis.X) "__tvClipsX" else "__tvClipsY"
    (asDynamic()[key] as? Boolean)?.let { return it }
    val property = if (axis == Axis.X) "overflow-x" else "overflow-y"
    val value = window.getComputedStyle(this).getPropertyValue(property).trim()
    val result = value.isNotEmpty() && value != "visible"
    asDynamic()[key] = result
    return result
}

/**
 * How much of an item has to survive clipping for it to count as on screen.
 *
 * A sliver is not a destination: landing on two pixels of a chip reads as the ring
 * vanishing. Four pixels is below anything deliberate and above the sub-pixel noise
 * that fractional device ratios produce.
 */
private const val MIN_VISIBLE = 4.0

/**
 * Distance travelled in [direction] to reach [to], or null if it is not in that
 * direction at all.
 *
 * A two-pixel tolerance absorbs the sub-pixel rectangles that `clamp()` sizing
 * and fractional device ratios produce; without it two tiles in the same row can
 * disagree about which is to the right of the other.
 */
internal fun along(from: Box, to: Box, direction: Direction): Double? {
    val d = when (direction) {
        Direction.Right -> to.left - from.right
        Direction.Left -> from.left - to.right
        Direction.Down -> to.top - from.bottom
        Direction.Up -> from.top - to.bottom
    }
    return if (d >= -2.0) d else null
}

/** How far off the travel line [to] sits. Lower is a straighter move. */
internal fun cross(from: Box, to: Box, direction: Direction): Double =
    if (direction.isHorizontal) abs(to.centreY - from.centreY) else abs(to.centreX - from.centreX)

/** Whether the two boxes share any extent on the axis the move is *not* along. */
internal fun overlaps(from: Box, to: Box, direction: Direction): Boolean =
    if (direction.isHorizontal) {
        to.bottom > from.top + 2 && to.top < from.bottom - 2
    } else {
        to.right > from.left + 2 && to.left < from.right - 2
    }

/**
 * The best candidate in [direction], or null when the group ends here.
 *
 * Scoring is `along + 4 * cross`. The cross-axis penalty is what makes a wrapping
 * grid step along its own row before it considers the row below, while still
 * letting Down pick the tile directly underneath rather than whichever one
 * happens to be nearest in a straight line.
 *
 * Horizontal moves additionally *require* row overlap. That is the rule that
 * stops Right at the end of a grid row instead of wrapping to the start of the
 * next one, which reads as a jump rather than a move.
 */
internal fun bestCandidate(from: Box, candidates: List<Box>, direction: Direction): HTMLElement? {
    var best: Box? = null
    var bestScore = Double.MAX_VALUE
    for (to in candidates) {
        if (to.el == from.el) continue
        val distance = along(from, to, direction) ?: continue
        if (direction.isHorizontal && !overlaps(from, to, direction)) continue
        val score = distance + 4 * cross(from, to, direction)
        if (score < bestScore) {
            bestScore = score
            best = to
        }
    }
    return best?.el
}
