package ge.dakalebi.ui.drive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import ge.dakalebi.core.formatTime
import ge.dakalebi.di.catalog
import ge.dakalebi.di.router
import ge.dakalebi.di.session
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.service.PlaybackFailure
import ge.dakalebi.domain.service.vlcStreamUrl
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.Route
import ge.dakalebi.ui.player.isAppleMobile
import ge.dakalebi.ui.useFixtures
import io.github.bchmsl.keel.dom.classNames
import io.github.bchmsl.keel.dom.switchClasses
import io.github.bchmsl.keel.dom.switchKnobClasses
import io.github.bchmsl.keel.icons.Icon
import io.github.bchmsl.keel.icons.LucideIcon
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.ceil

/**
 * One button: the episode to watch next, already chosen.
 *
 * Whatever the player holds wins, so coming back here mid-episode offers that episode
 * rather than a different guess. Otherwise it is [ge.dakalebi.presentation.CatalogStore.nextToPlay].
 */
@Composable
internal fun DriveHomeScreen(player: DrivePlayer) {
    val catalog = catalog()
    val session = session()
    val scope = rememberCoroutineScope()

    Div({ classes("drive-screen") }) {
        Div({ classes("drive-top") }) {
            // Back to the full site. The fixture keeps its fixture, so a check of this page
            // does not land on the production sign-in.
            A(href = if (useFixtures) "../?ui=demo" else "../", attrs = { classes("drive-link") }) {
                Icon(LucideIcon.ChevronLeft, size = null)
                Text(S.driveExit)
            }
        }

        Div({ classes("drive-body", "drive-home") }) {
            val loadError = catalog.loadError
            val episode = player.episodeId?.let { catalog.byId(it) } ?: catalog.nextToPlay
            when {
                catalog.loading -> Div({ classes("drive-note") }) { Text(S.loading) }
                loadError != null -> {
                    Div({ classes("drive-note") }) { Text(S.catalogUnavailable) }
                    Button({
                        classes("drive-btn")
                        onClick { session.uid?.let { uid -> scope.launch { catalog.ensureLoaded(uid) } } }
                    }) { Text(S.retry.caps) }
                }
                episode == null -> Div({ classes("drive-note") }) { Text(S.emptyBody) }
                else -> DriveStartButton(episode, player)
            }
        }
    }
}

@Composable
private fun DriveStartButton(episode: Episode, player: DrivePlayer) {
    val catalog = catalog()
    val router = router()
    val entry = catalog.progress[episode.id]
    val loaded = player.episodeId == episode.id

    val position = if (loaded) player.position else entry?.takeIf { !it.isWatched }?.progressSeconds ?: 0
    val total = (if (loaded) player.duration else null) ?: episode.durationSeconds ?: entry?.durationSeconds
    val started = position > 0

    Button({
        classes("drive-start")
        onClick {
            // Started here, inside the tap. iOS refuses `play()` from anywhere else until
            // the element has been started once, and the player screen composes too late.
            player.open(episode, play = true)
            router.go(Route.Watch(episode.id))
        }
    }) {
        Icon(LucideIcon.Play, size = null, className = "drive-start-ic")
        Span({ classes("drive-start-text") }) {
            Span({ classes("drive-start-label") }) { Text((if (started) S.continueLabel else S.play).caps) }
            // Two lines rather than "Season 2 · Episode 4" on one: at this size the
            // joined form breaks wherever it likes, and the episode is what the eye wants.
            Span({ classes("drive-start-season") }) { Text(S.season(episode.seasonNumber)) }
            Span({ classes("drive-start-title") }) { Text(S.episode(episode.episodeNumber)) }
            minutesLeft(position, total)?.let { Span({ classes("drive-start-meta") }) { Text(it) } }
        }
    }
}

/**
 * The native player, with the controls a driver reaches for drawn large around it:
 * play/pause, back and forward 30 seconds, the next episode, and the autoplay switch.
 * The player's own controls stay too, for fullscreen, AirPlay and scrubbing.
 */
