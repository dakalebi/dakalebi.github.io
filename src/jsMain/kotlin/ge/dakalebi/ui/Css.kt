package ge.dakalebi.ui

import org.jetbrains.compose.web.attributes.AttrsScope
import org.w3c.dom.Element

/**
 * Conditional class list.
 *
 * `classes("a", if (flag) "b" else "")` looks harmless but throws
 * `SyntaxError: The token provided must not be empty` from `DOMTokenList.add`,
 * which aborts the whole composition. Always route optional classes here.
 */
fun <T : Element> AttrsScope<T>.classNames(vararg names: String?) {
    val kept = names.filter { !it.isNullOrBlank() }.map { it!! }
    if (kept.isNotEmpty()) classes(*kept.toTypedArray())
}
