package ge.dakalebi.ui.tv.input

import ge.dakalebi.core.Log
import ge.dakalebi.ui.tv.TvConfig
import ge.dakalebi.ui.tv.focus.Direction
import ge.dakalebi.ui.tv.focus.FOCUS_ATTR
import ge.dakalebi.ui.tv.focus.ITEM_ATTR
import ge.dakalebi.ui.tv.focus.SpatialNav
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.MutationObserver
import org.w3c.dom.MutationObserverInit
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * One layer of the input stack. A screen is a layer; so is a dialog, and so is the
 * player's control overlay.
 *
 * Every hook returns whether it consumed the press. An unconsumed direction key
 * falls through to the spatial engine scoped to this layer's [root] — which is
 * what makes a dialog a focus trap without a separate trap helper, because the
 * engine simply cannot see anything outside it.
 *
 * [onAnyKey] runs before everything else and consumes nothing. It exists for the
 * player: on a television the controls have to come back on *any* press, and
 * before the press is acted on, or the first button you push is spent revealing
 * the thing you were aiming at.
 */
class TvLayer(
    val key: String,
    val root: () -> Element?,
    /**
     * Whether an unhandled Back removes this layer.
     *
     * True for anything that sits *over* a screen — a dialog, a menu. False for a
     * layer that **is** the screen: the player pushes one, and popping it would
     * leave the player on screen with its input handling gone. A non-dismissible
     * layer passes an unhandled Back down to the layer beneath instead.
     */
    val dismissible: Boolean = true,
    val onBack: () -> Boolean = { false },
    val onAnyKey: (Key) -> Unit = {},
    /**
     * A direction press, and whether it is auto-repeat rather than a fresh press.
     *
     * The player is the reason the second argument exists. A tap and a hold are
     * different intentions — "skip a bit" against "take me somewhere" — and the only
     * thing that separates them is `KeyboardEvent.repeat`. Without it a held key is
     * indistinguishable from someone pressing very fast, and the player has to guess
     * with a timer.
     */
    val onDirection: (Direction, Boolean) -> Boolean = { _, _ -> false },
    val onSelect: (HTMLElement?) -> Boolean = { false },
    val onMedia: (MediaAction) -> Boolean = { false },
)

/** Handle for removing a layer again. */
class TvLayerHandle internal constructor(private val input: TvInput, private val layer: TvLayer) {
    fun dismiss() = input.pop(layer)
}

/**
 * The single keyboard entry point for the whole TV UI.
 *
 * **Exactly one `window` listener.** The web UI's `DismissOnEscape` adds one per
 * open dialog, which is fine when the only question is "did someone press
 * Escape". Here the questions are which layer owns a press, whether a direction
 * key means move-focus or seek, and where Back goes — and none of those can be
 * answered by listeners that cannot see each other. So layers register into a
 * stack instead, and the topmost one is asked first.
 */
class TvInput {
    private val layers = mutableListOf<TvLayer>()

    /** Set by the host bridge so the page can ask to be closed. See [back]. */
    var onExitRequested: (() -> Unit)? = null

    /**
     * Told that Back was pressed with nowhere left to go and no host to exit to.
     *
     * A callback rather than a toast call, because this is the input layer and it names
     * no UI: what "say something" means is the shell's decision, and only the shell
     * knows the wording. See [back].
     */
    var onTopLevelBack: (() -> Unit)? = null

    /** Whether the top-of-app hint is still on screen, so a repeat press does not stack it. */
    private var hintShown = false

