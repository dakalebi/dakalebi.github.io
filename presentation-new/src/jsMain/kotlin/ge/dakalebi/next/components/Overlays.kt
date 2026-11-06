package ge.dakalebi.next.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.tv.focus.FocusAxis
import ge.dakalebi.next.tv.focus.ITEM_ATTR
import ge.dakalebi.next.tv.focus.SpatialNav
import ge.dakalebi.next.tv.focus.focusGroup
import ge.dakalebi.next.tv.focus.focusItem
import ge.dakalebi.next.tv.input.LocalTvInput
import ge.dakalebi.next.tv.input.TvInput
import ge.dakalebi.next.tv.input.TvLayer
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.events.SyntheticKeyboardEvent
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.jetbrains.compose.web.dom.Button as HtmlButton

/*
 * Dialogs, sheets and menus share one way of taking and giving back focus, and it differs
 * by shell:
 *
 * - **Web and drive.** The overlay moves focus into itself when it opens, keeps Tab
 *   inside, closes on Escape, stops the page behind it from scrolling, and puts focus
 *   back on whatever opened it when it closes. The keys are read on the overlay's own
 *   element, never on `window`, so two overlays cannot fight over a key.
 * - **TV.** The overlay pushes its own layer on the one [TvInput], which makes it the
 *   only thing the D-pad can reach and makes Back close it. When it closes, the ring goes
 *   back to the control that opened it.
 *
 * Overlays animate in only. Their exit is instant for now: animating out needs the node to
 * outlive its state, which is a transition helper this phase does not have yet.
 */

/** The element an overlay draws its panel into, set by `ref`. */
private class OverlayRefs {
    var panel: HTMLElement? = null
}

/** What the web shells can focus, for the Tab trap. */
private const val FOCUSABLE =
    "a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), " +
        "textarea:not([disabled]), [tabindex]:not([tabindex=\"-1\"])"

/**
 * Takes focus into an overlay when it opens and gives it back when it closes. See the
 * note at the top of this file.
 *
 * On the web the first focus goes to an element marked `data-autofocus` (a dialog's safe
 * choice), else to the panel itself, so a screen reader starts at the title and a stray
 * Enter does nothing.
 */
@Composable
private fun OverlayFocus(refs: OverlayRefs, layerKey: String, onDismiss: () -> Unit) {
    val input = LocalTvInput.current
    val dismiss by rememberUpdatedState(onDismiss)

    DisposableEffect(Unit) {
        // Read here, after the change that opened this is applied, not during composition:
        // a menu item that opens a dialog has given focus back to its trigger by now, and
        // the item itself is gone.
        val opener = document.activeElement as? HTMLElement
        val layer = input?.push(TvLayer(key = layerKey, root = { refs.panel }, onBack = { dismiss(); true }))

        val body = document.body
        val overflow = body?.style?.getPropertyValue("overflow").orEmpty()
        if (input == null) body?.style?.setProperty("overflow", "hidden")

        // A turn of the event loop, so the panel exists. `setTimeout` and not the frame
        // clock, which a hidden page stops.
        val land = window.setTimeout({
            val panel = refs.panel ?: return@setTimeout
            if (input != null) {
                SpatialNav.ensureFocused(panel)
            } else {
                (panel.querySelector("[data-autofocus]") as? HTMLElement ?: panel).focus()
            }
        }, 0)

        onDispose {
            window.clearTimeout(land)
            layer?.dismiss()
            if (input == null) body?.style?.setProperty("overflow", overflow)
            restoreFocus(opener, input)
        }
    }
}

/** Puts focus back on [element], with the TV ring when it is a D-pad stop. */
private fun restoreFocus(element: HTMLElement?, input: TvInput?) {
    if (element == null || !element.isConnected) return
    val body = document.body
    if (input != null && body != null && element.hasAttribute(ITEM_ATTR)) {
        input.focus(element, body)
    } else {
        element.focus()
    }
}

