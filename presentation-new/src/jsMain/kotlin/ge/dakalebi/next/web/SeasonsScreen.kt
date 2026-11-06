package ge.dakalebi.next.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import ge.dakalebi.di.catalog
import ge.dakalebi.di.router
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.EpisodeRow
import ge.dakalebi.next.components.MenuEntry
import ge.dakalebi.next.components.MenuButton
import ge.dakalebi.next.components.SeasonRing
import ge.dakalebi.next.components.Skeleton
import ge.dakalebi.next.components.SkeletonShape
import ge.dakalebi.next.components.seasonLabel
import ge.dakalebi.presentation.CatalogStore
import ge.dakalebi.presentation.DashboardConfirm
import ge.dakalebi.presentation.DashboardViewModel
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Small
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/** A season's watched and total episode counts. */
internal class SeasonCount(val watched: Int, val total: Int)

internal fun CatalogStore.count(season: Int): SeasonCount {
    val episodes = season(season)
    return SeasonCount(
        watched = episodes.count { progress[it.id]?.isWatched == true },
        total = episodes.size,
    )
}

/**
 * Seasons: every season as a ring along the top, and the picked season's episodes
 * under it, each a row that opens the episode.
 *
 * The pick is the route (`#/seasons/6`), so a season has an address. Picking another
 * replaces the address rather than adding one: Back leaves the tab, not the last
 * eleven seasons looked at. No number in the address means the season of the episode
 * to continue.
 */
@Composable
internal fun SeasonsScreen(number: Int?, dashboard: DashboardViewModel) {
    val catalog = catalog()
    val router = router()

    PageHead(S.seasons)

    CatalogGate(dashboard, loading = { SeasonsLoading() }) {
        val seasons = catalog.seasons
        val current = catalog.continueWatching
        // With no season in the address, the one you are on: the amber ring. The old
        // dashboard's pick (the season of the last progress row) can differ from it,
        // and the tab would then open on one season with another one marked.
        val selected = number?.takeIf { it in seasons }
            ?: current?.seasonNumber
            ?: catalog.defaultSeason
            ?: seasons.first()

        // A season the catalog does not have (an old link, a typo) falls back to the
        // tab's own address instead of an empty list.
        LaunchedEffect(number, seasons) {
            if (number != null && number !in seasons) router.replace(Route.Seasons)
        }

        RingStrip(seasons, selected, currentSeason = current?.seasonNumber)

        val count = catalog.count(selected)
        Section({ classes("page-section", "season-section") }) {
            // The season's name in the title role with its count under it, as the
            // design draws it, rather than a small section label.
            Div({ classes("season-head") }) {
                Div({ classes("season-head-text") }) {
                    H2({ classes("season-title") }) { Text(S.season(selected).caps) }
                    P({ classes("season-meta") }) { Text(S.episodeCount(count.total, count.watched)) }
                }
                MenuButton(
                    label = S.seasonActions,
                    entries = listOf(
                        MenuEntry(
                            S.markSeasonWatched,
                            onSelect = { dashboard.ask(DashboardConfirm.MarkSeason(selected)) },
                        ),
                        MenuEntry(
                            S.resetSeasonProgress,
                            onSelect = { dashboard.ask(DashboardConfirm.ResetSeason(selected)) },
                            danger = true,
                        ),
                    ),
                )
            }
            Ul({ classes("ep-list") }) {
                catalog.season(selected).forEach { episode ->
                    Li {
                        EpisodeRow(
                            episode = episode,
                            progress = catalog.progress[episode.id],
                            href = Router.href(Route.Watch(episode.id)),
                            current = episode.id == current?.id,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The seasons as a row of rings that scrolls sideways on a phone and wraps from
 * 64rem. Each is a link, so it opens in a new tab like any other; a plain click
 * swaps the season in place.
 */
@Composable
private fun RingStrip(seasons: List<Int>, selected: Int, currentSeason: Int?) {
    val catalog = catalog()
    val router = router()

    // The picked ring in view, also when the address named a season far along.
    LaunchedEffect(selected) {
        document.querySelector(".ring-strip [aria-current]")
            ?.asDynamic()
            ?.scrollIntoView(js("({ block: 'nearest', inline: 'center' })"))
    }

    Nav({
        classes("ring-strip")
        attr("aria-label", S.seasons)
    }) {
        seasons.forEach { season ->
            val count = catalog.count(season)
            A(Router.href(Route.Season(season)), {
                classes("ring-link")
                attr("aria-label", seasonLabel(season, count.watched, count.total))
                if (season == selected) attr("aria-current", "page")
                onClick { event ->
                    // A modified click is the browser's: a new tab, a new window.
                    if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return@onClick
                    event.preventDefault()
                    router.replace(Route.Season(season))
                }
            }) {
                SeasonRing(season, count.watched, count.total, current = season == currentSeason)
            }
        }
    }
}

/**
 * Every season as a ring with its count under it, each opening that season on the
 * Seasons tab. Home's way into a season.
 */
@Composable
internal fun SeasonGrid(currentSeason: Int?) {
    val catalog = catalog()
    Div({ classes("season-grid") }) {
        catalog.seasons.forEach { season ->
            val count = catalog.count(season)
            A(Router.href(Route.Season(season)), {
                classes("season-card")
                attr("aria-label", seasonLabel(season, count.watched, count.total))
            }) {
                SeasonRing(season, count.watched, count.total, current = season == currentSeason)
                Small({ classes("season-card-meta"); attr("aria-hidden", "true") }) {
                    Text("${count.watched} / ${count.total}")
                }
            }
        }
    }
}

/** The Seasons tab's shape while the catalog loads: the rings, then rows. */
@Composable
private fun SeasonsLoading() {
    Div({ classes("ring-strip", "ring-strip--loading") }) {
        repeat(7) { Skeleton(SkeletonShape.Ring) }
    }
    Div({ classes("ep-list", "ep-list--loading") }) {
        repeat(6) { Skeleton(SkeletonShape.Row) }
    }
}