@Composable
internal fun DrivePlayerScreen(episodeId: String, player: DrivePlayer) {
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

    Div({ classes("drive-screen", "drive-watch") }) {
        Div({ classes("drive-top") }) {
            // The arrow alone: with the autoplay label beside it, the Georgian word for
            // "home" pushes the bar onto two rows and the controls below the toolbar.
            Button({
                classes("drive-link", "drive-back")
                attr("aria-label", S.home)
                onClick { router.go(Route.Dashboard) }
            }) {
                Icon(LucideIcon.ChevronLeft, size = null)
            }
            DriveAutoplaySwitch()
        }

        if (episode == null) {
            Div({ classes("drive-body") }) {
                Div({ classes("drive-note") }) { Text(if (catalog.loading) S.loading else S.episodeNotFound) }
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

    Div({ classes("drive-body", "drive-player") }) {
        Div({ classes("drive-now") }) {
            // The season above, the episode as the headline: the same split as the home button.
            Div({ classes("drive-eyebrow") }) { Text(S.season(episode.seasonNumber).caps) }
            H1({ classes("drive-title") }) { Text(S.episode(episode.episodeNumber)) }
            Div({ classes("drive-time") }) {
                Span({ classes("drive-clock") }) {
                    Text(formatTime(position.toDouble()) + (total?.let { " / " + formatTime(it.toDouble()) } ?: ""))
                }
                minutesLeft(position, total)?.let { Span { Text(it) } }
            }
        }

        // The picture and the actions under it. On a phone on its side this is the left
        // column; upright it sits between the title and the playback controls.
        Div({ classes("drive-side") }) {
            Div({ classes("drive-stage") }) {
                // The player's one `<video>` is lent to this frame while the screen is open.
                // It is not a Compose child: it is the same element for the whole visit (see
                // DrivePlayer).
                Div({
                    classes("drive-video")
                    ref { slot ->
                        player.attach(slot)
                        onDispose { player.detach(slot) }
                    }
                })
                // Over the picture, which is blank whenever this shows, so it costs the
                // controls no room.
                val failure = player.failure
                if (loaded && failure != null) {
                    Div({ classes("drive-error"); attr("role", "alert") }) {
                        Text(
                            when (failure) {
                                PlaybackFailure.UnsupportedOnAppleMobile -> S.videoUnsupportedOnAppleMobile
                                PlaybackFailure.LoadFailed -> S.videoLoadFailed
                            },
                        )
                    }
                }
            }
            DriveExtras(episode, player, loaded)
        }

        Div({ classes("drive-controls") }) {
            Div({ classes("drive-row") }) {
                Button({
                    classes("drive-btn")
                    attr("aria-label", S.skipBack(SKIP_SECONDS.toInt()))
                    if (!loaded) disabled()
                    onClick { player.skip(-SKIP_SECONDS) }
                }) {
                    Icon(LucideIcon.RotateCcw, size = null)
                    Span { Text("${SKIP_SECONDS.toInt()}") }
                }
                Button({
                    classNames("drive-play", "drive-busy".takeIf { loaded && player.buffering && player.playing })
                    attr("aria-label", if (playing) S.pause else S.play)
                    onClick {
                        // The route can name an episode the player has not loaded yet, on a reload
                        // or before the effect above has run. This tap is the gesture iOS wants.
                        if (player.episodeId != episode.id) player.open(episode, play = true) else player.toggle()
                    }
                }) {
                    Icon(if (playing) LucideIcon.Pause else LucideIcon.Play, size = null, className = "drive-play-ic")
                }
                Button({
                    classes("drive-btn")
                    attr("aria-label", S.skipForward(SKIP_SECONDS.toInt()))
                    if (!loaded) disabled()
                    onClick { player.skip(SKIP_SECONDS) }
                }) {
                    Span { Text("${SKIP_SECONDS.toInt()}") }
                    Icon(LucideIcon.RotateCw, size = null)
                }
            }

            Button({
                classes("drive-btn", "drive-next")
                if (!loaded || !player.hasNext) disabled()
                onClick { player.next() }
            }) {
                Span { Text(S.nextEpisodeAction.caps) }
                Icon(LucideIcon.SkipForward, size = null)
            }
        }
    }
}

/**
 * The two actions the web page has under its player, at car size: mark the episode
 * watched, and on an iPhone or iPad open it in VLC.
 */
@Composable
private fun DriveExtras(episode: Episode, player: DrivePlayer, loaded: Boolean) {
    val catalog = catalog()
    val toasts = toasts()
    val watched = catalog.progress[episode.id]?.isWatched == true
    val vlcUrl = player.sourceUrl.takeIf { loaded && isAppleMobile }

    Div({ classes("drive-extras") }) {
        Button({
            classes("drive-btn", "drive-extra")
            // Shown as done rather than offered back: undoing it asks for a
            // confirmation, which belongs on the web page, not in a car.
            if (watched) {
                classes("drive-done")
                disabled()
            } else if (!loaded) {
                disabled()
            }
            onClick {
                player.markWatched()
                toasts.ok(S.episodeMarkedWatched)
            }
        }) {
            if (watched) {
                Text(S.watchedTick.caps)
            } else {
                Icon(LucideIcon.Check, size = null)
                Span { Text(S.markAsWatched.caps) }
            }
        }

        // As on the web page: every episode on an iPhone or iPad, since VLC also plays
        // what Safari cannot (all of season 2). VLC always starts at 0:00, so the button
        // carries the time to seek to. The page's own player pauses first, so the two
        // never play at once.
        if (vlcUrl != null) {
            A(href = vlcStreamUrl(vlcUrl), attrs = {
                classes("drive-btn", "drive-extra", "drive-vlc")
                onClick { player.pause() }
            }) {
                Span { Text(S.openInVlc.caps) }
                if (player.position >= 1) {
                    Span({ classes("drive-vlc-time") }) { Text(formatTime(player.position.toDouble())) }
                }
            }
        }
    }
}

/**
 * The account's own autoplay setting, the one the web drawer and the TV player show.
 * Not a driving-mode copy of it: the next episode starting by itself is the feature
 * here, so it must agree with every other device.
 */
@Composable
private fun DriveAutoplaySwitch() {
    val settings = settings()
    val toasts = toasts()
    val scope = rememberCoroutineScope()
    val on = settings.autoplayNext

    Button({
        classes("drive-toggle")
        attr("role", "switch")
        attr("aria-checked", on.toString())
        onClick { settings.setAutoplayNext(scope, !on) { toasts.error(S.settingNotSynced) } }
    }) {
        Span { Text(S.autoplayShort.caps) }
        // The same keel switch the drawer draws. Its `aria-checked` copy is only for
        // keel's rule to paint; the role lives on the button around it.
        Span({ classNames(switchClasses()); attr("aria-checked", on.toString()) }) {
            Span({ classNames(switchKnobClasses()) })
        }
    }
}

/** "16 min left", rounded up so the last half-minute still says 1. Null when unknown or done. */
private fun minutesLeft(position: Int, total: Int?): String? {
    if (total == null || total <= 0) return null
    val left = total - position
    if (left <= 0) return null
    return S.minutesRemaining(ceil(left / 60.0).toInt())
}