/** Escape closes; Tab and Shift+Tab wrap inside [panel]. Web shells only. */
private fun overlayKeys(event: SyntheticKeyboardEvent, panel: HTMLElement?, onDismiss: () -> Unit) {
    when (event.key) {
        "Escape" -> {
            event.preventDefault()
            onDismiss()
        }
        "Tab" -> {
            panel ?: return
            val items = panel.querySelectorAll(FOCUSABLE).asList().filterIsInstance<HTMLElement>()
            val active = document.activeElement
            when {
                items.isEmpty() -> event.preventDefault()
                event.shiftKey && (active == items.first() || active == panel) -> {
                    event.preventDefault()
                    items.last().focus()
                }
                !event.shiftKey && active == items.last() -> {
                    event.preventDefault()
                    items.first().focus()
                }
            }
        }
    }
}

/**
 * A modal question in the middle of the screen: a title, an optional line of [body] and
 * a row of [actions]. A tap on the scrim, Escape or Back dismisses it.
 *
 * [alert] makes it an `alertdialog`, for a question about something that cannot be
 * undone. Most callers want [ConfirmDialog] rather than this.
 *
 * On TV the actions row is one D-pad row, and each action needs [tvItem].
 */
@Composable
fun Dialog(
    title: String,
    onDismiss: () -> Unit,
    body: String? = null,
    alert: Boolean = false,
    actions: @Composable () -> Unit,
) {
    val refs = remember { OverlayRefs() }
    val titleId = rememberId("dialog")
    val bodyId = "$titleId-body"
    val actionsKey = "$titleId-actions"
    val tv = LocalTvInput.current != null

    OverlayFocus(refs, layerKey = "dialog", onDismiss = onDismiss)

    Div({
        classes("overlay", "overlay--center")
        if (!tv) onKeyDown { overlayKeys(it, refs.panel, onDismiss) }
    }) {
        Div({ classes("scrim"); onClick { onDismiss() } })
        Div({
            classes("dialog")
            attr("role", if (alert) "alertdialog" else "dialog")
            attr("aria-modal", "true")
            attr("aria-labelledby", titleId)
            if (body != null) attr("aria-describedby", bodyId)
            attr("tabindex", "-1")
            ref { element ->
                refs.panel = element
                onDispose { refs.panel = null }
            }
        }) {
            H2({ id(titleId); classes("dialog-title") }) { Text(title.caps) }
            if (body != null) P({ id(bodyId); classes("dialog-body") }) { Text(body) }
            Div({
                classes("dialog-actions")
                // A fresh key per dialog: the safe choice is where the ring starts every
                // time, not wherever it was left in the last dialog.
                if (tv) focusGroup(actionsKey, FocusAxis.X)
            }) {
                actions()
            }
        }
    }
}

/**
 * "Are you sure?" before something that is hard to undo: sign out, reset all progress.
 *
 * Cancel comes first and takes focus (and the TV ring), so a stray Enter or OK closes
 * the question instead of doing the thing. [danger] colours the confirm button.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    val tv = LocalTvInput.current != null

    Dialog(title = title, onDismiss = onDismiss, body = body, alert = true) {
        Button(
            S.cancel,
            onClick = onDismiss,
            variant = ButtonVariant.Secondary,
            attrs = {
                attr("data-autofocus", "")
                if (tv) focusItem("dialog-cancel", entry = true)
            },
        )
        Button(
            confirmLabel,
            onClick = onConfirm,
            variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary,
            attrs = tvItem("dialog-confirm"),
        )
    }
}

/**
 * A panel that rises from the bottom edge, for a short list of choices or a detail that
 * belongs to the screen behind it. It never covers the whole screen: the top of the page
 * stays visible above it, and its height is measured in `dvh` so the iOS toolbar does not
 * hide its last row. It pads its bottom by the safe inset.
 *
 * On the web it has a close button; on TV, Back closes it, and [content]'s controls need
 * [tvItem] (they are one vertical D-pad list).
 */
