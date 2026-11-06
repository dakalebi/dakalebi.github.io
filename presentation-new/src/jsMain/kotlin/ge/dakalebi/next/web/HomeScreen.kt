package ge.dakalebi.next.web

import androidx.compose.runtime.Composable
import ge.dakalebi.core.formatDuration
import ge.dakalebi.core.formatTime
import ge.dakalebi.di.catalog
import ge.dakalebi.di.session
import ge.dakalebi.di.sessionState
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.Button
import ge.dakalebi.next.components.ButtonVariant
import ge.dakalebi.next.components.EpisodeTile
import ge.dakalebi.next.components.Glyph
import ge.dakalebi.next.components.Skeleton
import ge.dakalebi.next.components.SkeletonShape
import ge.dakalebi.next.components.Thumb
import ge.dakalebi.presentation.DashboardViewModel
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import kotlin.math.roundToInt
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Text

/** How many episodes the sideways rows on Home hold: the old dashboard's rail length. */
private const val ROW_LENGTH = 12

/**
 * Home: where you are and what comes next.
 *
 * The episode to continue, in a card with the one amber button. Then the episodes
 * after it, the other episodes you started, and every season as a ring. All of it is
 * read from the catalog; nothing here changes progress.
 */
@Composable
internal fun HomeScreen(dashboard: DashboardViewModel) {
    val catalog = catalog()
    val session = session()
    val state = sessionState()

    PageHead(S.home) {
        // The phone's way to an account. From 64rem the side menu offers it.
        if (state.isGuest) {
            Button(
                S.signIn,
                onClick = session::leaveGuestMode,
                variant = ButtonVariant.Quiet,
                attrs = { classes("home-signin") },
            )
        }
    }

    CatalogGate(dashboard, loading = { HomeLoading() }) {
        val current = catalog.continueWatching
        if (current != null) {
            ContinueCard(current)

            val next = catalog.upcoming(current, ROW_LENGTH)
            if (next.isNotEmpty()) {
                PageSection(
                    title = S.nextEpisodes,
                    end = {
                        A(Router.href(Route.Season(current.seasonNumber)), { classes("section-link") }) {
                            Text(S.allEpisodes.caps)
                        }
                    },
                ) {
                    TileRow(next, withSeason = next.any { it.seasonNumber != current.seasonNumber })
                }
            }
        }

        val started = catalog.inProgress(ROW_LENGTH + 1).filter { it.id != current?.id }.take(ROW_LENGTH)
        if (started.isNotEmpty()) {
            PageSection(title = S.statStarted, meta = "${started.size}") {
                TileRow(started, withSeason = true)
            }
        }

        val stats = catalog.stats
        PageSection(title = S.seasons, meta = "${stats.watched} / ${stats.total} ${S.statWatched}") {
            SeasonGrid(currentSeason = current?.seasonNumber)
        }
    }
}

/**
 * The episode to continue: its still, where you are in it, and Continue (or Watch,
 * for one not started or already watched). The words are the old hero's.
 */
@Composable
private fun ContinueCard(episode: Episode) {
    val catalog = catalog()
    val entry = catalog.progress[episode.id]
    val watched = entry?.isWatched == true
    val position = entry?.progressSeconds ?: 0
    val duration = entry?.durationSeconds ?: episode.durationSeconds
    // The old hero's five-second floor: a stray `timeupdate` at 0:02 is not a start.
    val resuming = !watched && position > 5
    val href = Router.href(Route.Watch(episode.id))

    Section({ classes("continue") }) {
        A(href, { classes("continue-still"); attr("tabindex", "-1"); attr("aria-hidden", "true") }) {
            Thumb(episode, progress = entry?.takeIf { resuming }?.percent?.let { it / 100.0 })
        }
        Div({ classes("continue-body") }) {
            P({ classes("continue-eyebrow") }) {
                Text(
                    when {
                        resuming -> S.continueLabel
                        watched -> S.lastWatched
                        else -> S.beginning
                    }.caps,
                )
            }
            H2({ classes("continue-title") }) {
                Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
            }
            P({ classes("continue-meta") }) {
                Text(
                    when {
                        watched -> S.watchedLabel
                        resuming && duration != null && duration > 0 -> S.minutesLeft(
                            ((duration - position) / 60.0).roundToInt().coerceAtLeast(1),
                            formatTime(position.toDouble()),
                            formatTime(duration.toDouble()),
                        )
                        // Its name if Formula gave it one, else its length: the title
                        // above already says which episode it is.
                        else -> episode.title ?: formatDuration(duration).orEmpty()
                    },
                )
            }
            Div({ classes("continue-actions") }) {
                Button(
                    if (resuming) S.resume else S.watch,
                    variant = ButtonVariant.Primary,
                    icon = Glyph.Play,
                    href = href,
                )
            }
            if (!episode.hasVideo) {
                P({ classes("continue-note") }) { Text(S.videoUnavailableForEpisode) }
            }
        }
    }
}

/** A sideways row of episode tiles that scrolls on its own, edge to edge on a phone. */
@Composable
private fun TileRow(episodes: List<Episode>, withSeason: Boolean) {
    val catalog = catalog()
    Div({ classes("tile-row") }) {
        episodes.forEach { episode ->
            EpisodeTile(
                episode = episode,
                progress = catalog.progress[episode.id],
                href = Router.href(Route.Watch(episode.id)),
                withSeason = withSeason,
            )
        }
    }
}

/** Home's shape while the catalog loads: the card, a row of tiles, the rings. */
@Composable
private fun HomeLoading() {
    Div({ classes("continue", "continue--loading") }) {
        Skeleton(SkeletonShape.Tile)
        Div({ classes("continue-body") }) {
            Skeleton(SkeletonShape.Line)
            Skeleton(SkeletonShape.Line)
        }
    }
    Div({ classes("tile-row", "tile-row--loading") }) {
        repeat(4) { Skeleton(SkeletonShape.Tile) }
    }
    Div({ classes("season-grid", "season-grid--loading") }) {
        repeat(6) { Skeleton(SkeletonShape.Ring) }
    }
}
