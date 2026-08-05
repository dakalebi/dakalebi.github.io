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
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.SpatialNav
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Home: a masthead, then shelves — what you have started, the seasons, and one season
 * at a time.
 *
 * The shape is deliberate and is the television idiom rather than a translated web
 * page: a vertical stack of horizontally scrolling shelves, each with a permanent
 * heading, and the top item's metadata lifted into a masthead above the first of them.
 *
 * Deliberately narrower than the web dashboard. There is no catalog refresh, no
 * mark-season-watched and no reset-all here — those are three confirm dialogs and
 * a live progress counter for jobs nobody does from a sofa, and every one of them
 * would be another band to steer past on the way to pressing play.
 *
 * The mark and the way to Settings used to sit in a top bar here. Both now live in
 * [TvNavRail], which is why this function starts at the content.
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
            catalog.continueWatching?.let { TvMasthead(it) }
            TvRail(
                key = "continue",
                title = S.continueLabel.caps,
                episodes = catalog.inProgress(TvConfig.RAIL_COUNT),
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
                TvBackToTop()
            }
        }
    }
}

/**
 * The masthead: what the viewer is most likely to want, above the first shelf.
 *
 * Replaces a two-column hero that put artwork on one side and a stack of text on the
 * other. That shape is a web landing page; a television home screen is a **vertical
 * stack of horizontal shelves** with the metadata for the top item lifted above the
 * first of them, which is what "the full metadata lives in a masthead above the top
 * shelf" describes and what YouTube's own home is built from.
 *
 * The practical gain is the whole point of the change: the old hero was a half-screen
 * band that pushed the first real shelf below the fold, so arriving at the app showed
 * one episode and no rails. Text over a wide gradient costs a third of that height and
 * the first shelf is visible on arrival.
 *
 * Two stops, not one: resuming and starting over are different intentions, and on a
 * television the second is otherwise unreachable without opening the episode first.
 */
@Composable
private fun TvMasthead(episode: Episode) {
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

    Div({ classes("tv-masthead") }) {
        // The episode still, full-bleed behind the text, YouTube's mainstage
        // treatment. `Thumb` already handles the CDN's occasional 404 by falling
        // back to a gradient, which reads as a perfectly good backdrop; `showLabel`
        // is off because the scrim and the heading below already name the episode.
        // `aria-hidden`, because it is pure decoration — the heading is the accessible
        // name, and the still's own `alt` would otherwise be announced on top of it.
        Div({ classes("tv-masthead-art"); attr("aria-hidden", "true") }) {
            Thumb(episode, showLabel = false)
        }

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

/**
 * The control at the foot of the grid that returns the ring to the top of the screen.
 *
 * A season can be six rows deep and a remote has no "Home" key on the page, so without
 * this, climbing back to the masthead from the bottom is a long press-by-press haul. Its
 * own `X` group, so Down from the last grid row reaches it; [SpatialNav.focusEntry] moves
 * the ring to the masthead and, by centring it, scrolls the page up.
 */
@Composable
private fun TvBackToTop() {
    Div({ classes("tv-totop"); focusGroup("totop", FocusAxis.X) }) {
        Div({
            classes("tv-btn")
            focusItem("back-to-top")
            actsAsButton()
            onClick {
                document.querySelector(".tv-root")?.let { SpatialNav.focusEntry(it) }
            }
        }) { Text(S.backToTop.caps) }
    }
}
