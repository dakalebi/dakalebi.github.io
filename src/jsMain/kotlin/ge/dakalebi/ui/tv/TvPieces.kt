package ge.dakalebi.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import ge.dakalebi.core.formatDuration
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import ge.dakalebi.ui.Thumb
import ge.dakalebi.ui.classNames
import ge.dakalebi.ui.tv.focus.Axis
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.centre
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
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
fun TvTile(episode: Episode, progress: WatchProgress?, entry: Boolean = false) {
    val watched = progress?.isWatched == true
    A(href = Router.href(Route.Watch(episode.id)), attrs = {
        classes("tv-tile")
        focusItem(episode.id, entry = entry)
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
    /**
     * The episode to land on when the ring first drops into this rail. The player's
     * "up next" rail names its head (the true next episode) and its "previously" rail
     * names its tail (the episode just before this one), so a fresh Down lands on the
     * one that matters instead of mid-row. Null keeps the geometric default.
     */
    entryEpisodeId: String? = null,
) {
    if (episodes.isEmpty()) return
    Div({ classes("tv-band") }) {
        H2({ classes("tv-sub") }) { Text(title) }
        Div({ classes("tv-rail"); focusGroup(key, FocusAxis.X) }) {
            episodes.forEach { TvTile(it, progress[it.id], entry = it.id == entryEpisodeId) }
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

/** Holds the season strip's scroller so an effect can centre the selected chip in it. */
private class RailRef {
    var el: HTMLElement? = null
}

/** The season selector: a rail of chips, so 18 seasons scroll rather than wrap. */
@Composable
fun TvSeasonRail(seasons: List<Int>, selected: Int?, onPick: (Int) -> Unit) {
    if (seasons.isEmpty()) return
    val rail = remember { RailRef() }

    Div({ classes("tv-band") }) {
        H2({ classes("tv-sub") }) { Text(S.seasons.caps) }
        Div({
            classes("tv-rail", "tv-chips")
            focusGroup("seasons", FocusAxis.X)
            actsAsOptionGroup(S.seasons)
            ref { element -> rail.el = element; onDispose { rail.el = null } }
        }) {
            seasons.forEach { season ->
                val isSelected = season == selected
                Div({
                    classNames("tv-chip", if (isSelected) "on" else null)
                    // The current season is where a fresh Down onto the strip should land,
                    // not season one. See [ge.dakalebi.ui.tv.focus.SpatialNav].
                    focusItem("season-$season", entry = isSelected)
                    actsAsOption(selected = isSelected)
                    onClick { onPick(season) }
                }) { Text(S.season(season).caps) }
            }
        }
    }

    // Pre-scroll the strip so the selected season is visible on arrival, rather than
    // parked off-screen while the ring is still up in the masthead — a default of
    // season 15 should not open showing season 1. Deferred a tick so the rail has laid
    // out; `setTimeout`, not the frame clock, which a hidden page stops. Re-runs when the
    // selection changes so picking a far season keeps it centred.
    DisposableEffect(selected) {
        val timer = window.setTimeout({
            val scroller = rail.el ?: return@setTimeout
            (scroller.querySelector(".tv-chip.on") as? HTMLElement)
                ?.let { centre(it, setOf(Axis.X), scroller) }
        }, 0)
        onDispose { window.clearTimeout(timer) }
    }
}
