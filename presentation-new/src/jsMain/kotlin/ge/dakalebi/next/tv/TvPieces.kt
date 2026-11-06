package ge.dakalebi.next.tv

import androidx.compose.runtime.Composable
import ge.dakalebi.di.catalog
import ge.dakalebi.di.session
import ge.dakalebi.di.sessionState
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.EmptyState
import ge.dakalebi.next.components.EpisodeTile
import ge.dakalebi.next.components.Glyph
import ge.dakalebi.next.components.SeasonRing
import ge.dakalebi.next.components.Skeleton
import ge.dakalebi.next.components.SkeletonShape
import ge.dakalebi.next.components.seasonLabel
import ge.dakalebi.next.components.tvItem
import ge.dakalebi.next.tv.focus.FocusAxis
import ge.dakalebi.next.tv.focus.focusGroup
import ge.dakalebi.next.tv.focus.focusItem
import ge.dakalebi.next.web.count
import ge.dakalebi.presentation.DashboardViewModel
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLAnchorElement

/**
 * "From the start" for the next episode opened, once.
 *
 * Set by a button that opens an episode at 0:00 rather than where it was left, and taken
 * by the watch screen when the video knows its length. A holder rather than a route
 * parameter: the address of an episode is the same whichever way it was opened, and a
 * reload or a shared link should resume.
 *
 * The old TV app marked the button with an attribute nothing read, so it resumed.
 */
internal object TvStartFrom {
    private var episodeId: String? = null

    fun set(id: String) {
        episodeId = id
    }

    /** Whether [id] was asked to start from 0:00. Clears the request either way. */
    fun take(id: String): Boolean {
        val asked = episodeId == id
        episodeId = null
        return asked
    }
}

/** A place's title, at the top of its page. */
@Composable
internal fun TvHead(title: String) {
    H1({ classes("tv-head") }) { Text(title.caps) }
}

/**
 * One titled band: a section label with an optional figure beside it, then [content],
 * which is one focus group. The band is the unit the player's shelf counts.
 */
@Composable
internal fun TvBand(title: String, meta: String? = null, content: @Composable () -> Unit) {
    Section({ classes("tv-band") }) {
        H2({ classes("tv-band-head") }) {
            Span({ classes("tv-band-title") }) { Text(title.caps) }
            if (meta != null) Span({ classes("tv-band-meta") }) { Text(meta) }
        }
        content()
    }
}

/**
 * A sideways row of episode tiles: one D-pad row filed under [key], scrolling on its
 * own. [entryId] is where the ring lands when the row is entered for the first time.
 */
@Composable
internal fun TvEpisodeRow(
    key: String,
    episodes: List<Episode>,
    withSeason: Boolean,
    entryId: String? = null,
) {
    val catalog = catalog()
    Div({
        classes("tv-row")
        focusGroup(key, FocusAxis.X)
    }) {
        episodes.forEach { episode ->
            EpisodeTile(
                episode = episode,
                progress = catalog.progress[episode.id],
                href = Router.href(Route.Watch(episode.id)),
                withSeason = withSeason,
                attrs = { focusItem(episode.id, entry = episode.id == entryId) },
            )
        }
    }
}

/**
 * One season as a ring with its count under it, a link to that season. [attrs] adds the
 * D-pad stop and, on Seasons, what a press does instead of following the link.
 */
@Composable
internal fun TvSeasonLink(
    season: Int,
    currentSeason: Int?,
    selected: Boolean = false,
    attrs: AttrsScope<HTMLAnchorElement>.() -> Unit,
) {
    val count = catalog().count(season)
    A(Router.href(Route.Season(season)), {
        classes("tv-season")
        attr("aria-label", seasonLabel(season, count.watched, count.total))
        if (selected) attr("aria-current", "page")
        attrs()
    }) {
        SeasonRing(season, count.watched, count.total, current = season == currentSeason)
        Span({ classes("tv-season-count"); attr("aria-hidden", "true") }) {
            Text("${count.watched} / ${count.total}")
        }
    }
}

/**
 * The catalog's three ways of having nothing to show, then [content]: a placeholder
 * while it loads, a retry when it failed, and an empty state when it has no episodes yet.
 * The same words as the web's; the retry is a D-pad stop with the ring on it.
 */
@Composable
internal fun TvCatalogGate(dashboard: DashboardViewModel, content: @Composable () -> Unit) {
    val catalog = catalog()
    val session = session()
    val state = sessionState()

    when {
        catalog.loading -> Div({
            classes("tv-loading")
            attr("aria-busy", "true")
        }) {
            Span({ classes("visually-hidden") }) { Text(S.loading) }
            Skeleton(SkeletonShape.Line)
            Div({ classes("tv-loading-row") }) { repeat(5) { Skeleton(SkeletonShape.Tile) } }
            Div({ classes("tv-loading-row") }) { repeat(5) { Skeleton(SkeletonShape.Tile) } }
        }

        catalog.loadError != null -> Div({ classes("tv-gate"); focusGroup("gate", FocusAxis.Y) }) {
            EmptyState(
                title = S.loadFailedEyebrow,
                body = ErrorMessages.catalogLoad(catalog.loadError),
                actionLabel = S.retry,
                onAction = session::retryCatalogLoad,
                glyph = Glyph.CircleAlert,
                actionAttrs = tvItem("gate-retry", entry = true),
            )
        }

        catalog.episodes.isEmpty() -> Div({ classes("tv-gate"); focusGroup("gate", FocusAxis.Y) }) {
            EmptyState(
                title = S.emptyEyebrow,
                body = when {
                    !state.isAdmin -> "${S.emptyBody} ${S.waitForAdmin}"
                    catalog.refreshing -> catalog.refreshNote ?: S.refreshing
                    else -> S.emptyBody
                },
                actionLabel = S.downloadEpisodes.takeIf { state.isAdmin && !catalog.refreshing },
                onAction = dashboard::refreshCatalog,
                glyph = Glyph.LayoutGrid,
                actionAttrs = tvItem("gate-download", entry = true),
            )
        }

        else -> content()
    }
}