    fun install(): () -> Unit {
        val handler: (Event) -> Unit = { raw -> (raw as? KeyboardEvent)?.let(::dispatch) }
        window.addEventListener("keydown", handler)

        // If the host lets its WebView consume Back as history-back, the page sees
        // a popstate and no keydown at all. So a popstate has to be read as a Back
        // press — but only when it is one, and "any popstate" is not that test.
        //
        // A same-document fragment navigation fires `popstate` too, ahead of the
        // `hashchange` that actually moves the router, so every forward move the app
        // makes announced itself as Back. It was not theoretical: measured on the
        // fixture, going to `#/settings` ran the Back ladder while the browse screen was
        // still mounted, which threw the ring into the navigation rail and opened it, and
        // the settings screen then arrived under a ring that had already been placed.
        //
        // The two are distinguishable by the one thing that differs: a forward
        // navigation *creates* an entry, and a new entry's state is null, while a
        // traversal lands on an entry this app has already stood on and marked. So mark
        // every entry on arrival, and let the mark be the answer.
        markHistoryEntry()
        val onPop: (Event) -> Unit = { event ->
            if (event.asDynamic().state == null) markHistoryEntry() else back()
        }
        window.addEventListener("popstate", onPop)

        // The documented seam for an Android host: `evaluateJavascript` this from
        // `onBackPressed`, because KEYCODE_BACK never reaches the page by itself.
        window.asDynamic().__tvShell = js("({})")
        window.asDynamic().__tvShell.onBack = { back() }

        val disposeGuardian = installFocusGuardian()

        return {
            window.removeEventListener("keydown", handler)
            window.removeEventListener("popstate", onPop)
            disposeGuardian()
        }
    }

    /**
     * Stamps the current history entry, so a later traversal onto it is recognisable.
     *
     * Written only when the entry has no state of its own, so this never overwrites
     * something a router put there. `HashRouter.replace` carries the existing state
     * across for the same reason — an entry that has been visited must not forget it.
     */
    private fun markHistoryEntry() {
        if (window.history.state == null) {
            window.history.replaceState(HISTORY_MARK, "", window.location.href)
        }
    }

    /**
     * Keeps exactly one item focused at all times.
     *
     * On a television the ring *is* the cursor, so a moment with nothing focused is a
     * moment the remote does nothing and the viewer cannot tell why. Two things take
     * the ring away, and neither is a real navigation:
     *
     * 1. **A recomposition removes or re-creates the focused element.** The player's
     *    clock ticks every second and its play glyph swaps on pause; each redraws the
     *    focused control, and if Compose detaches it the browser drops focus to
     *    `<body>`. This is why seeking and pausing "cleared" the ring — the act
     *    triggered a redraw, not the loss.
     * 2. **Content arrives after the screen mounts.** The browse screen places focus
     *    once, but the catalog loads asynchronously, so on a real load the tiles do
     *    not exist yet when that runs and nothing gets focused until the first press.
     *
     * The engine cannot prevent either — they happen inside Compose, on the frame
     * clock — so this restores focus after the fact, imperatively, which is why it
     * works where a `LaunchedEffect` would not. `focusout` handles (1): when the ring
     * leaves for nothing, it goes back, onto the very element it left where possible
     * so a seek keeps the bar. A `MutationObserver` handles (2): when the DOM changes
     * and nothing is focused, it lands the ring where the screen wants it.
     */
    private fun installFocusGuardian(): () -> Unit {
        // Focus left an element for nowhere (relatedTarget null). Settle a tick — a
        // recomposition may re-focus something itself — then restore if still lost,
        // preferring the element that left so an action does not move the ring.
        val onFocusOut: (Event) -> Unit = { event ->
            if (event.asDynamic().relatedTarget == null) {
                val left = event.target as? HTMLElement
                window.setTimeout({ restoreFocus(prefer = left) }, 0)
            }
        }
        window.addEventListener("focusout", onFocusOut)

        // The DOM changed. Coalesce a burst into one check and, if nothing is focused,
        // land the ring. `childList` only, because element add/remove and re-creation
        // are what strand focus; a pure text edit (were the clock to update its node in
        // place) is a `characterData` change and would not wake this. Whether Compose
        // edits the time text or replaces its node is up to the framework, so this may
        // still wake often — which is fine, because the check below is a no-op when
        // focus is intact.
        var scheduled = false
        val observer = MutationObserver { _, _ ->
            if (scheduled) return@MutationObserver
            scheduled = true
            window.setTimeout({ scheduled = false; restoreFocus(prefer = null) }, 0)
        }
        observer.observe(
            document.body ?: document,
            MutationObserverInit(
                childList = true,
                subtree = true,
                // The ring itself, because a recomposition can take it off an element
                // without touching the element: Compose HTML re-applies an element's
                // attributes by clearing them first, and the ring and the roving
                // `tabindex` are written outside Compose, so they go with the clear.
                // Nothing is added or removed, so `childList` never sees it — measured
                // on the settings screen, pressing an interface-size chip left the ring
                // nowhere at all. `focusout` covers this too, but only while the
                // document is frontmost, which is the one assumption this whole layer
                // refuses to make.
                //
                // The filter keeps the cost to what it has to be: the engine writes this
                // attribute twice per move, so a keypress adds one coalesced wake-up
                // whose check is a no-op when focus is intact.
                attributeFilter = arrayOf(FOCUS_ATTR),
            ),
        )

        return {
            window.removeEventListener("focusout", onFocusOut)
            observer.disconnect()
        }
    }

