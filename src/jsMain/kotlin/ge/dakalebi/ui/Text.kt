package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import ge.dakalebi.i18n.cased
import org.jetbrains.compose.web.dom.Text as DomText

/**
 * Compose's `Text`, set in the active language's casing.
 *
 * Every screen imports this one instead of `org.jetbrains.compose.web.dom.Text`, so
 * all visible text is cased in a single place: Georgian in Mtavruli, anything else as
 * written (see [ge.dakalebi.i18n.Strings.cased]). A rule that covers every string on
 * the page belongs where every string passes, not at each call site, where the next
 * new screen would be the one that forgets it. Text already passed through `caps`
 * comes through unchanged, since Mtavruli has nothing left to case.
 *
 * Text that reaches the page another way is cased where it leaves this app: strings
 * handed to keel components (toasts, empty states, button labels) and the visible
 * attributes (`placeholder`, `title`). Spoken labels and the tab title are not on the
 * page and stay as written.
 */
@Composable
fun Text(value: String) {
    DomText(value.cased)
}
