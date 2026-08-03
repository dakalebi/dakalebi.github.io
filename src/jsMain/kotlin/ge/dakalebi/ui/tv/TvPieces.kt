package ge.dakalebi.ui.tv

import androidx.compose.runtime.Composable
import ge.dakalebi.core.formatDuration
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import ge.dakalebi.ui.Thumb
import ge.dakalebi.ui.classNames
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.roundToInt

/**
 * One episode, as a single focus stop.
 *
 * The web tile is three: the card plus two clipboard buttons, which
 * `@media (hover: none)` then force-shows permanently. On a television those are
 * two extra presses to get past a thing a remote cannot use, so they are not here
 * at all — which also means that media query cannot reach this UI, since `web.css`
 * is never loaded.
 *
 * An `<a href>` rather than a div with a handler, so `Enter` reaching it through
 * [ge.dakalebi.ui.tv.input.TvInput] is an ordinary click and the URL is real.
 */
@Composable
fun TvTile(episode: Episode, progress: WatchProgress?) {
    val watched = progress?.isWatched == true
    A(href = Router.href(Route.Watch(episode.id)), attrs = {
        classes("tv-tile")
        focusItem(episode.id)
    }) {
        Div({ classes("tv-tile-art") }) {
            // No label inside the art: the badge and the name below already say
            // which episode this is, and a third copy is just noise.
            Thumb(episode, showLabel = false)
            Span({ classes("tv-tile-badge", "mono") }) { Text("E${episode.episodeNumber}") }
            if (watched) Span({ classes("tv-tile-seen") }) { Text("✓") }
            formatDuration(episode.durationSeconds)?.let {
                Span({ classes("tv-tile-dur", "mono") }) { Text(it) }
            }
            // At least one percent, not merely more than zero. Three seconds of a
            // 25-minute episode rounds to a fill of no width, which draws an empty
            // track that reads as a broken bar rather than as "barely started".
            if (progress != null && !watched && progress.percent >= 1) {
                Div({ classes("tv-tile-prog") }) {
                    Div({ style { property("width", "${progress.percent.roundToInt()}%") } })
                }
            }
        }
        Span({ classes("tv-tile-name") }) {
            Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber))
        }
    }
}

/**
 * A horizontal band of episodes.
 *
 * The heading is outside the focus group on purpose: it is not a stop, and the
 * engine centres the *group* vertically so the heading stays on screen above the
 * row that has the ring.
 */
@Composable
fun TvRail(
    key: String,
    title: String,
    episodes: List<Episode>,
    progress: Map<String, WatchProgress>,
) {
    if (episodes.isEmpty()) return
    Div({ classes("tv-band") }) {
        H2({ classes("tv-sub") }) { Text(title) }
        Div({ classes("tv-rail"); focusGroup(key, FocusAxis.X) }) {
            episodes.forEach { TvTile(it, progress[it.id]) }
        }
    }
}

/**
 * A wrapping grid of episodes, one season at a time.
 *
 * The column count is never declared here. `auto-fill` decides it from the
 * available width and the focus engine reads it back off the rectangles, which is
 * why this works identically on a 720p set and a 4K one.
 */
@Composable
fun TvGrid(
    key: String,
    title: String,
    episodes: List<Episode>,
    progress: Map<String, WatchProgress>,
) {
    Div({ classes("tv-band") }) {
        H2({ classes("tv-sub") }) { Text(title) }
        Div({ classes("tv-grid"); focusGroup(key, FocusAxis.Grid) }) {
            episodes.forEach { TvTile(it, progress[it.id]) }
        }
    }
}

/** The season selector: a rail of chips, so 18 seasons scroll rather than wrap. */
@Composable
fun TvSeasonRail(seasons: List<Int>, selected: Int?, onPick: (Int) -> Unit) {
    if (seasons.isEmpty()) return
    Div({ classes("tv-band") }) {
        H2({ classes("tv-sub") }) { Text(S.seasons.caps) }
        Div({
            classes("tv-rail", "tv-chips")
            focusGroup("seasons", FocusAxis.X)
            actsAsOptionGroup(S.seasons)
        }) {
            seasons.forEach { season ->
                Div({
                    classNames("tv-chip", if (season == selected) "on" else null)
                    focusItem("season-$season")
                    actsAsOption(selected = season == selected)
                    onClick { onPick(season) }
                }) { Text(S.season(season).caps) }
            }
        }
    }
}
