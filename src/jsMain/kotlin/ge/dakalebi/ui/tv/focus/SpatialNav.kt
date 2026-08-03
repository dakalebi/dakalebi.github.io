package ge.dakalebi.ui.tv.focus

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import kotlin.math.abs

/**
 * Where the D-pad goes.
 *
 * Geometry within a group, memory across groups. Neither half works alone:
 *
 * - **Pure geometry** breaks on exactly the shape this app has. Press Down out of
 *   a rail that is scrolled four thousand pixels sideways and the nearest visible
 *   thing below is whatever happens to sit under the current tile, which is not
 *   where anyone was going.
 * - **A declared focus graph** cannot describe the season grid. It is
 *   `repeat(auto-fill, minmax(...))`, so the column count is a decision CSS makes
 *   at layout time, and half the screen is conditional besides — the continue rail
 *   only exists when something is started, the quality button only when there is
 *   more than one rendition. Every one of those becomes index bookkeeping a screen
 *   author has to keep right.
 *
 * Reading `getBoundingClientRect()` asks the browser what it actually did, which
 * is the one source that is never out of date.
 *
 * **Nothing here goes through Compose.** Attributes are written once when the
 * element is created; a move only calls `focus()` and sets `tabIndex` on raw DOM
 * nodes, and the ring is drawn by CSS. So sweeping across a sixty-tile grid
 * recomposes nothing. Driving focus from Compose state instead would recompose
 * every tile on every keypress, which is the difference between smooth and
 * unusable on a 2019 television.
 */
internal object SpatialNav {

    /** Every group inside [scope], in DOM order. */
    fun groupsIn(scope: Element): List<HTMLElement> =
        scope.querySelectorAll("[$GROUP_ATTR]").asList()
            .filterIsInstance<HTMLElement>()

    /** The navigable items of one group, excluding any nested group's items. */
    fun itemsOf(group: HTMLElement): List<HTMLElement> =
        group.querySelectorAll("[$ITEM_ATTR]").asList()
            .filterIsInstance<HTMLElement>()
            .filter { it.closest("[$GROUP_ATTR]") == group && it.isNavigable() }

    fun firstItem(scope: Element): HTMLElement? =
        groupsIn(scope).firstNotNullOfOrNull { itemsOf(it).firstOrNull() }

    fun groupOf(item: Element): HTMLElement? = item.closest("[$GROUP_ATTR]") as? HTMLElement

    private fun axisOf(group: HTMLElement): FocusAxis =
        when (group.getAttribute(AXIS_ATTR)) {
            FocusAxis.X.attr -> FocusAxis.X
            FocusAxis.Grid.attr -> FocusAxis.Grid
            else -> FocusAxis.Y
        }

    /**
     * Whether [direction] moves inside a group with this [axis], or leaves it.
     *
     * A grid keeps all four, which is what makes the column count irrelevant: Down
     * finds the tile below by geometry and only fails at the last row, and that
     * failure is what hands the press to the next group.
     */
    private fun staysWithin(axis: FocusAxis, direction: Direction): Boolean = when (axis) {
        FocusAxis.Grid -> true
        FocusAxis.X -> direction.isHorizontal
        FocusAxis.Y -> !direction.isHorizontal
    }

    /**
     * Whether a press that found nothing inside its group may cross to another.
     *
     * **Vertical presses always may.** A TV screen is a stack of bands and Down
     * means "the next band", whether the current one is a list that ran out or a
     * grid at its last row.
     *
     * **Horizontal presses may only leave a `Y` group**, where sideways was never
     * the group's own axis. Letting Right escape a rail or a grid is what produces
     * the worst bug in a D-pad UI: reaching the last tile of a row and being
     * teleported into an unrelated section. Running out of rail should feel like a
     * wall, because that is what it is.
     */
    private fun mayLeave(axis: FocusAxis, direction: Direction): Boolean =
        if (direction.isHorizontal) axis == FocusAxis.Y else true

    /**
     * The element focus should move to, or null if the press goes nowhere.
     *
     * Tries within the current group first, then group to group in DOM order.
     */
    fun move(from: HTMLElement, direction: Direction, scope: Element): HTMLElement? {
        val group = groupOf(from) ?: return null
        val fromBox = from.box()

        val axis = axisOf(group)
        if (staysWithin(axis, direction)) {
            bestCandidate(fromBox, itemsOf(group).map { it.box() }, direction)?.let { return it }
        }
        if (!mayLeave(axis, direction)) return null
        return leaveGroup(fromBox, group, direction, scope)
    }

    /** Every navigable item in [scope], regardless of which group owns it. */
    private fun allItems(scope: Element): List<HTMLElement> =
        scope.querySelectorAll("[$ITEM_ATTR]").asList()
            .filterIsInstance<HTMLElement>()
            .filter { it.isNavigable() }

