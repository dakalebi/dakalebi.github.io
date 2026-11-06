package ge.dakalebi.next.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ge.dakalebi.next.tv.focus.focusItem
import ge.dakalebi.next.tv.input.LocalTvInput
import org.jetbrains.compose.web.attributes.AttrsScope
import org.w3c.dom.HTMLElement

/**
 * Attributes a caller adds to a component's main element, on top of the component's own.
 *
 * The TV shell is the main user: it passes `focusItem(...)` so a control becomes a
 * D-pad stop, which only the screen can do because only the screen knows the keys its
 * focus memory is filed under. See [tvItem].
 */
typealias ExtraAttrs = AttrsScope<HTMLElement>.() -> Unit

/**
 * Marks a control as a D-pad stop on TV, and does nothing in the other shells.
 *
 * [key] must be stable: TV focus memory is filed under it. [entry] is where the ring
 * starts when the screen or layer opens.
 */
@Composable
fun tvItem(key: String, entry: Boolean = false): ExtraAttrs? =
    if (LocalTvInput.current != null) ({ focusItem(key, entry) }) else null

/**
 * An id that is unique on the page and stable while the caller is composed. Used to tie
 * a label, an error or a description to its control (`for`, `aria-labelledby`,
 * `aria-describedby`).
 */
@Composable
fun rememberId(prefix: String): String = remember { "$prefix-${++lastId}" }

private var lastId = 0
