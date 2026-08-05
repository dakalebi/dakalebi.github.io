package ge.dakalebi.ui.tv.focus

import org.w3c.dom.HTMLElement
import kotlin.math.abs

/**
 * One element's box, read once.
 *
 * Every rectangle a move needs is collected before anything is focused or
 * scrolled, because interleaving reads and writes forces a layout flush per
 * element instead of one for the whole pass.
 */
internal class Box(val el: HTMLElement, rect: org.w3c.dom.DOMRect) {
    val left = rect.left
    val right = rect.right
    val top = rect.top
    val bottom = rect.bottom
    val centreX = rect.left + rect.width / 2
    val centreY = rect.top + rect.height / 2
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
