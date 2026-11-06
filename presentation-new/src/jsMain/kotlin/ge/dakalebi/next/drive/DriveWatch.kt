package ge.dakalebi.next.drive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.core.formatTime
import ge.dakalebi.di.catalog
import ge.dakalebi.di.router
import ge.dakalebi.di.toasts
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.service.vlcOpenUrl
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.Button
import ge.dakalebi.next.components.ButtonVariant
import ge.dakalebi.next.components.ConfirmDialog
import ge.dakalebi.next.components.Glyph
import ge.dakalebi.next.components.IconButton
import ge.dakalebi.next.components.IconButtonVariant
import ge.dakalebi.next.components.Notice
import ge.dakalebi.next.components.Spinner
import ge.dakalebi.next.player.isAppleMobile
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.presentation.Route
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The native player, with the controls a driver reaches for drawn large around it:
 * back and forward 30 seconds round a 112px play, then the previous and next episodes
 * as two big labelled buttons. No scrubber while driving (the brief); the player's own
 * controls stay for full screen, AirPlay and scrubbing when parked.
 *
 * Upright, it reads top to bottom: the way back, what is playing, the picture with the
 * episode's actions under it, and the controls pushed to the bottom, where the thumb
 * finds them at the same place on every visit. On its side (`drive.css`), the picture
 * takes the left at full height and the rest stacks on the right.
 */
@Composable
internal fun DriveWatch(episodeId: String, player: DrivePlayer) {
    val catalog = catalog()
    val router = router()
    val episode = catalog.byId(episodeId)

    // A reload, or a link straight to an episode, arrives with no tap to play from. So
    // this only loads; the big button, or the player's own, starts it.
    LaunchedEffect(episode?.id, catalog.loading) {
        if (episode != null && !catalog.loading && player.episodeId != episode.id) {
            player.open(episode, play = false)
        }
    }

    Main({ classes("drive-page", "drive-watch") }) {
        Div({ classes("drive-top") }) {
            // The arrow alone, so the bar stays one short row and leaves the height to
            // the picture and the controls. Named, since it has no words.
            IconButton(
                Glyph.ChevronLeft,
                label = S.home,
                onClick = { router.go(Route.Dashboard) },
                variant = IconButtonVariant.Secondary,
            )
        }

        if (episode == null) {
            Div({ classes("drive-missing") }) {
                if (catalog.loading) Spinner() else P({ classes("drive-note") }) { Text(S.episodeNotFound) }
            }
        } else {
            DrivePlayerBody(episode, player)
        }
    }
}

@Composable
private fun DrivePlayerBody(episode: Episode, player: DrivePlayer) {
    val loaded = player.episodeId == episode.id
    val position = if (loaded) player.position else 0
    val total = (if (loaded) player.duration else null) ?: episode.durationSeconds
    val playing = loaded && player.playing

    // The season above, the episode as the headline: the same split as the home button.
    Div({ classes("drive-now") }) {
        P({ classes("drive-eyebrow") }) { Text(S.season(episode.seasonNumber).caps) }
        H1({ classes("drive-title") }) { Text(S.episode(episode.episodeNumber)) }
        P({ classes("drive-time") }) {
            Span({ classes("drive-clock") }) {
                Text(formatTime(position.toDouble()) + (total?.let { " / " + formatTime(it.toDouble()) } ?: ""))
            }
            minutesLeft(position, total)?.let { Span { Text(it) } }
        }
    }

    // The picture and the episode's actions under it: the left column on its side.
    Div({ classes("drive-side") }) {
        Div({ classes("drive-stage") }) {
            // The player's one `<video>` is lent to this frame while the screen is open.
            // It is not a Compose child: it is the same element for the whole visit
            // (see DrivePlayer).
            Div({
                classes("drive-video")
                ref { slot ->
                    player.attach(slot)
                    onDispose { player.detach(slot) }
                }
            })
            val failure = player.failure
            when {
                // Over the picture, which is blank whenever this shows, so it costs the
                // controls no room.
                loaded && failure != null -> Div({ classes("drive-stage-over") }) {
                    Notice(title = ErrorMessages.playback(failure))
                }
                loaded && player.buffering && player.playing -> Div({ classes("drive-stage-over") }) {
                    Spinner(onMedia = true)
                }
            }
        }
        DriveExtras(episode, player, loaded)
    }

    Div({ classes("drive-controls") }) {
        Div({ classes("drive-transport") }) {
            IconButton(
                Glyph.Back30,
                label = S.skipBack(SKIP_SECONDS.toInt()),
                onClick = { player.skip(-SKIP_SECONDS) },
                variant = IconButtonVariant.Secondary,
                disabled = !loaded,
            )
            IconButton(
                if (playing) Glyph.Pause else Glyph.Play,
                label = if (playing) S.pause else S.play,
                onClick = {
                    // The route can name an episode the player has not loaded yet, on a
                    // reload or before the effect above has run. This tap is the gesture
                    // iOS wants.
                    if (player.episodeId != episode.id) player.open(episode, play = true) else player.toggle()
                },
                variant = IconButtonVariant.Primary,
                attrs = { classes("drive-play") },
            )
            IconButton(
                Glyph.Forward30,
                label = S.skipForward(SKIP_SECONDS.toInt()),
                onClick = { player.skip(SKIP_SECONDS) },
                variant = IconButtonVariant.Secondary,
                disabled = !loaded,
            )
        }

        // Previous and next side by side, each over its label (the brief moves them out
        // of the transport row to buttons of their own).
        Div({ classes("drive-steps") }) {
            Button(
                label = S.previousEpisodeAction,
                onClick = { player.previous() },
                icon = Glyph.SkipBack,
                disabled = !loaded || !player.hasPrevious,
                attrs = { classes("drive-step") },
            )
            Button(
                label = S.nextEpisodeAction,
                onClick = { player.next() },
                icon = Glyph.SkipForward,
                disabled = !loaded || !player.hasNext,
                attrs = { classes("drive-step") },
            )
        }
    }
}

