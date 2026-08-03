package ge.dakalebi.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window

/**
 * The browser's [Router]: the location hash is the source of truth.
 *
 * Every route the app can be in is expressible as a URL, which is what makes a
 * hard refresh and a copied link land in the same place.
 */
class HashRouter : Router {
    override var current: Route by mutableStateOf(Router.parse(window.location.hash))
        private set

    fun start() {
        window.addEventListener("hashchange", { current = Router.parse(window.location.hash) })
    }

    /** Assigning the hash fires `hashchange`, which updates [current]. */
    override fun go(route: Route) {
        val next = Router.href(route)
        if (window.location.hash != next) window.location.hash = next else current = route
    }

    override fun replace(route: Route) {
        val url = window.location.pathname + window.location.search + Router.href(route)
        window.history.replaceState(null, "", url)
        current = route
    }

    /**
     * Built from `pathname` rather than assumed to be at the site root, so this
     * stays correct under a project sub-path.
     */
    override fun absolute(route: Route): String =
        window.location.origin + window.location.pathname + Router.href(route)
}
