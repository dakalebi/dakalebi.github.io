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

    /**
     * Told about every focus change, after it has happened.
     *
     * Exactly one hook, at the one place focus can move, so a subscriber cannot miss
     * a change and there is nowhere else to keep in sync. The navigation rail is the
     * only subscriber: it has to expand when the ring arrives in it and collapse when
     * the ring leaves, and neither event is something CSS can see — `:focus-within`
     * would do it, but the ring is an attribute here precisely because `:focus` is
     * unreliable when the document is not frontmost.
     *
     * **Keep subscribers cheap and keep them off the tiles.** This runs on the
     * critical path of every D-pad press. A subscriber that writes Compose state
     * makes a keypress recompose, which is the cost this whole file exists to avoid;
     * the rail gets away with it because it recomposes a rail of four items, and only
     * on the two presses that cross its boundary.
     */
    var onFocusChanged: ((HTMLElement) -> Unit)? = null

    /**
     * The content item the ring left when it last stepped into a side rail (the nav).
     *
     * A rail is an excursion, not a destination: Left opens it, and the natural thing on
     * the way out is to land back where you were rather than on whichever band geometry
     * picks. Geometry picks wrong here for a concrete reason — every content band shares
     * the same left edge, so the "nearest band" distances tie and DOM order breaks the
     * tie, which drops the ring into the topmost band while the page is still scrolled to
     * where you actually were. Remembering the seat fixes both halves at once: the ring
     * returns to it, and because [focus] re-centres it, so does the scroll.
     */
    private var railReturn: HTMLElement? = null

    /** Every group inside [scope], in DOM order. */
    fun groupsIn(scope: Element): List<HTMLElement> =
        scope.querySelectorAll("[$GROUP_ATTR]").asList()
            .filterIsInstance<HTMLElement>()

    /** The navigable items of one group, excluding any nested group's items. */
    fun itemsOf(group: HTMLElement): List<HTMLElement> =
        group.querySelectorAll("[$ITEM_ATTR]").asList()
            .filterIsInstance<HTMLElement>()
            .filter { it.closest("[$GROUP_ATTR]") == group && it.isNavigable() }

    /**
     * The boxes of one group's own navigable items, each rectangle read exactly once.
     *
     * The measuring counterpart of [itemsOf]: the in-group move path needs geometry, and
     * folding the visibility gate into a single [navigableBox] read halves the
     * `getBoundingClientRect` calls a keypress forces over a large grid.
     */
    private fun navigableBoxesOf(group: HTMLElement): List<Box> =
        group.querySelectorAll("[$ITEM_ATTR]").asList()
            .filterIsInstance<HTMLElement>()
            .filter { it.closest("[$GROUP_ATTR]") == group }
            .mapNotNull { it.navigableBox() }

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
     * **Rightward presses may only leave a `Y` group**, where sideways was never the
     * group's own axis. Letting Right escape a rail or a grid produces the worst bug
     * in a D-pad UI: reaching the last tile of a row and being teleported into an
     * unrelated section. Running out of rail rightward should feel like a wall,
     * because that is what it is — there is nothing over there.
     *
     * **Leftward is different, and it is asymmetric on purpose.** The navigation rail
     * lives off the left edge of every screen, so "nothing left of here" is false in a
     * way it is not on the right: from the first tile of any shelf there is exactly one
     * sensible destination, and it is the rail. That is the whole interaction model of
     * a left-navigation app — Google writes the path to Settings as "scroll to the
     * left, then down" — and a wall on the left would make the rail unreachable from
     * anything except a `Y` group.
     *
     * The asymmetry costs nothing on the right, where the teleport bug actually lived.
     * See [leaveGroup] for why a leftward escape also has to drop the row-overlap
     * requirement that every other horizontal move keeps.
     */
    private fun mayLeave(axis: FocusAxis, direction: Direction): Boolean = when {
        !direction.isHorizontal -> true
        axis == FocusAxis.Y -> true
        else -> direction == Direction.Left
    }

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
            bestCandidate(fromBox, navigableBoxesOf(group), direction)?.let { return it }
        }
        if (!mayLeave(axis, direction)) return null

        // Leaving a side rail (a `Y` group) horizontally: go back to the seat the ring
        // left when it stepped in, not to wherever geometry lands. See [railReturn].
        if (direction.isHorizontal && axis == FocusAxis.Y) {
            railReturn
                ?.takeIf {
                    it.isConnected && scope.contains(it) &&
                        groupOf(it) != group && it.hasAttribute(ITEM_ATTR)
                }
                ?.let { railReturn = null; return it }
        }

        val target = leaveGroup(fromBox, group, direction, scope) ?: return null

        // Stepping into a side rail (a `Y` group) horizontally: remember the seat, so
        // leaving it returns here. Only horizontal — a vertical Up/Down into a `Y` list is
        // ordinary stacking, not an excursion to come back from.
        if (direction.isHorizontal && groupOf(target)?.let(::axisOf) == FocusAxis.Y) {
            railReturn = from
        }
        return target
    }

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
     *
     * **A horizontal escape does not require row overlap, and once did.** Overlap stops
     * a sideways press landing in a band above or below, which is exactly right *inside*
     * a group — [bestCandidate] still enforces it, and that is where it earns its keep.
     * Applying it here as well turned out to be belt over braces, and the belt did
     * damage: a navigation rail's *container* spans the screen height but its items do
     * not, being 40px tall and clustered top and bottom, so no rail item ever shares a
     * row with a shelf in the middle of the screen. With the requirement in place the
     * rail could not be entered from a shelf, and — mirrored — could not be left again.
     *
     * Dropping it is safe because [mayLeave] already guards the case overlap was
     * protecting. The bug on record ("Right at the end of a rail jumped into the grid")
     * was a press escaping an `X` group, and `mayLeave` refuses that outright, whatever
     * the geometry says. What reaches this line horizontally is a press leaving a `Y`
     * group, or a leftward press with only the rail to its left.
     */
    private fun leaveGroup(
        from: Box,
        current: HTMLElement,
        direction: Direction,
        scope: Element,
    ): HTMLElement? {
        val candidates = scope.querySelectorAll("[$ITEM_ATTR]").asList()
            .filterIsInstance<HTMLElement>()
            .mapNotNull { element ->
                if (groupOf(element) == current) return@mapNotNull null
                val group = groupOf(element) ?: return@mapNotNull null
                // One rectangle read per item, folding in the visibility gate.
                val box = element.navigableBox() ?: return@mapNotNull null
                val distance = along(from, box, direction) ?: return@mapNotNull null
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

        // A group may name the item to land on when the ring arrives fresh, with no
        // memory yet — the true "next" episode at the head of the up-next rail, the
        // episode just watched at the tail of the previous rail, the current season on
        // the season strip. It carries [ENTRY_ATTR], and it is preferred here over the
        // geometric guess, which otherwise lands wherever happens to sit under the
        // cursor: mid-rail, when the press came from a centred button. Memory still wins
        // over it, so this only steers the *first* arrival.
        itemsOf(nearest).firstOrNull { it.hasAttribute(ENTRY_ATTR) }?.let { return it }

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
     * A horizontal move centres the item in its own rail and leaves the page alone.
     * A vertical move centres the group when it fits the viewport — so a rail's
     * heading stays visible above the focused row — and the item when the group is
     * taller than the viewport, which a full season grid is. See [verticalTarget].
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
            // A vertical move centres the item on X (in its rail) and the band-or-item on
            // Y (in the page). `centreAxes` reads both rectangles before either scroll
            // write, so the vertical target is measured before the X write dirties layout
            // — one reflow instead of two. See [verticalTarget]/[centreAxes].
            group != null -> centreAxes(item, verticalTarget(item, group, scope), scope)
            else -> centre(item, setOf(Axis.X), scope)
        }

        // Last, so a subscriber reading geometry sees the settled position.
        onFocusChanged?.invoke(item)
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
     * Jumps the ring to the screen's declared entry point, ignoring memory.
     *
     * Unlike [entryPoint] this does not defer to a remembered item: it is for an explicit
     * "take me back to the top" action — the browse screen's back-to-top control — where
     * leaving where you were is the whole point. Focusing with no direction centres on
     * both axes, and because the entry marker sits at the top of the screen, that scrolls
     * the page up to it.
     */
    fun focusEntry(scope: Element): Boolean {
        val entry = scope.querySelector("[$ENTRY_ATTR]") as? HTMLElement ?: return false
        if (!entry.isNavigable()) return false
        focus(entry, direction = null, scope = scope)
        // "Back to top" means the very top, not the masthead merely centred. `focus`
        // above centres the entry, which leaves a sliver of scroll above it; pin the
        // vertical scroller to zero so the page is actually at its head.
        scrollableAncestor(entry, Axis.Y, scope)?.scrollTop = 0.0
        return true
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
