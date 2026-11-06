package ge.dakalebi.next.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import ge.dakalebi.next.tv.focus.FocusAxis
import ge.dakalebi.next.tv.focus.focusGroup
import ge.dakalebi.next.tv.focus.focusItem
import ge.dakalebi.next.tv.input.LocalTvInput
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.jetbrains.compose.web.dom.Button as HtmlButton

/** What a [TextField] holds. Picks the input type, the keyboard and what autofill offers. */
enum class TextFieldKind { Text, Email, Password }

/** A [TextField]'s or a [Scrubber]'s input element, set by `ref`. */
internal class InputRef {
    var element: HTMLInputElement? = null
}

/**
 * A text field with its label above it and its error under it.
 *
 * The text is at least 16px in every shell, so iOS never zooms into it. The error is
 * tied to the input (`aria-invalid`, `aria-describedby`), so a screen reader reads it
 * with the field and not only when it appears.
 *
 * [attrs] go on the box around the input, not on the input. On TV that box is the D-pad
 * stop: OK on it starts typing (this focuses the input), and Back stops typing and puts
 * the ring back on the box. The TV input layer does the second half.
 */
@Composable
fun TextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    kind: TextFieldKind = TextFieldKind.Text,
    error: String? = null,
    hint: String? = null,
    autocomplete: String? = when (kind) {
        TextFieldKind.Text -> null
        TextFieldKind.Email -> "email"
        TextFieldKind.Password -> "current-password"
    },
    attrs: ExtraAttrs? = null,
) {
    val inputId = rememberId("field")
    val hintId = "$inputId-hint"
    val errorId = "$inputId-error"
    val describedBy = listOfNotNull(hint?.let { hintId }, error?.let { errorId }).joinToString(" ")
    val input = remember { InputRef() }

    Div({ classes("field") }) {
        Label(forId = inputId, attrs = { classes("field-label") }) { Text(label) }

        Div({
            classes("field-control")
            if (error != null) classes("field-control--invalid")
            onClick { (document.getElementById(inputId) as? HTMLElement)?.focus() }
            attrs?.invoke(this)
        }) {
            Input(
                when (kind) {
                    TextFieldKind.Text -> InputType.Text
                    TextFieldKind.Email -> InputType.Email
                    TextFieldKind.Password -> InputType.Password
                },
            ) {
                id(inputId)
                classes("field-input")
                value(value)
                onInput { event -> onValueChange(event.value) }
                autocomplete?.let { attr("autocomplete", it) }
                if (kind != TextFieldKind.Text) {
                    // An address or a password is not prose: no capital, no correction.
                    attr("autocapitalize", "off")
                    attr("autocorrect", "off")
                    attr("spellcheck", "false")
                }
                if (kind == TextFieldKind.Email) attr("inputmode", "email")
                ref { element ->
                    input.element = element
                    onDispose { input.element = null }
                }
            }

            // Written directly, not as attributes above. When any attribute in that list
            // changes, Compose HTML removes them all and adds them back, and removing
            // `type` for that moment puts an email field's caret back at the start: the
            // next letter typed after the error appears would land in front.
            DisposableEffect(error != null, describedBy) {
                input.element?.let { element ->
                    if (error != null) {
                        element.setAttribute("aria-invalid", "true")
                    } else {
                        element.removeAttribute("aria-invalid")
                    }
                    if (describedBy.isNotEmpty()) {
                        element.setAttribute("aria-describedby", describedBy)
                    } else {
                        element.removeAttribute("aria-describedby")
                    }
                }
                onDispose {}
            }
        }

        if (hint != null) {
            P({ id(hintId); classes("field-hint") }) { Text(hint) }
        }
        if (error != null) {
            P({ id(errorId); classes("field-error") }) {
                Icon(Glyph.CircleAlert, small = true)
                Text(error)
            }
        }
    }
}

/**
 * An on/off setting: a row with its words on the left and the switch on the right.
 *
 * The whole row is one `<button role="switch">`, so the target is the row, a TV stop is
 * one press, and the state is `aria-checked`. It is named by [label] alone; the
 * [description] is read after it as a description.
 */
@Composable
fun Switch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    disabled: Boolean = false,
    attrs: ExtraAttrs? = null,
) {
    val labelId = rememberId("switch")
    val descriptionId = "$labelId-description"

    HtmlButton({
        type(ButtonType.Button)
        classes("switch")
        attr("role", "switch")
        attr("aria-checked", checked.toString())
        attr("aria-labelledby", labelId)
        if (description != null) attr("aria-describedby", descriptionId)
        if (disabled) disabled()
        onClick { onCheckedChange(!checked) }
        attrs?.invoke(this)
    }) {
        Span({ classes("switch-text") }) {
            Span({ id(labelId); classes("switch-label") }) { Text(label) }
            if (description != null) {
                Span({ id(descriptionId); classes("switch-description") }) { Text(description) }
            }
        }
        Span({ classes("switch-track"); attr("aria-hidden", "true") }) {
            Span({ classes("switch-knob") })
        }
    }
}

/**
 * One choice in a [Segmented] control. [lang] marks a label in another language than
 * the page, such as "English" on a Georgian page, so it is read out in its own voice.
 */
class SegmentOption<T>(val value: T, val label: String, val lang: String? = null)

/**
 * A choice of two to five options shown side by side, all visible at once: the
 * language, the TV interface size.
 *
 * A radio group. On the web the arrow keys move and select (the selected option is the
 * one Tab stop); on TV it is a row of D-pad stops filed under [tvKey], and OK selects.
 * The label above it names the group; with [showLabel] false it is only read out.
 */
@Composable
fun <T> Segmented(
    label: String,
    options: List<SegmentOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    tvKey: String = "segmented",
    showLabel: Boolean = true,
) {
    val tv = LocalTvInput.current != null
    val labelId = rememberId("segmented")

    Div({ classes("segmented-field") }) {
        Span({ id(labelId); classes(if (showLabel) "segmented-label" else "visually-hidden") }) { Text(label) }

        Div({
            classes("segmented")
            attr("role", "radiogroup")
            attr("aria-labelledby", labelId)
            if (tv) {
                focusGroup(tvKey, FocusAxis.X)
            } else {
                onKeyDown { event ->
                    val current = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
                    val next = when (event.key) {
                        "ArrowRight", "ArrowDown" -> (current + 1).mod(options.size)
                        "ArrowLeft", "ArrowUp" -> (current - 1).mod(options.size)
                        "Home" -> 0
                        "End" -> options.lastIndex
                        else -> return@onKeyDown
                    }
                    event.preventDefault()
                    onSelect(options[next].value)
                    // Selection follows focus, as in a native radio group.
                    (event.target as? HTMLElement)
                        ?.closest("[role=radiogroup]")
                        ?.querySelectorAll("[role=radio]")
                        ?.asList()
                        ?.getOrNull(next)
                        ?.let { (it as? HTMLElement)?.focus() }
                }
            }
        }) {
            options.forEachIndexed { index, option ->
                val isSelected = option.value == selected
                HtmlButton({
                    type(ButtonType.Button)
                    classes("segmented-option")
                    attr("role", "radio")
                    attr("aria-checked", isSelected.toString())
                    option.lang?.let { attr("lang", it) }
                    if (tv) {
                        focusItem("$tvKey-$index", entry = isSelected)
                    } else {
                        attr("tabindex", if (isSelected) "0" else "-1")
                    }
                    onClick { onSelect(option.value) }
                }) {
                    Text(option.label)
                }
            }
        }
    }
}
