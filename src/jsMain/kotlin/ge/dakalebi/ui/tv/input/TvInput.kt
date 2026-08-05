package ge.dakalebi.ui.tv.input

import ge.dakalebi.core.Log
import ge.dakalebi.ui.tv.focus.Direction
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

    private var pendingExit = false

    fun install(): () -> Unit {
        val handler: (Event) -> Unit = { raw -> (raw as? KeyboardEvent)?.let(::dispatch) }
        window.addEventListener("keydown", handler)

        // If the host lets its WebView consume Back as history-back, the page sees
        // a popstate and no keydown at all. Hash routing means an unexplained
        // popstate is a Back press.
        val onPop: (Event) -> Unit = { back() }
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
        observer.observe(document.body ?: document, MutationObserverInit(childList = true, subtree = true))

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
        if (active is HTMLElement && active.hasAttribute("data-tv-item") && scope.contains(active)) return

        if (prefer != null && prefer.isConnected && scope.contains(prefer) &&
            prefer.hasAttribute("data-tv-item")
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
     * Asks the top layer, then pops it, and only asks to leave once the stack is
     * down to the bottom-most screen. A web page cannot close an Activity, so the
     * last step is a request the host may ignore — which is why it is
     * press-twice-to-exit rather than a silent dead end.
     */
    fun back() {
        // Top down, giving every layer a chance rather than only the topmost. A
        // layer that is a screen rather than an overlay declines and passes the
        // press down; see [TvLayer.dismissible].
        for (index in layers.indices.reversed()) {
            val layer = layers[index]
            if (layer.onBack()) {
                pendingExit = false
                return
            }
            if (layer.dismissible && layers.size > 1) {
                pop(layer)
                pendingExit = false
                return
            }
        }
        if (!pendingExit) {
            pendingExit = true
            window.setTimeout({ pendingExit = false }, EXIT_WINDOW_MS)
            Log.d("tv", "back at the top level; press again to exit")
            return
        }
        onExitRequested?.invoke() ?: Log.d("tv", "no host to exit to")
    }

    /** Moves focus to a known place, used when a screen opens. */
    fun focus(item: HTMLElement, scope: Element) = SpatialNav.focus(item, direction = null, scope = scope)

    private fun dispatch(event: KeyboardEvent) {
        val layer = layers.lastOrNull() ?: return
        val key = keyOf(event)
        layer.onAnyKey(key)

        // A text field owns its own arrows and its own space bar. Only Back is
        // still ours, so a viewer who focused the email box can get out of it.
        if (isTextEntry(document.activeElement)) {
            if (key is Key.Back) {
                event.preventDefault()
                back()
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
        if (!active.hasAttribute("data-tv-item")) return false
        active.click()
        return true
    }

    private fun isTextEntry(node: Element?): Boolean {
        val tag = node?.tagName?.uppercase()
        if (tag == "INPUT" || tag == "TEXTAREA" || tag == "SELECT") return true
        return runCatching { node.asDynamic().isContentEditable == true }.getOrDefault(false)
    }

    private companion object {
        /** How long "press Back again to exit" stays armed. */
        const val EXIT_WINDOW_MS = 2000
    }
}
