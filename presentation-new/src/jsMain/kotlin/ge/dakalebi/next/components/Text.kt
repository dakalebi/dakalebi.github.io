package ge.dakalebi.next.components

import androidx.compose.runtime.Composable
import ge.dakalebi.i18n.cased
import org.jetbrains.compose.web.dom.Text as DomText

/**
 * Compose's `Text`, set in the active language's casing.
 *
 * Every screen and component imports this one instead of Compose's, so all
 * on-page text is cased in one place: Georgian in Mtavruli, English as written
 * (see [ge.dakalebi.i18n.Strings.cased]). Text that leaves the app some other
 * way, a visible attribute such as `placeholder` or `title`, is cased where it
 * is set. Spoken labels (`aria-label`) and the tab title are left as written.
 */
@Composable
fun Text(value: String) {
    DomText(value.cased)
}
