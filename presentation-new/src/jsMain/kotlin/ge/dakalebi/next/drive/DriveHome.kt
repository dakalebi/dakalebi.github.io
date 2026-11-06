package ge.dakalebi.next.drive

import androidx.compose.runtime.Composable
import ge.dakalebi.di.catalog
import ge.dakalebi.di.router
import ge.dakalebi.di.session
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.Button
import ge.dakalebi.next.components.ButtonVariant
import ge.dakalebi.next.components.EmptyState
import ge.dakalebi.next.components.Glyph
import ge.dakalebi.next.components.Icon
import ge.dakalebi.next.components.Spinner
import ge.dakalebi.next.useFixtures
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.presentation.Route
import org.jetbrains.compose.web.dom.Button as HtmlButton
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * One button: the episode to watch next, already chosen (CarContinue in the brief).
 *
 * Whatever the player holds wins, so coming back here mid-episode offers that episode
 * rather than a different guess. Otherwise it is [ge.dakalebi.presentation.CatalogStore.nextToPlay].
 * The same choice as the old driving mode's home.
 */
@Composable
internal fun DriveHome(player: DrivePlayer) {
    val catalog = catalog()
    val session = session()

    Main({ classes("drive-page") }) {
        Div({ classes("drive-top") }) {
            // Back to the full site, the web page one folder up. The fixture
            // keeps its fixture, so a check of this page does not land on the real sign-in.
            Button(
                label = S.driveExit,
                icon = Glyph.ChevronLeft,
                href = if (useFixtures) "../?ui=demo" else "../",
                variant = ButtonVariant.Quiet,
                attrs = { classes("drive-exit") },
            )
        }

        Div({ classes("drive-home-body") }) {
            val loadError = catalog.loadError
            val episode = player.episodeId?.let { catalog.byId(it) } ?: catalog.nextToPlay
            when {
                catalog.loading -> Spinner()
                loadError != null -> EmptyState(
                    title = S.loadFailedEyebrow,
                    body = ErrorMessages.catalogLoad(loadError),
                    actionLabel = S.retry,
                    onAction = session::retryCatalogLoad,
                    glyph = Glyph.CircleAlert,
                )
                episode == null -> EmptyState(title = S.emptyEyebrow, body = S.emptyBody, glyph = Glyph.LayoutGrid)
                else -> CarContinue(episode, player)
            }
        }
    }
}

/**
 * The huge Continue, in the darker car amber. The label says whether it continues or
 * starts; the season above the episode, then the episode as the headline, then how much
 * is left, so the eye finds the one thing it needs at a glance.
 */
@Composable
private fun CarContinue(episode: Episode, player: DrivePlayer) {
    val catalog = catalog()
    val router = router()
    val entry = catalog.progress[episode.id]
    val loaded = player.episodeId == episode.id

    val position = if (loaded) player.position else entry?.takeIf { !it.isWatched }?.progressSeconds ?: 0
    val total = (if (loaded) player.duration else null) ?: episode.durationSeconds ?: entry?.durationSeconds
    val started = position > 0
    val left = minutesLeft(position, total)

    HtmlButton({
        classes("car-continue")
        onClick {
            // Started here, inside the tap. iOS refuses `play()` from anywhere else until
            // the element has been started once, and the player screen composes too late.
            player.open(episode, play = true)
            router.go(Route.Watch(episode.id))
        }
    }) {
        Span({ classes("car-continue-disc") }) { Icon(Glyph.Play) }
        Span({ classes("car-continue-text") }) {
            Span({ classes("car-continue-label") }) { Text((if (started) S.continueLabel else S.play).caps) }
            // Two lines rather than "Season 2 · Episode 4" on one: at this size the joined
            // form breaks wherever it likes, and the episode is what the eye wants.
            Span({ classes("car-continue-season") }) { Text(S.season(episode.seasonNumber)) }
            Span({ classes("car-continue-title") }) { Text(S.episode(episode.episodeNumber)) }
            if (left != null) Span({ classes("car-continue-meta") }) { Text(left) }
        }
        // How far in, as a line along the foot. Decorative: the words above say it.
        if (started && total != null && total > 0) {
            Span({ classes("car-continue-progress"); attr("aria-hidden", "true") }) {
                Span({
                    classes("car-continue-fill")
                    style { property("width", "${(position * 100.0 / total).coerceIn(0.0, 100.0).roundToInt()}%") }
                })
            }
        }
    }
}

/**
 * "16 min left", rounded up so the last half-minute still says 1. Null when unknown or
 * done. The old driving mode's rule, kept.
 */
internal fun minutesLeft(position: Int, total: Int?): String? {
    if (total == null || total <= 0) return null
    val left = total - position
    if (left <= 0) return null
    return S.minutesRemaining(ceil(left / 60.0).toInt())
}
