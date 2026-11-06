package ge.dakalebi.presentation

/**
 * Hash routing. GitHub Pages has no SPA rewrite, so `#/watch/531` is the only
 * scheme that survives a hard refresh and a project sub-path without extra
 * server config.
 */
sealed interface Route {
    data object Dashboard : Route
    data object Login : Route
    data class Watch(val episodeId: String) : Route

    /**
     * A screen on TV, a drawer on the web.
     *
     * The web UI shows settings in a sheet over the dashboard and so has no use
     * for a route of its own; on a television a bottom sheet wastes the screen and
     * complicates focus, so it is a place you go. Unhandled routes fall back to the
     * dashboard, which is what the web app does with this one.
     */
    data object Settings : Route
}

/**
 * Where the app is, and how to move.
 *
 * An interface rather than the browser class itself because the screens read it
 * through a `CompositionLocal` and none of them care how a route is stored. The
 * two halves that carry the actual rules — [href] and [parse] — are in the
 * companion, so they are reachable without an instance and testable without a
 * browser.
 *
 * Starting a router is deliberately not on here: attaching to a history
 * mechanism is the implementation's own business, and only the entry point
 * does it.
 */
interface Router {
    val current: Route

    /** Navigate, adding a history entry. */
    fun go(route: Route)

    /**
     * Replace the current entry instead of pushing one. Used for auth
     * redirects so the back button does not bounce between login and home.
     */
    fun replace(route: Route)

    /** Absolute URL for the copy-link action. */
    fun absolute(route: Route): String

    companion object {
        /** Value for an `<a href>`, so links stay copyable and middle-clickable. */
        fun href(route: Route): String = when (route) {
            Route.Dashboard -> "#/"
            Route.Login -> "#/login"
            Route.Settings -> "#/settings"
            is Route.Watch -> "#/watch/${route.episodeId}"
        }

        /** Pure, so the parsing rules can be checked without a browser. */
        fun parse(hash: String): Route {
            val path = hash.removePrefix("#").removePrefix("/")
            val parts = path.split("/").filter { it.isNotBlank() }
            return when {
                parts.isEmpty() -> Route.Dashboard
                parts[0] == "login" -> Route.Login
                parts[0] == "settings" -> Route.Settings
                parts[0] == "watch" && parts.size >= 2 -> Route.Watch(parts[1])
                else -> Route.Dashboard
            }
        }
    }
}
