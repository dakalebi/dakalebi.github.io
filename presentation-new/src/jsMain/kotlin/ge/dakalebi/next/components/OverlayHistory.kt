package ge.dakalebi.next.components

import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * Lets the phone's Back gesture, and the browser's Back button, close the overlay on top
 * instead of leaving the screen behind it. Web and drive shells only: on TV, Back already
 * belongs to the overlay through its `TvLayer`.
 *
 * While any overlay is open, one extra history entry sits on top, with the same URL and
 * [MARK] as its state. Back pops that entry. The URL does not change, so no `hashchange`
 * fires and the router stays where it is; the `popstate` closes the top overlay. When an
 * overlay closes some other way (a button, Escape, the scrim), the entry is taken back
 * off, so the next Back leaves the screen as it did before.
 *
 * Overlays share the one entry. A menu row that opens a dialog hands the entry over
 * instead of popping and pushing it, and when Back closes a dialog over a sheet, the
 * entry is pushed again for the sheet.
 *
 * Known gaps, all small. No overlay action changes the route today.
 * - A route push from an open overlay leaves the entry behind, and so does a reload with
 *   an overlay open. Back passes through it once with no visible change. The next
 *   overlay opened on that entry reuses it.
 * - A route replace under an open overlay leaves the old route one Back away.
 */
internal object OverlayHistory {
    private const val MARK = "dakalebi-overlay"

    /** What Back closes, top last. */
    private val open = mutableListOf<Handle>()

    /** Whether the entry is on top now, and the URL it was pushed with. */
    private var entryPushed = false
    private var pushedUrl: String? = null

    /** Set between taking the entry off and the `popstate` that says it is off. */
    private var awaitingPop = false

    private var installed = false

    class Handle internal constructor(internal val onBack: () -> Unit)

    /** Call when an overlay opens. [onBack] closes it. */
    fun open(onBack: () -> Unit): Handle {
        install()
        val handle = Handle(onBack)
        open += handle
        sync()
        return handle
    }

    /** Call when the overlay that [handle] belongs to has closed, however it closed. */
    fun close(handle: Handle) {
        if (!open.remove(handle)) return
        // A turn later, so an overlay opened by the one closing (a menu row that asks a
        // question) takes the entry over before anything pops it.
        window.setTimeout({ sync() }, 0)
    }

    private fun sync() {
        if (awaitingPop) return
        val href = window.location.href
        when {
            open.isNotEmpty() && !entryPushed -> {
                // An entry left on top by a reload or a route change is reused, so the
                // gaps above never stack.
                if (window.history.state != MARK) window.history.pushState(MARK, "", href)
                entryPushed = true
                pushedUrl = href
            }
            open.isEmpty() && entryPushed -> {
                entryPushed = false
                when {
                    // Not on the entry any more: the page moved on while the overlay was
                    // open. Nothing to take off.
                    window.history.state != MARK -> Unit
                    href == pushedUrl -> {
                        awaitingPop = true
                        window.history.back()
                    }
                    // The router replaced the URL under the overlay. Going back would undo
                    // that route change, so leave the entry and drop its mark, so the next
                    // overlay does not mistake it for one of its own.
                    else -> window.history.replaceState(null, "", href)
                }
            }
        }
    }

    private fun install() {
        if (installed) return
        installed = true
        window.addEventListener("popstate", { _: Event ->
            when {
                awaitingPop -> {
                    awaitingPop = false
                    // An overlay may have opened while the entry was coming off.
                    sync()
                }
                // Back, or a route change while an overlay is open: either way the entry
                // is gone, and the top overlay closes.
                entryPushed && window.history.state != MARK -> {
                    entryPushed = false
                    open.lastOrNull()?.onBack?.invoke()
                    // Pushes the entry again for any overlay still open under it.
                    window.setTimeout({ sync() }, 0)
                }
            }
        })
    }
}
