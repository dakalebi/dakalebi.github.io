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
 * [accessibleName] is `aria-label`, and **`aria-label` replaces the visible text rather
 * than adding to it.** That is the whole reason this parameter is not called `label`:
 * the obvious reading of "label" is "the thing to call this control", and passing the
 * control's *purpose* while it visibly shows a *value* silently hides the value from
 * anyone not looking at the screen. Three of the six call sites here did exactly that
 * before it was named this way — a quality button announcing "Quality" instead of
 * "1080p", a sign-out button announcing "Sign out" instead of the account it would
 * sign out of, and a sign-in button still announcing "Sign in" while its own text had
 * changed to "Loading".
 *
 * So the rule, which WCAG 2.5.3 states as Label in Name:
 *
 * - **No visible text** (an icon) — pass the name, because nothing else supplies one.
 * - **Visible text that already names the action** — pass nothing. The text is the name.
 * - **Visible text that is a value** — pass a name that *contains* that value.
 *
 * [disabled] is `aria-disabled` rather than the `disabled` attribute, because a `Div`
 * has no such attribute and because the control must stay focusable either way — the
 * ring cannot vanish mid-screen just because the thing under it is briefly unavailable.
 * [busy] is the same idea for work in flight, and is what tells a screen reader that a
 * press *was* registered when the only other evidence is a word changing.
 */
internal fun <T : Element> AttrsScope<T>.actsAsButton(
    accessibleName: String? = null,
    disabled: Boolean = false,
    busy: Boolean = false,
) {
    attr("role", "button")
    accessibleName?.let { attr("aria-label", it) }
    if (disabled) attr("aria-disabled", "true")
    if (busy) attr("aria-busy", "true")
}

/**
 * A control that opens something: it owns a popup, and says whether it is open.
 *
 * Without this a menu button is announced as an ordinary button, so there is no way to
 * know a press opened anything — and no way to know it is already open, which turns
 * "close the menu" into a guess.
 */
internal fun <T : Element> AttrsScope<T>.ownsPopup(expanded: Boolean) {
    attr("aria-haspopup", "menu")
    attr("aria-expanded", expanded.toString())
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
