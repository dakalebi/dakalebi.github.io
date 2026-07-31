package ge.dakalebi.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window

/**
 * Hash routing. GitHub Pages has no SPA rewrite, so `#/watch/531` is the only
 * scheme that survives a hard refresh and a project sub-path without extra
 * server config.
 */
sealed interface Route {
    data object Dashboard : Route
    data object Login : Route
    data class Watch(val episodeId: String) : Route
}

object Router {
    var current: Route by mutableStateOf(parse(window.location.hash))
        private set

    fun start() {
        window.addEventListener("hashchange", {
            current = parse(window.location.hash)
        })
    }

    /** Navigate. Assigning the hash fires `hashchange`, which updates [current]. */
    fun go(route: Route) {
        val next = href(route)
        if (window.location.hash != next) {
            window.location.hash = next
        } else {
            current = route
        }
    }

    /**
     * Replace the current entry instead of pushing one. Used for auth
     * redirects so the back button does not bounce between login and home.
     */
    fun replace(route: Route) {
        val url = window.location.pathname + window.location.search + href(route)
        window.history.replaceState(null, "", url)
        current = route
    }

    /** Value for an `<a href>`, so links stay copyable and middle-clickable. */
    fun href(route: Route): String = when (route) {
        Route.Dashboard -> "#/"
        Route.Login -> "#/login"
        is Route.Watch -> "#/watch/${route.episodeId}"
    }

    /** Absolute URL for the copy-link action. */
    fun absolute(route: Route): String =
        window.location.origin + window.location.pathname + href(route)

    private fun parse(hash: String): Route {
        val path = hash.removePrefix("#").removePrefix("/")
        val parts = path.split("/").filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> Route.Dashboard
            parts[0] == "login" -> Route.Login
            parts[0] == "watch" && parts.size >= 2 -> Route.Watch(parts[1])
            else -> Route.Dashboard
        }
    }
}
