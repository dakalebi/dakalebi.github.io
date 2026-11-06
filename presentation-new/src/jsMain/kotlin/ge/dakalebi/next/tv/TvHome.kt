package ge.dakalebi.next.tv

import androidx.compose.runtime.Composable
import ge.dakalebi.core.formatDuration
import ge.dakalebi.core.formatTime
import ge.dakalebi.di.catalog
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.Button
import ge.dakalebi.next.components.ButtonVariant
import ge.dakalebi.next.components.Glyph
import ge.dakalebi.next.components.Thumb
import ge.dakalebi.next.tv.focus.FocusAxis
import ge.dakalebi.next.tv.focus.focusGroup
import ge.dakalebi.next.tv.focus.focusItem
import ge.dakalebi.presentation.DashboardViewModel
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import kotlin.math.roundToInt
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Text

/**
 * Home on TV: the episode to continue, large, with the ring on its one amber button.
 * Under it the episodes after it, the other episodes started, and every season as a
 * ring. The same reads of the catalog as the web's Home; nothing here changes progress.
 */
@Composable
internal fun TvHome(dashboard: DashboardViewModel) {
    val catalog = catalog()

    TvCatalogGate(dashboard) {
        val current = catalog.continueWatching
        if (current != null) {
            TvHero(current)

            val next = catalog.upcoming(current, TvConfig.RAIL_COUNT)
            if (next.isNotEmpty()) {
                TvBand(S.nextEpisodes) {
                    TvEpisodeRow(
                        key = "home-next",
                        episodes = next,
                        withSeason = next.any { it.seasonNumber != current.seasonNumber },
                    )
                }
            }
        }

        val started = catalog.inProgress(TvConfig.RAIL_COUNT + 1)
            .filter { it.id != current?.id }
            .take(TvConfig.RAIL_COUNT)
        if (started.isNotEmpty()) {
            TvBand(S.statStarted, meta = "${started.size}") {
                TvEpisodeRow(key = "home-started", episodes = started, withSeason = true)
            }
        }

        val stats = catalog.stats
        TvBand(S.seasons, meta = "${stats.watched} / ${stats.total} ${S.statWatched}") {
            Div({
                classes("tv-row", "tv-row--seasons")
                focusGroup("home-seasons", FocusAxis.X)
            }) {
                catalog.seasons.forEach { season ->
                    TvSeasonLink(season, currentSeason = current?.seasonNumber) {
                        focusItem("season-$season")
                    }
                }
            }
        }
    }
}

/**
 * The episode to continue: where you are in it and the button that plays it, with its
 * still on the right. "From the start" sits beside Continue for an episode left halfway.
 */
@Composable
private fun TvHero(episode: Episode) {
    val catalog = catalog()
    val entry = catalog.progress[episode.id]
    val watched = entry?.isWatched == true
    val position = entry?.progressSeconds ?: 0
    val duration = entry?.durationSeconds ?: episode.durationSeconds
    // The old hero's five-second floor: a stray `timeupdate` at 0:02 is not a start.
    val resuming = !watched && position > 5
    val href = Router.href(Route.Watch(episode.id))

    Section({ classes("tv-hero") }) {
        Div({ classes("tv-hero-text") }) {
            P({ classes("tv-hero-eyebrow") }) {
                Text(
                    when {
                        resuming -> S.continueLabel
                        watched -> S.lastWatched
                        else -> S.beginning
                    }.caps,
                )
            }
            H1({ classes("tv-hero-title") }) {
                Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
            }
            P({ classes("tv-hero-meta") }) {
                Text(
                    when {
                        watched -> S.watchedLabel
                        resuming && duration != null && duration > 0 -> S.minutesLeft(
                            ((duration - position) / 60.0).roundToInt().coerceAtLeast(1),
                            formatTime(position.toDouble()),
                            formatTime(duration.toDouble()),
                        )
                        else -> episode.title ?: formatDuration(duration).orEmpty()
                    },
                )
            }
            Div({
                classes("tv-hero-actions")
                focusGroup("hero", FocusAxis.X)
            }) {
                Button(
                    if (resuming) S.resume else S.watch,
                    variant = ButtonVariant.Primary,
                    icon = Glyph.Play,
                    href = href,
                    attrs = { focusItem("hero-play", entry = true) },
                )
                if (resuming) {
                    Button(
                        S.watchFromStart,
                        icon = Glyph.SkipBack,
                        href = href,
                        onClick = { TvStartFrom.set(episode.id) },
                        attrs = { focusItem("hero-start") },
                    )
                }
            }
            if (!episode.hasVideo) {
                // A television cannot open Cavea Plus's page. Watch leads to a QR code for it.
                P({ classes("tv-hero-note") }) {
                    Text(if (episode.onCavea) S.videoOnlyOnCavea else S.videoUnavailableForEpisode)
                }
            }
        }
        Div({ classes("tv-hero-still"); attr("aria-hidden", "true") }) {
            Thumb(episode, progress = entry?.takeIf { resuming }?.percent?.let { it / 100.0 })
        }
    }
}
