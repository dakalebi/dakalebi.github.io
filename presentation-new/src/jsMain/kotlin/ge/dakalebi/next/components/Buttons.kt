package ge.dakalebi.next.components

import androidx.compose.runtime.Composable
import ge.dakalebi.i18n.caps
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.jetbrains.compose.web.dom.Button as HtmlButton

/**
 * How much a [Button] asks for attention. One [Primary] per view: it is the amber one.
 * [Quiet] has no fill until hovered. [Danger] is for what cannot be undone, and only
 * colours the words: the dialog in front of it is what makes it safe.
 */
enum class ButtonVariant { Primary, Secondary, Quiet, Danger }

/**
 * The one button. A real `<button type="button">`, or an `<a>` when it has an [href],
 * so the browser, a screen reader and the TV's OK key all treat it as what it is.
 *
 * The label is drawn in the label role, which is caps (Mtavruli in Georgian), so pass it
 * as written in the strings: this applies `caps` itself.
 *
 * [busy] shows a spinner in place of the icon and ignores presses until it clears, for
 * an action that is waiting on the network. [external] opens [href] in a new tab.
 */
@Composable
fun Button(
    label: String,
    onClick: () -> Unit = {},
    variant: ButtonVariant = ButtonVariant.Secondary,
    icon: Glyph? = null,
    href: String? = null,
    external: Boolean = false,
    disabled: Boolean = false,
    busy: Boolean = false,
    attrs: ExtraAttrs? = null,
) {
    val look: AttrsScope<HTMLElement>.() -> Unit = {
        classes(
            "button",
            when (variant) {
                ButtonVariant.Primary -> "button--primary"
                ButtonVariant.Secondary -> "button--secondary"
                ButtonVariant.Quiet -> "button--quiet"
                ButtonVariant.Danger -> "button--danger"
            },
        )
    }
    val content: @Composable () -> Unit = {
        when {
            busy -> Span({ classes("spinner", "spinner--inline"); attr("aria-hidden", "true") })
            icon != null -> Icon(icon)
        }
        Span({ classes("button-label") }) { Text(label.caps) }
    }

    if (href != null && !disabled) {
        A(href, {
            look()
            if (external) {
                target(ATarget.Blank)
                attr("rel", "noopener noreferrer")
            }
            onClick { onClick() }
            attrs?.invoke(this)
        }) { content() }
    } else {
        HtmlButton({
            type(ButtonType.Button)
            look()
            if (disabled) disabled()
            if (busy) attr("aria-busy", "true")
            onClick { if (!busy) onClick() }
            attrs?.invoke(this)
        }) { content() }
    }
}

/**
 * How an [IconButton] sits on its ground. [OnMedia] is for controls over video or
 * artwork: a dark, blurred disc that reads on any frame.
 */
enum class IconButtonVariant { Quiet, Secondary, Primary, OnMedia }

/**
 * A button that is only an icon. Always named: [label] is its accessible name, because
 * an icon alone is not one. The target is the full `--icon-button-size` (44px on the
 * web, 64px in drive) even when the glyph is smaller.
 *
 * [pressed] makes it a toggle (`aria-pressed`), for mute and the like.
 */
@Composable
fun IconButton(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    variant: IconButtonVariant = IconButtonVariant.Quiet,
    pressed: Boolean? = null,
    disabled: Boolean = false,
    attrs: ExtraAttrs? = null,
) {
    HtmlButton({
        type(ButtonType.Button)
        classes(
            "icon-button",
            when (variant) {
                IconButtonVariant.Quiet -> "icon-button--quiet"
                IconButtonVariant.Secondary -> "icon-button--secondary"
                IconButtonVariant.Primary -> "icon-button--primary"
                IconButtonVariant.OnMedia -> "icon-button--on-media"
            },
        )
        attr("aria-label", label)
        if (pressed != null) attr("aria-pressed", pressed.toString())
        if (disabled) disabled()
        onClick { onClick() }
        attrs?.invoke(this)
    }) {
        Icon(glyph)
    }
}

/**
 * A small pill action, for the row of things to do under the player: copy a link, mark
 * as watched, open elsewhere. The label is not caps: it is the label-s role.
 *
 * With [href] it is a link; [external] opens it in a new tab.
 */
@Composable
fun Chip(
    label: String,
    onClick: () -> Unit = {},
    icon: Glyph? = null,
    href: String? = null,
    external: Boolean = false,
    attrs: ExtraAttrs? = null,
) {
    val content: @Composable () -> Unit = {
        if (icon != null) Icon(icon, small = true)
        Span({ classes("chip-label") }) { Text(label) }
    }

    if (href != null) {
        A(href, {
            classes("chip")
            if (external) {
                target(ATarget.Blank)
                attr("rel", "noopener noreferrer")
            }
            onClick { onClick() }
            attrs?.invoke(this)
        }) { content() }
    } else {
        HtmlButton({
            type(ButtonType.Button)
            classes("chip")
            onClick { onClick() }
            attrs?.invoke(this)
        }) { content() }
    }
}