    /**
     * Where a press goes when its own group has nothing left.
     *
     * Geometry over every item outside the current group, then the target group's
     * remembered item if it has one.
     *
     * This deliberately does **not** walk groups in DOM order, which was the first
     * attempt and is wrong for nested groups: a group nested inside another appears
     * *after* its parent in document order, so a segmented control inside a
     * settings list would find nothing below it while its parent's remaining rows
     * sat "before" it in the list. Measured on the fixture: Down out of the segment
     * went nowhere and Up out of it moved downwards.
     *
     * Geometry has neither problem, and the memory lookup afterwards is what keeps
     * it from misbehaving in the case DOM order was chosen for — arriving at a rail
     * that is scrolled far off to one side lands where you left it, not wherever
     * happens to be under the cursor.
     */
    private fun leaveGroup(
        from: Box,
        current: HTMLElement,
        direction: Direction,
        scope: Element,
    ): HTMLElement? {
        val candidates = allItems(scope).mapNotNull { element ->
            if (groupOf(element) == current) return@mapNotNull null
            val group = groupOf(element) ?: return@mapNotNull null
            val box = element.box()
            val distance = along(from, box, direction) ?: return@mapNotNull null
            if (direction.isHorizontal && !overlaps(from, box, direction)) return@mapNotNull null
            Candidate(box, group, distance)
        }
        if (candidates.isEmpty()) return null

        // The nearest *band*, then the nearest item in it — not the single best
        // scoring item. Scoring items directly is what the first attempt did, and
        // the cross-axis penalty that makes a grid step along its own row before
        // dropping a row is far too strong once bands are stacked: measured on the
        // browse screen, Down from the hero's right-hand button skipped the continue
        // rail *and* the season chips to land on a grid tile that happened to sit
        // underneath it. Down means "the next band", and no weighting expresses that.
        val nearest = candidates
            .groupBy { it.group }
            .minByOrNull { (_, group) -> group.minOf { it.along } }
            ?.key
            ?: return null

        remembered(nearest, itemsOf(nearest))?.let { return it }
        return candidates
            .filter { it.group == nearest }
            .minByOrNull { cross(from, it.box, direction) }
            ?.box?.el
    }

    /** One reachable item, with the band that owns it and how far away it is. */
    private class Candidate(val box: Box, val group: HTMLElement, val along: Double)

    /** The item this group was left on, if it is still there. */
    private fun remembered(group: HTMLElement, items: List<HTMLElement>): HTMLElement? {
        val groupKey = group.getAttribute(GROUP_ATTR) ?: return null
        val itemKey = FocusMemory.recall(groupKey) ?: return null
        return items.firstOrNull { it.getAttribute(ITEM_ATTR) == itemKey }
    }

    /**
     * Moves focus and keeps the item in view.
     *
     * Roving `tabindex`: the arriving item is the only one at `0`, so a paired
     * keyboard's Tab leaves the page instead of walking sixty tiles.
     *
     * A horizontal move centres the item in its own rail and leaves the page
     * alone. A vertical move centres the whole *group*, so a rail's heading stays
     * visible above the row that is focused rather than scrolling off.
     */
    fun focus(item: HTMLElement, direction: Direction?, scope: Element) {
        (document.activeElement as? HTMLElement)
            ?.takeIf { it.hasAttribute(ITEM_ATTR) }
            ?.let { it.tabIndex = -1 }
        // The ring does not depend on the document being frontmost. See FOCUS_ATTR.
        scope.querySelectorAll("[$FOCUS_ATTR]").asList()
            .filterIsInstance<HTMLElement>()
            .forEach { it.removeAttribute(FOCUS_ATTR) }
        item.setAttribute(FOCUS_ATTR, "")
        item.tabIndex = 0
        item.focusWithoutScrolling()

        val group = groupOf(item)
        if (group != null) {
            val groupKey = group.getAttribute(GROUP_ATTR)
            val itemKey = item.getAttribute(ITEM_ATTR)
            if (groupKey != null && itemKey != null) FocusMemory.remember(groupKey, itemKey)
        }

        when {
            direction == null -> centre(item, setOf(Axis.X, Axis.Y), scope)
            direction.isHorizontal -> centre(item, setOf(Axis.X), scope)
            else -> {
                centre(item, setOf(Axis.X), scope)
                group?.let { centre(it, setOf(Axis.Y), scope) }
            }
        }
    }

    /**
     * Puts focus somewhere sane when it has been lost.
     *
     * The real failure mode is Compose removing the focused node — changing season
     * re-renders the whole grid — which leaves `activeElement` on `<body>` and the
     * D-pad dead with no visible cause. Called before every move, so a lost ring
     * costs one keypress rather than the session.
     */
    fun ensureFocused(scope: Element): HTMLElement? {
        val active = document.activeElement as? HTMLElement
        if (active != null && active.hasAttribute(ITEM_ATTR) && scope.contains(active)) return active

        val restored = entryPoint(scope) ?: groupsIn(scope).firstNotNullOfOrNull { group ->
            val items = itemsOf(group)
            if (items.isEmpty()) null else remembered(group, items) ?: items.first()
        } ?: return null

        focus(restored, direction = null, scope = scope)
        return restored
    }

    /**
     * The screen's declared starting point, used only on a first visit.
     *
     * Skipped once its group has a remembered item, because coming back from an
     * episode should land on the tile you left rather than resetting to the hero.
     */
    private fun entryPoint(scope: Element): HTMLElement? {
        val entry = scope.querySelector("[$ENTRY_ATTR]") as? HTMLElement ?: return null
        if (!entry.isNavigable()) return null
        val group = groupOf(entry) ?: return entry
        return if (remembered(group, itemsOf(group)) != null) null else entry
    }
}