/**
 * The actions under the picture: full screen, mark watched and, on an iPhone or iPad,
 * open in VLC. The old driving mode's three, pressed once a visit while parked rather
 * than while driving.
 */
@Composable
private fun DriveExtras(episode: Episode, player: DrivePlayer, loaded: Boolean) {
    val catalog = catalog()
    val toasts = toasts()
    val watched = catalog.progress[episode.id]?.isWatched == true
    val vlcUrl = player.sourceUrl.takeIf { loaded && isAppleMobile }
    var confirmClear by remember(episode.id) { mutableStateOf(false) }

    Div({ classes("drive-extras") }) {
        // The native player has a fullscreen button of its own, but a small one, hidden
        // until the picture is tapped. This one waits for the video to know its picture,
        // which an iPhone needs before it opens its fullscreen player.
        Button(
            label = S.fullscreen,
            onClick = { player.enterFullscreen() },
            icon = Glyph.Maximize,
            disabled = !loaded || !player.fullscreenReady,
            attrs = { classes("drive-extra") },
        )

        // Watched undoes through the web page's own confirmation: a tap by mistake must
        // not cost the episode's place.
        Button(
            label = if (watched) S.watchedLabel else S.markAsWatched,
            onClick = {
                if (watched) {
                    confirmClear = true
                } else {
                    player.markWatched()
                    toasts.ok(S.episodeMarkedWatched)
                }
            },
            icon = Glyph.Check,
            disabled = !loaded,
            attrs = {
                classes("drive-extra")
                if (watched) classes("drive-extra--done")
            },
        )

        // As on the web page: every episode on an iPhone or iPad, since VLC also plays
        // what Safari cannot (all of season 2). This episode alone, starting where this
        // player is; the page's own player pauses first, so the two never play at once.
        if (vlcUrl != null) {
            val href = vlcOpenUrl(
                current = episode,
                currentUrl = vlcUrl,
                upcoming = emptyList(),
                quality = player.quality,
                startSeconds = player.position,
                title = { S.seasonAndEpisode(it.seasonNumber, it.episodeNumber) },
            )
            Button(
                label = S.openInVlc + if (player.position >= 1) "  ${formatTime(player.position.toDouble())}" else "",
                onClick = { player.pause() },
                icon = Glyph.ExternalLink,
                href = href,
                attrs = { classes("drive-extra") },
            )
        }
    }

    // The web page's dialog and words, so undoing "watched" means the same everywhere.
    if (confirmClear) {
        ConfirmDialog(
            title = S.clearProgressTitle,
            body = S.clearProgressBody,
            confirmLabel = S.delete,
            onConfirm = {
                confirmClear = false
                player.clearProgress { cleared ->
                    if (cleared) toasts.ok(S.progressCleared) else toasts.error(S.clearFailed)
                }
            },
            onDismiss = { confirmClear = false },
        )
    }
}
