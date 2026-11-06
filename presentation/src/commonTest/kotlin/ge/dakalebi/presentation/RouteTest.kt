package ge.dakalebi.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hash parsing, which decides what a bookmarked or shared link opens.
 *
 * Testable at all only because parsing is a companion function rather than a
 * private method on a router that needs a `window`.
 */
class RouteTest {

    @Test
    fun an_empty_hash_is_the_dashboard() {
        assertEquals(Route.Dashboard, Router.parse(""))
        assertEquals(Route.Dashboard, Router.parse("#"))
        assertEquals(Route.Dashboard, Router.parse("#/"))
    }

    @Test
    fun a_watch_link_carries_the_episode_id() {
        assertEquals(Route.Watch("531"), Router.parse("#/watch/531"))
    }

    @Test
    fun a_watch_link_with_no_id_is_not_a_watch_route() {
        assertEquals(Route.Dashboard, Router.parse("#/watch"))
    }

    @Test
    fun an_unknown_path_falls_back_to_the_dashboard() {
        assertEquals(Route.Dashboard, Router.parse("#/nonsense/here"))
    }

    @Test
    fun a_settings_link_is_its_own_route() {
        assertEquals(Route.Settings, Router.parse("#/settings"))
    }

    @Test
    fun a_seasons_link_is_the_list_and_a_number_after_it_is_that_season() {
        assertEquals(Route.Seasons, Router.parse("#/seasons"))
        assertEquals(Route.Season(3), Router.parse("#/seasons/3"))
    }

    @Test
    fun a_season_that_is_not_a_positive_number_is_the_list() {
        assertEquals(Route.Seasons, Router.parse("#/seasons/three"))
        assertEquals(Route.Seasons, Router.parse("#/seasons/0"))
        assertEquals(Route.Seasons, Router.parse("#/seasons/-2"))
    }

    /**
     * The whole set, so adding a route without giving it an `href` — or giving it
     * one `parse` cannot read back — fails here rather than as a link that quietly
     * lands on the dashboard.
     */
    @Test
    fun every_route_round_trips_through_its_href() {
        val routes = listOf(
            Route.Dashboard,
            Route.Login,
            Route.Settings,
            Route.Watch("531"),
            Route.Seasons,
            Route.Season(12),
        )
        for (route in routes) {
            assertEquals(route, Router.parse(Router.href(route)), "round trip for $route")
        }
    }
}
