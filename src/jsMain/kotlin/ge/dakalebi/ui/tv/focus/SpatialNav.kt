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

    private val focusListeners = mutableListOf<(HTMLElement) -> Unit>()

    /**
     * Subscribes to every focus change, after it has happened. Returns the way to stop.
     *
     * One hook at the one place focus can move, so a subscriber cannot miss a change and
     * there is nowhere else to keep in sync. **Not a DOM `focusin` listener**, which is
     * the obvious alternative and does not work here: this app draws its ring as an
     * attribute precisely because a television WebView is often not the frontmost
     * document, and in that state focus events are unreliable — measured, they do not
     * fire at all in a background page, while `document.activeElement` still moves.
     *
     * Two subscribers today. The navigation rail expands when the ring arrives in it and
     * collapses when it leaves; the player's episode shelf slides to whichever band now
     * holds the ring. Neither is something CSS can see: `:focus-within` would do both if
     * `:focus` could be trusted, and it cannot.
     *
     * **Keep subscribers cheap and keep them off the tiles.** This runs on the critical
     * path of every D-pad press. A subscriber that writes Compose state makes a keypress
     * recompose, which is the cost this whole file exists to avoid; both get away with it
     * because they recompose a handful of elements, and only on the presses that cross a
     * boundary they care about.
     */
    fun onFocusChanged(listener: (HTMLElement) -> Unit): () -> Unit {
        focusListeners += listener
        return { focusListeners -= listener }
    }

    /** Copied before iterating, so a subscriber unsubscribing in its own callback — the
     *  player's does, on the press that unmounts it — cannot mutate the list mid-walk. */
    private fun notifyFocus(item: HTMLElement) {
        focusListeners.toList().forEach { it(item) }
    }

    /**
     * Which screen the ring is on, so returning to it can land where it was left.
     *
     * Set by the shell, not read from the router: nothing in this package names a route
     * type, and keeping it that way is what lets the focus engine be reasoned about on
     * its own. A null disables screen memory rather than guessing a key.
     *
     * It must identify a screen, not a URL. Two episodes are two routes and one screen,
     * so keying by route string would give the player a fresh memory per episode and,
     * worse, let the browse screen inherit a key it never wrote. The shell derives it
     * from the route's *shape*.
     */
    var screenKey: String? = null

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

    /**
     * Records the content item to come back to when the ring next leaves a side rail.
     *
     * [move] sets this itself when a press walks into the rail, but a press is not the
     * only way in: Back jumps to the rail's active destination from anywhere, and that
     * path had no seat to leave, so the Right that followed fell through to geometry and
     * landed in whichever band the page happened to show. Handing the seat over here
     * makes Back-then-Right a round trip, which is what the two presses read as.
     */
    fun seatRailReturn(item: HTMLElement?) {
        railReturn = item?.takeIf { it.hasAttribute(ITEM_ATTR) }
    }

    /**
     * Whether the ring is parked on a landing chosen before the screen had anything to
     * land on.
     *
     * The browse screen mounts with only the navigation rail in the DOM: the catalog is
     * still loading, so there is no masthead, no shelf and no entry point. The focus
     * guardian will not leave the ring nowhere, so it lands in the only group there is —
     * and then never moved again, which is why arriving at the app showed an open rail
     * with Home lit and the first press appeared to do nothing. Marking that landing as
     * provisional lets [settleProvisional] finish the job once the content exists.
     *
     * It stays set across a settle, because a screen does not necessarily arrive all at
     * once and one correction is not enough. Browse draws its masthead only when there
     * is something to continue, and the watch progress that decides it resolves after
     * the episode list: measured, the screen exists for a beat as a season strip and a
     * grid with no masthead at all, the settle moved the ring to the season chip — the
     * only entry marker on the page — and cleared this flag, so the masthead arriving a
     * moment later found nothing left armed to claim the ring. Right then stepped along
     * the season strip, which is the reported "it focused the season chooser". Only
     * [focus] clears this, so the arming ends the instant the viewer moves the ring
     * themselves and never overrides a deliberate press.
     */
    private var provisional = false

    /**
     * The element the ring is on, so a recomposition that wipes it can be undone.
     *
     * Compose HTML applies an element's attributes by clearing what is there and
     * re-applying its own, and the ring is not one of its own: it and the roving
     * `tabindex` are written here, imperatively, precisely so a keypress does not
     * recompose. So a recomposition of the *focused* control strips both from a node
     * that is otherwise untouched — measured on the settings screen, pressing a size
     * chip left the ring nowhere and the browser's focus on `<body>`, from a node that
     * was still the same node.
     *
     * Every other way the ring is lost destroys the element, and [landingSpot] is the
     * right answer for those. This one does not, and landing somewhere sensible would
     * be the wrong answer twice over: nothing moved, so the ring should not either.
     */
    private var lastFocused: HTMLElement? = null

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

    internal fun axisOf(group: HTMLElement): FocusAxis =
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
     *
     * This answers the question for a group's *axis* only. [mayLeaveChrome] answers it
     * for a group's *position*, and both have to say yes.
     */
    private fun mayLeave(axis: FocusAxis, direction: Direction): Boolean = when {
        !direction.isHorizontal -> true
        axis == FocusAxis.Y -> true
        else -> direction == Direction.Left
    }

    /**
     * Whether a press may leave chrome — in this app, the navigation rail.
     *
     * [mayLeave] reasons about axes, which is enough for a group that scrolls with the
     * page. Chrome is `position: fixed`, so it does not: the content slides behind it
     * while it stays put, and every one of its edges therefore borders whatever happens
     * to be scrolled there at that moment. That is why the rail's boundaries were not
     * merely wrong but *intermittent* — the same press left the rail or did not,
     * depending on where the page behind it sat.
     *
     * Two walls, and each mirrors a rule that already existed on the other side:
     *
     * - **Vertically, never.** A rail sits *beside* the page, not in its vertical
     *   stack, so Up from the first item and Down from the last are ends of a list.
     *   [leaveGroup] already refuses to *land* a vertical press on chrome; without this
     *   the same press could still *depart* from it, which is the identical mistake
     *   read backwards.
     * - **Leftward, never.** [mayLeave]'s whole leftward argument is that the rail lies
     *   to the left of everything. From inside the rail that argument is spent: there is
     *   nothing further left, and letting the press through sent the ring back to the
     *   content it had just come from. Rightward still leaves, which is how you get out.
     *
     * Chrome is found by asking the layout ([isChrome]), not by naming the rail, so a
     * second pinned surface gets the same treatment without editing this.
     *
     * The leftward wall assumes the rail is on the left edge, which is true of this
     * app's one rail and is the same assumption [mayLeave] already makes. A right-hand
     * rail would need this to ask which edge the group is flush against instead.
     */
    private fun mayLeaveChrome(group: HTMLElement, direction: Direction): Boolean =
        !isChrome(group) || direction == Direction.Right

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
        if (!mayLeaveChrome(group, direction)) return null

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
        val clips = ClipCache()
        val candidates = scope.querySelectorAll("[$ITEM_ATTR]").asList()
            .filterIsInstance<HTMLElement>()
            .mapNotNull { element ->
                val group = groupOf(element) ?: return@mapNotNull null
                if (group == current) return@mapNotNull null
                // Chrome is beside the page, never below it. The navigation rail is
                // fixed and spans the panel's full height, so its lower item sits under
                // the last row of any screen and a plain geometric Down walked straight
                // into it — measured on settings: Down from the sign-out button left the
                // screen entirely and opened the rail. Down means the next band of this
                // screen, and when there is none it means a wall. Horizontal presses are
                // untouched, because sideways *is* how the rail is reached.
                if (!direction.isHorizontal && isChrome(group)) return@mapNotNull null
                // Measured as the viewer sees it, not as the element claims. See
                // [visibleBox]: an item scrolled out of its own rail reports a full
                // rectangle, and believing it is what sent the ring off screen.
                //
                // A vertical press is the exception, and it hands `visibleBox` the axis
                // to forgive: the next band down is often entirely below the fold, and
                // scrolling it into view is what Down *is*. Sideways keeps the strict
                // reading, because nothing off screen is ever to the right of the ring.
                val reachable = if (direction.isHorizontal) null else Axis.Y
                val box = element.visibleBox(scope, clips, reachable) ?: return@mapNotNull null
                val distance = along(from, box, direction) ?: return@mapNotNull null
                Candidate(box, group, distance, cross(from, box, direction))
            }
        if (candidates.isEmpty()) return null

        // The nearest *band*, then the nearest item in it — not the single best
        // scoring item. Scoring items directly is what the first attempt did, and
        // the cross-axis penalty that makes a grid step along its own row before
        // dropping a row is far too strong once bands are stacked: measured on the
        // browse screen, Down from the hero's right-hand button skipped the continue
        // rail *and* the season chips to land on a grid tile that happened to sit
        // underneath it. Down means "the next band", and no weighting expresses that.
        //
        // Ties are then broken by how far off the travel line the band sits, and that
        // second key is not a refinement — it is the answer to a question the first key
        // cannot express. Every content band on this screen starts at the same left
        // edge, so a sideways press out of the navigation rail finds *every* band at an
        // identical distance. Document order used to settle it, which meant leaving the
        // rail always landed in the topmost band no matter where on the page the viewer
        // was. Vertical closeness settles it correctly instead: the ring comes out of
        // the rail beside what it was next to.
        val nearestAlong = candidates.minOf { it.along }
        val nearest = candidates
            .filter { it.along <= nearestAlong + BAND_TIE }
            .groupBy { it.group }
            .minByOrNull { (_, items) -> items.minOf { it.cross } }
            ?.key
            ?: return null

        val items = itemsOf(nearest)

        // Stepping vertically into a stacked list is the one arrival that means
        // something precise: **the next row**. Nothing else can express it. Memory
        // cannot — a `Y` band the ring is stepping into from a control nested inside it
        // was never "left", so recalling where it was last skips whatever sits between.
        // A declared entry cannot either, for the same reason. And the cross-axis
        // measure below actively gets it wrong here, because rows in a settings list are
        // different widths: measured, Down from the language segment picked the wide
        // sign-out button over the narrow autoplay switch directly beneath it, purely
        // because a wide button's centre is nearer. So a stacked arrival is decided by
        // distance travelled, and every other arrival keeps the rules it had.
        val stacked = !direction.isHorizontal && axisOf(nearest) == FocusAxis.Y
        if (!stacked) {
            // A group may name the item to land on when the ring arrives fresh, with no
            // memory yet — the true "next" episode at the head of the up-next rail, the
            // episode just watched at the tail of the previous rail, the current season
            // on the season strip. It carries [ENTRY_ATTR], and it is preferred below
            // over the geometric guess, which otherwise lands wherever happens to sit
            // under the cursor: mid-rail, when the press came from a centred button.
            val entry = items.firstOrNull { it.hasAttribute(ENTRY_ATTR) }

            // Chrome inverts the usual order, and only chrome does. A shelf is something
            // you were reading, so returning to it should resume where you stopped, and
            // memory has to win. The navigation rail is not read: it is a menu, and its
            // declared entry is the destination you are currently on. Landing anywhere
            // else means one stray Enter changes screen — and with memory in charge it
            // would keep landing on the last place you visited, so a trip to Settings
            // would poison every arrival afterwards.
            if (isChrome(nearest)) entry?.let { return it }

            remembered(nearest, items)?.let { return it }

            // Memory still wins for everything else, so this only steers the *first*
            // arrival.
            entry?.let { return it }
        }

        return candidates
            .filter { it.group == nearest }
            .minByOrNull { if (stacked) it.along else it.cross }
            ?.box?.el
    }

    /**
     * How close two bands' distances have to be to count as the same distance.
     *
     * Bands that share a content edge tie exactly in principle and by a fraction of a
     * pixel in practice, because `clamp()` sizing and fractional device ratios round
     * differently per element. Eight pixels is wide enough to absorb that and far
     * narrower than the gap between two stacked bands, which is measured in tens.
     */
    private const val BAND_TIE = 8.0

    /** One reachable item, with the band that owns it and how far away it is. */
    private class Candidate(
        val box: Box,
        val group: HTMLElement,
        val along: Double,
        val cross: Double,
    )

    /**
     * Sends the ring into [group], landing where that group was last left.
     *
     * For the caller that knows which group it wants and cannot get there by geometry:
     * the player's shelf, whose bands are clipped out of view one at a time, so Up from
     * the visible band has nothing on screen above it to aim at. Every other move should
     * still go through [move] — a screen that names its own targets has stopped being
     * spatially navigable, and this exists for the one case where the alternative is a
     * dead end rather than a different route.
     *
     * Falls back to the group's declared entry point, then to its first item.
     */
    fun focusInGroup(group: HTMLElement, scope: Element): Boolean {
        val items = itemsOf(group)
        val target = remembered(group, items)
            ?: items.firstOrNull { it.hasAttribute(ENTRY_ATTR) }
            ?: items.firstOrNull()
            ?: return false
        focus(target, direction = null, scope = scope)
        return true
    }

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
     * A horizontal move centres the item in its own rail and leaves the page alone —
     * unless the item is not on screen at all, which is the one case where leaving the
     * page alone means leaving the ring invisible. See [centreXRevealY].
     * A vertical move centres the group when it is a single row — so a rail's heading
     * stays visible above the focused row — and the focused item when the group has
     * several rows, which a season grid does. See [verticalTarget].
     */
    fun focus(item: HTMLElement, direction: Direction?, scope: Element) {
        // Any landing chosen deliberately is final; only the fallback in
        // [ensureFocused] marks itself provisional, and it does so after calling this.
        provisional = false
        val group = mark(item, scope)

        when {
            direction == null -> centre(item, setOf(Axis.X, Axis.Y), scope)
            direction.isHorizontal -> centreXRevealY(item, scope)
            // A vertical move centres the item on X (in its rail) and the band-or-item on
            // Y (in the page). `centreAxes` reads both rectangles before either scroll
            // write, so the vertical target is measured before the X write dirties layout
            // — one reflow instead of two. See [verticalTarget]/[centreAxes].
            group != null -> centreAxes(item, verticalTarget(item, group), scope)
            else -> centre(item, setOf(Axis.X), scope)
        }

        // Last, so a subscriber reading geometry sees the settled position.
        notifyFocus(item)
    }

    /**
     * Puts the ring and the roving `tabindex` on [item] and records where it is,
     * without scrolling anything. Returns the item's group, which the caller needs
     * anyway and which is one `closest()` call.
     */
    private fun mark(item: HTMLElement, scope: Element): HTMLElement? {
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
        lastFocused = item

        val group = groupOf(item)
        if (group != null) {
            val groupKey = group.getAttribute(GROUP_ATTR)
            val itemKey = item.getAttribute(ITEM_ATTR)
            if (groupKey != null && itemKey != null) {
                FocusMemory.remember(groupKey, itemKey)

                /*
                 * Per-group memory alone cannot answer "where was I on this screen".
                 * Every group on the browse screen remembers its own last item quite
                 * happily; what was missing is which of them the ring was actually in,
                 * so coming back from an episode consulted the hero's memory and landed
                 * on the hero. Recording the group as well is what makes the return trip
                 * land on the tile you launched from.
                 *
                 * Chrome is skipped deliberately. The navigation rail is not part of any
                 * screen — it is the same rail on all of them — so letting it write here
                 * would mean a screen last touched through the rail remembered the rail
                 * as its own last place, and returning to it would open on the menu.
                 */
                screenKey?.takeIf { !isChrome(group) }
                    ?.let { FocusMemory.rememberScreen(it, groupKey, itemKey) }
            }
        }
        return group
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

        // The element is still there and still navigable, so nothing was lost but the
        // two attributes a recomposition cleared off it. Put them back where they were
        // rather than choosing a new home for the ring. See [lastFocused].
        lastFocused
            ?.takeIf {
                it.isConnected && scope.contains(it) &&
                    it.hasAttribute(ITEM_ATTR) && it.isNavigable()
            }
            ?.let { mark(it, scope); notifyFocus(it); return it }

        val restored = landingSpot(scope) ?: return null
        focus(restored, direction = null, scope = scope)
        // Set after focusing, which clears the flag: a landing in the chrome is a
        // placeholder for content that has not arrived, not a decision.
        provisional = isChrome(groupOf(restored))
        return restored
    }

    /**
     * Moves a provisional landing onto the real one, once the screen has one.
     *
     * Called from the focus guardian on every DOM change, and a no-op in every case but
     * the one it exists for, so the cost is a flag read per batch. Returns whether it
     * moved the ring.
     *
     * The target is the screen's *declared* entry marker rather than a full
     * [landingSpot], and that is not a shortcut — it is what makes a second settle
     * possible at all. [mark] writes every landing into screen memory, including one
     * this function made, so `landingSpot` consults [screenSpot] and hands straight back
     * the season chip the ring is already sitting on. An automatic landing is not
     * somewhere the viewer *was*, so the memory it leaves has no business outranking the
     * entry point the screen declares. [screenEntry] also excludes chrome by
     * construction, which is why no check for it is needed here.
     *
     * The flag is left armed afterwards; see [provisional] for why one correction is not
     * enough.
     */
    fun settleProvisional(scope: Element): Boolean {
        if (!provisional) return false
        val target = screenEntry(scope) ?: return false
        if (target == document.activeElement) return false
        focus(target, direction = null, scope = scope)
        provisional = true
        return true
    }

    /**
     * Where the ring belongs when it has none: the screen's declared entry point, else
     * the best remembered or first item of a group — **content before chrome**.
     *
     * The preference is the whole point. Groups are searched in document order, and the
     * navigation rail is written first so that one Left press reaches it from anywhere,
     * which meant a plain document-order scan handed it the ring by default. The rail is
     * a way to somewhere else, never a destination: a viewer arriving at the app wants
     * the show, and a viewer whose focus was stranded by a re-render wants the shelf they
     * were reading. Chrome is only the answer when there is genuinely nothing else, which
     * on this app means the catalog has not loaded yet — and [settleProvisional] then
     * comes back for it.
     */
    private fun landingSpot(scope: Element): HTMLElement? {
        screenSpot(scope)?.let { return it }
        entryPoint(scope)?.let { return it }
        val groups = groupsIn(scope)
        fun pick(group: HTMLElement): HTMLElement? {
            val items = itemsOf(group)
            return if (items.isEmpty()) null else remembered(group, items) ?: items.first()
        }
        return groups.filterNot { isChrome(it) }.firstNotNullOfOrNull(::pick)
            ?: groups.firstNotNullOfOrNull(::pick)
    }

    /**
     * Whether a group is chrome pinned over the page rather than part of it.
     *
     * `position: fixed` is the honest test and not a proxy for "is it the nav rail":
     * anything pinned to the viewport is by construction a thing that sits *beside* the
     * content, since it does not travel with it. Asking the layout keeps this from
     * becoming a list of group keys to keep in step with the screens.
     */
    private fun isChrome(group: HTMLElement?): Boolean = group?.isFixed() == true

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
        val entry = screenEntry(scope) ?: return false
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
        val entry = screenEntry(scope) ?: return null
        val group = groupOf(entry) ?: return entry
        return if (remembered(group, itemsOf(group)) != null) null else entry
    }

    /**
     * Where this screen was when the ring last left it, if it is still there.
     *
     * Tried before the declared entry point, and that order is the entire fix for
     * "coming back from an episode throws you to the top of the page". The entry point
     * only steps aside when *its own* group has a memory, so returning to browse from
     * the player asked the hero — which had one — and landed on the hero's play button
     * with the page scrolled to the top. The grid's memory was intact the whole time and
     * simply never consulted, because nothing recorded which group the screen was in.
     *
     * Both halves are verified against the live DOM rather than trusted: a season change
     * rebuilds the grid, and a remembered tile can be gone. When either is missing this
     * returns null and the older path runs, so the worst case is the behaviour that was
     * there before.
     */
    private fun screenSpot(scope: Element): HTMLElement? {
        val (groupKey, itemKey) = screenKey?.let(FocusMemory::recallScreen) ?: return null
        val group = groupsIn(scope)
            .firstOrNull { it.getAttribute(GROUP_ATTR) == groupKey } ?: return null
        return itemsOf(group).firstOrNull { it.getAttribute(ITEM_ATTR) == itemKey }
    }

    /**
     * The screen's own entry marker, ignoring any that chrome declares.
     *
     * [ENTRY_ATTR] answers two different questions, and the difference is the whole
     * reason this exists. Asked of a *group* — in [leaveGroup] — it means "land here
     * when the ring arrives fresh", and the navigation rail wants that: arriving at it
     * should put the ring on the destination you are actually on. Asked of a *screen* —
     * here, for [entryPoint] and [focusEntry] — it means "this is where the screen
     * begins", and the rail must never answer that. A plain `querySelector` cannot tell
     * the two apart: it takes the first match in document order, and `TvApp` renders the
     * rail before the screen, so the rail would win both. A cold load would open on the
     * navigation rail instead of the hero, and Back-to-top would jump sideways into it.
     *
     * Skipping chrome is not a special case bolted on for the rail. It is the rule
     * [landingSpot] already states in prose — the rail is a way to somewhere else, never
     * a destination — applied to the one lookup that was still ignoring it.
     */
    private fun screenEntry(scope: Element): HTMLElement? =
        scope.querySelectorAll("[$ENTRY_ATTR]").asList()
            .filterIsInstance<HTMLElement>()
            .firstOrNull { !isChrome(groupOf(it)) && it.isNavigable() }
}