@Composable
fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val refs = remember { OverlayRefs() }
    val titleId = rememberId("sheet")
    val tv = LocalTvInput.current != null

    OverlayFocus(refs, layerKey = "sheet", onDismiss = onDismiss)

    Div({
        classes("overlay", "overlay--bottom")
        if (!tv) onKeyDown { overlayKeys(it, refs.panel, onDismiss) }
    }) {
        Div({ classes("scrim"); onClick { onDismiss() } })
        Div({
            classes("sheet")
            attr("role", "dialog")
            attr("aria-modal", "true")
            attr("aria-labelledby", titleId)
            attr("tabindex", "-1")
            ref { element ->
                refs.panel = element
                onDispose { refs.panel = null }
            }
        }) {
            Div({ classes("sheet-handle"); attr("aria-hidden", "true") })
            Div({ classes("sheet-head") }) {
                H2({ id(titleId); classes("sheet-title") }) { Text(title.caps) }
                if (!tv) IconButton(Glyph.X, S.dismiss, onClick = onDismiss)
            }
            Div({
                classes("sheet-body")
                if (tv) focusGroup("$titleId-body", FocusAxis.Y)
            }) {
                content()
            }
        }
    }
}

/**
 * One row of a [MenuButton]'s menu.
 *
 * [checked] makes it one of a set of choices (`menuitemradio`), such as a video quality,
 * and draws a check on the chosen one. [danger] is for a destructive row. [lang] marks a
 * label in another language than the page.
 */
class MenuEntry(
    val label: String,
    val onSelect: () -> Unit,
    val icon: Glyph? = null,
    val checked: Boolean? = null,
    val danger: Boolean = false,
    val lang: String? = null,
)

/** Which side of its trigger a menu opens on, where it opens as a popover. */
enum class MenuPlacement { Below, Above }

/**
 * A button that opens a short list of actions or choices.
 *
 * The same markup is three things, picked by CSS: a bottom sheet over a scrim on a phone
 * and in drive (reachable with a thumb), a popover by the trigger on a desktop, and a
 * popover the D-pad moves through on TV. [placement] is for the popover: the player's
 * quality menu opens [MenuPlacement.Above] its trigger.
 *
 * The trigger is an [IconButton] named [label], or, with [value], a chip that shows the
 * current choice ("720p") and is named "[label]: [value]".
 *
 * On the web the arrows, Home and End move through the rows, and Escape or Tab closes it.
 * On TV it is its own focus layer; Back closes it. Either way focus goes back to the
 * trigger.
 */
@Composable
fun MenuButton(
    label: String,
    entries: List<MenuEntry>,
    glyph: Glyph = Glyph.EllipsisVertical,
    value: String? = null,
    placement: MenuPlacement = MenuPlacement.Below,
    variant: IconButtonVariant = IconButtonVariant.Quiet,
    attrs: ExtraAttrs? = null,
) {
    var open by remember { mutableStateOf(false) }
    val triggerId = rememberId("menu")
    val menuId = "$triggerId-list"

    val trigger: ExtraAttrs = {
        id(triggerId)
        attr("aria-haspopup", "menu")
        attr("aria-expanded", open.toString())
        if (open) attr("aria-controls", menuId)
        attrs?.invoke(this)
    }

    Div({ classes("menu-anchor") }) {
        if (value == null) {
            IconButton(glyph, label, onClick = { open = !open }, variant = variant, attrs = trigger)
        } else {
            HtmlButton({
                type(ButtonType.Button)
                classes("chip", "chip--menu")
                attr("aria-label", "$label: $value")
                onClick { open = !open }
                trigger()
            }) {
                Span({ classes("chip-label") }) { Text(value) }
                Icon(Glyph.ChevronDown, small = true)
            }
        }

        if (open) {
            Menu(
                label = label,
                entries = entries,
                menuId = menuId,
                triggerId = triggerId,
                placement = placement,
                onClose = { open = false },
            )
        }
    }
}

