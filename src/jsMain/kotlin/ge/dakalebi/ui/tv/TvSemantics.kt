package ge.dakalebi.ui.tv

import org.jetbrains.compose.web.attributes.AttrsScope
import org.w3c.dom.Element

/*
 * What a control *is*, for anything that is not already a semantic element.
 *
 * The TV UI builds most of its controls out of `Div`, and for a reason: a `<button>`
 * carries a user-agent stylesheet, its own focus ring and its own activation
 * behaviour, all three of which fight a surface where the ring is the cursor and the
 * input layer decides what a press means. What it does *not* carry over is the
 * semantics, so a screen reader announces a row of clickable divs as nothing at all.
 *
 * These two helpers put the semantics back in one place. They exist as helpers rather
 * than as an `attr("role", …)` at each site because there are eleven such sites, and a
 * rule applied eleven times by hand is a rule that will be applied ten times next.
 *
 * Deliberately separate from `focusGroup`/`focusItem`, which describe how the D-pad
 * *moves*. Those apply equally to an `<a href>`, which needs no role at all.
 */

/**
 * An activatable control: a button in everything but tag name.
 *
 * [disabled] is `aria-disabled` rather than the `disabled` attribute, because a `Div`
 * has no such attribute and because the control stays focusable either way — the ring
 * must never vanish mid-screen just because the thing under it is briefly busy.
 */
internal fun <T : Element> AttrsScope<T>.actsAsButton(
    label: String? = null,
    disabled: Boolean = false,
) {
    attr("role", "button")
    label?.let { attr("aria-label", it) }
    if (disabled) attr("aria-disabled", "true")
}

/**
 * One option of a single-choice group — a season chip, a language, a rendition.
 *
 * `radio` rather than `button`, because these announce a *state* and not just an
 * action: "Season 3, selected" is the useful thing to hear, and a row of buttons cannot
 * say which one is on. Pair with [actsAsOptionGroup] on the container, since a lone
 * radio has nothing to be exclusive within.
 */
internal fun <T : Element> AttrsScope<T>.actsAsOption(selected: Boolean) {
    attr("role", "radio")
    attr("aria-checked", selected.toString())
}

/** The container for [actsAsOption] items. */
internal fun <T : Element> AttrsScope<T>.actsAsOptionGroup(label: String? = null) {
    attr("role", "radiogroup")
    label?.let { attr("aria-label", it) }
}
