package ge.dakalebi.web

/**
 * The few things the UI needs that Compose does not give it.
 *
 * Small on purpose. Everything else the screens do goes through the shared stores, so this list
 * is the honest measure of how much of the 2.0 UI is tied to a browser: a tab title, a language
 * attribute, the clipboard, and opening a link.
 */

/** Names whatever is open, so a row of tabs still says which episode is playing. */
expect fun setDocumentTitle(title: String)

/** What a screen reader picks a voice from. Follows the language, rather than being stamped once. */
expect fun setDocumentLang(tag: String)

/** Reports success rather than throwing: a refused clipboard is a toast, not a failure. */
expect fun copyToClipboard(text: String, done: (Boolean) -> Unit)

expect fun openExternal(url: String)