    /**
     * Puts the ring back if it has been lost, [prefer]ring a specific element.
     *
     * A no-op in the common case: if a real item still holds focus, nothing moved and
     * this returns at once, so even a frequently-waking observer costs one attribute
     * read per batch.
     */
    private fun restoreFocus(prefer: HTMLElement?) {
        val scope = layers.lastOrNull()?.root() ?: return
        val active = document.activeElement
        // Leave a focused item alone — and a focused text field too, because edit mode
        // deliberately puts the ring's wrapper and the focus on different elements; the
        // input holds no `data-tv-item`, and restoring off it would drop the keyboard
        // the instant it opened.
        if (active is HTMLElement && scope.contains(active) &&
            (active.hasAttribute(ITEM_ATTR) || isTextEntry(active))
        ) {
            // Focus is intact, but it may be sitting on a landing made before the
            // screen had any content to land on — the browse screen mounts with only
            // the navigation rail. This is where that gets finished, because it is the
            // one hook that fires when the missing content actually appears.
            SpatialNav.settleProvisional(scope)
            return
        }

        if (prefer != null && prefer.isConnected && scope.contains(prefer) &&
            prefer.hasAttribute(ITEM_ATTR)
        ) {
            SpatialNav.focus(prefer, direction = null, scope = scope)
            return
        }
        SpatialNav.ensureFocused(scope)
    }

    fun push(layer: TvLayer): TvLayerHandle {
        layers.add(layer)
        return TvLayerHandle(this, layer)
    }

    internal fun pop(layer: TvLayer) {
        layers.remove(layer)
    }

    /**
     * Back, from any source: a key, a popstate, or the host bridge.
     *
     * Asks the top layer, then pops it, and only considers leaving once the stack is
     * down to the bottom-most screen.
     *
     * What "leaving" means depends on whether there is anywhere to go, and the two cases
     * are not variations of one rule:
     *
     * - **A host is attached.** It exits, on the first press. Android TV's guidance is
     *   that Back leaves rather than being gated, and gating it behind a second press
     *   buys nothing when the first one can simply work.
     * - **No host**, which is every plain browser. There is nothing to close, so the
     *   press announces itself through [onTopLevelBack] instead. It used to write a
     *   console line and stop, which on a television is indistinguishable from a remote
     *   with a flat battery.
     */
    fun back() {
        // Top down, giving every layer a chance rather than only the topmost. A
        // layer that is a screen rather than an overlay declines and passes the
        // press down; see [TvLayer.dismissible].
        for (index in layers.indices.reversed()) {
            val layer = layers[index]
            if (layer.onBack()) {
                hintShown = false
                return
            }
            if (layer.dismissible && layers.size > 1) {
                pop(layer)
                hintShown = false
                return
            }
        }
        /*
         * A host that can actually close the app closes it on the first press. Android
         * TV's guidance is explicit that Back should leave rather than be gated, and a
         * viewer who is at the top of the app and presses Back has said what they want.
         * Press-twice belongs to the case below, where there is nothing to exit to.
         */
        onExitRequested?.let { it(); return }

        if (!hintShown) {
            hintShown = true
            window.setTimeout({ hintShown = false }, TvConfig.TOP_LEVEL_HINT_MS)
            /*
             * Say so on screen, not only in the console. In a browser there is no host
             * to close the page, so the first press has nothing to show for itself, and
             * a remote that appears to do nothing reads as a broken remote rather than
             * as a prompt. The hint is what makes the second press a choice.
             */
            onTopLevelBack?.invoke()
            Log.d("tv", "back at the top level; press again to exit")
            return
        }
        Log.d("tv", "no host to exit to")
    }