@Composable
private fun Menu(
    label: String,
    entries: List<MenuEntry>,
    menuId: String,
    triggerId: String,
    placement: MenuPlacement,
    onClose: () -> Unit,
) {
    val input = LocalTvInput.current
    val refs = remember { OverlayRefs() }
    val close by rememberUpdatedState(onClose)
    // A popover grows away from the nearer screen edge: it lines up with the trigger's
    // start when the trigger is in the left half, else with its end. Read once, when it
    // opens; the trigger is already on the page.
    val alignStart = remember {
        document.getElementById(triggerId)?.getBoundingClientRect()
            ?.let { (it.left + it.right) / 2 < window.innerWidth / 2 } ?: false
    }

    DisposableEffect(Unit) {
        val layer = input?.push(TvLayer(key = "menu", root = { refs.panel }, onBack = { close(); true }))
        // Every opening starts on the chosen row, else the first, in every shell. Named
        // here rather than left to the D-pad's memory, which would land on whatever row
        // the last opening was left on.
        val land = window.setTimeout({
            val menu = refs.panel ?: return@setTimeout
            val items = menuItems(menu)
            val target = items.firstOrNull { it.getAttribute("aria-checked") == "true" } ?: items.firstOrNull()
            if (input != null) target?.let { input.focus(it, menu) } else target?.focus()
        }, 0)

        onDispose {
            window.clearTimeout(land)
            layer?.dismiss()
            restoreFocus(document.getElementById(triggerId) as? HTMLElement, input)
        }
    }

    Div({ classes("menu-scrim"); onClick { onClose() } })
    Div({
        id(menuId)
        classes("menu")
        if (placement == MenuPlacement.Above) classes("menu--above")
        if (alignStart) classes("menu--start")
        attr("role", "menu")
        attr("aria-labelledby", triggerId)
        ref { element ->
            refs.panel = element
            onDispose { refs.panel = null }
        }
        // On TV the panel is the layer's root and its one D-pad column.
        if (input != null) focusGroup(menuId, FocusAxis.Y) else onKeyDown { menuKeys(it, refs.panel, onClose) }
    }) {
        // The sheet's heading on a phone; hidden in the popover. Not read out: the trigger
        // already names the menu.
        P({ classes("menu-title"); attr("aria-hidden", "true") }) { Text(label.caps) }

        entries.forEachIndexed { index, entry ->
            HtmlButton({
                type(ButtonType.Button)
                classes("menu-item")
                if (entry.danger) classes("menu-item--danger")
                attr("role", if (entry.checked != null) "menuitemradio" else "menuitem")
                entry.checked?.let { attr("aria-checked", it.toString()) }
                entry.lang?.let { attr("lang", it) }
                if (input != null) focusItem("$menuId-$index", entry = entry.checked == true) else attr("tabindex", "-1")
                onClick {
                    onClose()
                    entry.onSelect()
                }
            }) {
                entry.icon?.let { Icon(it, small = true) }
                Span({ classes("menu-item-label") }) { Text(entry.label) }
                if (entry.checked == true) Icon(Glyph.Check, small = true)
            }
        }
    }
}

private fun menuItems(menu: HTMLElement): List<HTMLElement> =
    menu.querySelectorAll("[role^=\"menuitem\"]").asList().filterIsInstance<HTMLElement>()

/** The arrows, Home and End move; Escape and Tab close. Web shells only. */
private fun menuKeys(event: SyntheticKeyboardEvent, menu: HTMLElement?, onClose: () -> Unit) {
    val items = menu?.let(::menuItems).orEmpty()
    if (items.isEmpty()) return
    val index = items.indexOf(document.activeElement)

    val target = when (event.key) {
        "ArrowDown" -> items[(index + 1).mod(items.size)]
        "ArrowUp" -> if (index < 0) items.last() else items[(index - 1).mod(items.size)]
        "Home" -> items.first()
        "End" -> items.last()
        "Escape", "Tab" -> {
            event.preventDefault()
            onClose()
            return
        }
        else -> return
    }
    event.preventDefault()
    target.focus()
}
