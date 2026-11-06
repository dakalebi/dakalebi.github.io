package ge.dakalebi.next.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import ge.dakalebi.di.catalog
import ge.dakalebi.di.router
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.EpisodeTile
import ge.dakalebi.next.components.MenuButton
import ge.dakalebi.next.components.MenuEntry
import ge.dakalebi.next.components.tvItem
import ge.dakalebi.next.tv.focus.FocusAxis
import ge.dakalebi.next.tv.focus.focusGroup
import ge.dakalebi.next.tv.focus.focusItem
import ge.dakalebi.next.web.count
import ge.dakalebi.presentation.DashboardConfirm
import ge.dakalebi.presentation.DashboardViewModel
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Text

/**
 * Seasons on TV: every season as a ring along the top, and the picked season's episodes
 * as a grid of tiles under it.
 *
 * The pick is the route (`#/seasons/6`), replaced rather than pushed, as on the web. OK
 * on a ring picks it and leaves the ring where it is, so the next press can pick another
 * or go down into the episodes. With no season in the address, the one you are on.
 */
@Composable
internal fun TvSeasons(number: Int?, dashboard: DashboardViewModel) {
    val catalog = catalog()
    val router = router()

    TvHead(S.seasons)

    TvCatalogGate(dashboard) {
        val seasons = catalog.seasons
        val current = catalog.continueWatching
        val selected = number?.takeIf { it in seasons }
            ?: current?.seasonNumber
            ?: catalog.defaultSeason
            ?: seasons.first()

        // A season the catalog does not have falls back to the tab's own address.
        LaunchedEffect(number, seasons) {
            if (number != null && number !in seasons) router.replace(Route.Seasons)
        }

        Div({
            classes("tv-row", "tv-row--seasons")
            attr("aria-label", S.seasons)
            focusGroup("seasons-strip", FocusAxis.X)
        }) {
            seasons.forEach { season ->
                TvSeasonLink(season, currentSeason = current?.seasonNumber, selected = season == selected) {
                    focusItem("strip-$season", entry = season == selected)
                    onClick { event ->
                        event.preventDefault()
                        router.replace(Route.Season(season))
                    }
                }
            }
        }

        // Keyed, so each season's grid has its own focus memory and starts fresh.
        key(selected) {
            val count = catalog.count(selected)
            val episodes = catalog.season(selected)
            // The ring lands on the episode you are on when it is in this season, else
            // on the first one not yet watched.
            val entryId = current?.id?.takeIf { id -> episodes.any { it.id == id } }
                ?: episodes.firstOrNull { catalog.progress[it.id]?.isWatched != true }?.id
                ?: episodes.firstOrNull()?.id

            Section({ classes("tv-season-section") }) {
                Div({ classes("tv-season-head") }) {
                    Div {
                        H2({ classes("tv-season-title") }) { Text(S.season(selected).caps) }
                        P({ classes("tv-season-meta") }) { Text(S.episodeCount(count.total, count.watched)) }
                    }
                    Div({ classes("tv-season-actions"); focusGroup("season-actions", FocusAxis.X) }) {
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
                            attrs = tvItem("season-menu"),
                        )
                    }
                }
                Div({
                    classes("tv-grid")
                    focusGroup("season-$selected", FocusAxis.Grid)
                }) {
                    episodes.forEach { episode ->
                        EpisodeTile(
                            episode = episode,
                            progress = catalog.progress[episode.id],
                            href = Router.href(Route.Watch(episode.id)),
                            attrs = { focusItem(episode.id, entry = episode.id == entryId) },
                        )
                    }
                }
            }
        }
    }
}
