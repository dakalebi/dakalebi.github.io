package ge.dakalebi.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.core.formatTime
import ge.dakalebi.di.catalog
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import ge.dakalebi.ui.Thumb
import ge.dakalebi.ui.assetBase
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * What to watch: a hero, what you have started, and one season at a time.
 *
 * Deliberately narrower than the web dashboard. There is no catalog refresh, no
 * mark-season-watched and no reset-all here — those are three confirm dialogs and
 * a live progress counter for jobs nobody does from a sofa, and every one of them
 * would be another band to steer past on the way to pressing play.
 */
@Composable
fun TvBrowseScreen() {
    val catalog = catalog()

    var season by remember { mutableStateOf<Int?>(null) }
    val seasons = catalog.seasons
    val current = season ?: catalog.defaultSeason ?: seasons.firstOrNull()

    when {
        catalog.loading -> Div({ classes("tv-note") }) { Text(S.loading) }

        catalog.loadError != null -> Div({ classes("tv-note") }) {
            Div { Text(ErrorMessages.catalogLoad(catalog.loadError)) }
        }

        catalog.episodes.isEmpty() -> Div({ classes("tv-note") }) { Text(S.emptyBody) }

        else -> {
            TvTopBar()
            catalog.continueWatching?.let { TvHero(it) }
            TvRail(
                key = "continue",
                title = S.continueLabel.caps,
                episodes = catalog.inProgress(12),
                progress = catalog.progress,
            )
            TvSeasonRail(seasons, current) { season = it }
            current?.let {
                TvGrid(
                    key = "season-$it",
                    title = S.season(it).caps,
                    episodes = catalog.season(it),
                    progress = catalog.progress,
                )
            }
        }
    }
}

/** The mark, and the way to the settings screen. */
@Composable
private fun TvTopBar() {
    Div({ classes("tv-topbar"); focusGroup("topbar", FocusAxis.X) }) {
        Img(src = "${assetBase}logo.png", alt = S.appName) { classes("tv-mark") }
        Div({ classes("grow") })
        A(href = Router.href(Route.Settings), attrs = {
            classes("tv-btn")
            focusItem("open-settings")
        }) { Text(S.settings.caps) }
    }
}

/**
 * The one episode the viewer is most likely to want.
 *
 * Two stops, not one: resuming and starting over are different intentions, and on
 * a television the second is otherwise unreachable without opening the episode
 * first.
 */
@Composable
private fun TvHero(episode: Episode) {
    val catalog = catalog()
    val entry = catalog.progress[episode.id]
    val eyebrow = when {
        entry == null -> S.beginning
        entry.isWatched -> S.lastWatched
        else -> S.continueLabel
    }
    val left = entry?.let { p ->
        val duration = p.durationSeconds ?: episode.durationSeconds
        if (duration != null && !p.isWatched) formatTime((duration - p.progressSeconds).toDouble()) else null
    }

    Div({ classes("tv-hero") }) {
        Div({ classes("tv-hero-art") }) { Thumb(episode, showLabel = false) }
        Div({ classes("tv-hero-body") }) {
            Span({ classes("tv-eyebrow") }) { Text(eyebrow.caps) }
            H1({ classes("tv-h") }) {
                Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
            }
            left?.let { Span({ classes("tv-hero-sub", "mono") }) { Text(it) } }
            Div({ classes("tv-hero-acts"); focusGroup("hero", FocusAxis.X) }) {
                A(href = Router.href(Route.Watch(episode.id)), attrs = {
                    classes("tv-btn", "tv-btn-primary")
                    focusItem("hero-play", entry = true)
                }) { Text((if (entry?.isStarted == true) S.continueLabel else S.watch).caps) }

                A(href = Router.href(Route.Watch(episode.id)), attrs = {
                    classes("tv-btn")
                    focusItem("hero-restart")
                    attr("data-from-start", "1")
                }) { Text(S.watchFromStart.caps) }
            }
        }
    }
}