    /** Moves focus to a known place, used when a screen opens. */
    fun focus(item: HTMLElement, scope: Element) = SpatialNav.focus(item, direction = null, scope = scope)

    private fun dispatch(event: KeyboardEvent) {
        val layer = layers.lastOrNull() ?: return
        val key = keyOf(event)
        layer.onAnyKey(key)

        // Edit mode: a focused text field owns its own arrows and its space bar, so
        // typing works and the on-screen keyboard drives itself. Only Back is ours,
        // and it *leaves edit mode* rather than the screen: it blurs the field, which
        // drops the keyboard, and returns the ring to the `data-tv-item` wrapper the
        // field sits in — the highlighted-but-not-editing state from which the arrows
        // navigate again. Only a field with no such wrapper falls through to a plain
        // Back.
        val active = document.activeElement as? HTMLElement
        if (isTextEntry(active)) {
            if (key is Key.Back) {
                event.preventDefault()
                val wrapper = active?.closest("[$ITEM_ATTR]") as? HTMLElement
                if (wrapper != null && wrapper != active) wrapper.focus() else back()
            }
            return
        }

        // Browser and OS shortcuts stay theirs. Without this, Cmd+Left is a
        // ten-second seek instead of history-back.
        if (event.ctrlKey || event.metaKey || event.altKey) return

        val consumed = when (key) {
            is Key.Dir -> layer.onDirection(key.direction, event.repeat) ||
                moveFocus(layer, key.direction)
            Key.Select -> layer.onSelect(document.activeElement as? HTMLElement) || activate()
            Key.Back -> { back(); true }
            is Key.Media -> layer.onMedia(key.action)
            is Key.Other -> false
        }
        if (consumed) event.preventDefault()
    }

    private fun moveFocus(layer: TvLayer, direction: Direction): Boolean {
        val scope = layer.root() ?: return false
        val from = SpatialNav.ensureFocused(scope) ?: return false
        val target = SpatialNav.move(from, direction, scope) ?: return true
        SpatialNav.focus(target, direction, scope)
        return true
    }

    /**
     * Select falls through to a real click.
     *
     * So an `<a href>` navigates and a `<button onClick>` fires exactly as it does
     * with a mouse. Building a parallel activation path would mean every control
     * needs registering twice, and the two would drift.
     */
    private fun activate(): Boolean {
        val active = document.activeElement as? HTMLElement ?: return false
        if (!active.hasAttribute(ITEM_ATTR)) return false
        active.click()
        return true
    }

    private fun isTextEntry(node: Element?): Boolean {
        val tag = node?.tagName?.uppercase()
        if (tag == "INPUT" || tag == "TEXTAREA" || tag == "SELECT") return true
        return runCatching { node.asDynamic().isContentEditable == true }.getOrDefault(false)
    }

    private companion object {
        /**
         * What a visited history entry is marked with.
         *
         * Any non-null structured-cloneable value would do; the only thing asked of it
         * is that it not be null, because null is what the browser gives a brand new
         * entry and that is the whole discriminator.
         */
        const val HISTORY_MARK = "tv"
    }
}
