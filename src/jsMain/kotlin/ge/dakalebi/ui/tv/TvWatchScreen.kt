package ge.dakalebi.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import ge.dakalebi.core.Log
import ge.dakalebi.di.catalog
import ge.dakalebi.di.collectState
import ge.dakalebi.di.rememberPlaybackViewModel
import ge.dakalebi.di.sessionState
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.presentation.PlaybackError
import ge.dakalebi.ui.Thumb
import ge.dakalebi.ui.player.PlayerEvents
import ge.dakalebi.ui.player.VideoPlayhead
import ge.dakalebi.ui.tv.input.TvInput
import ge.dakalebi.ui.tv.player.TvVideoPlayer
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLVideoElement

/**
 * The one input stack, reachable from the player.
 *
 * A `CompositionLocal` rather than a parameter threaded through three screens,
 * and TV-only so it stays out of `di/Locals.kt` — which is shared code that a
 * future Android app will read, and it has no input stack of this kind.
 */
internal val LocalTvInput = staticCompositionLocalOf<TvInput> {
    error("LocalTvInput was read outside TvApp")
}

/** The element, for the error code a media `error` event does not carry. */
private class TvWatchRefs {
    var video: HTMLVideoElement? = null
}

/**
 * What this screen draws from the playback state. The time updates four times a
 * second change the rest, and must not recompose the player and its rails.
 */
private data class TvPlaybackView(
    val videoUrl: String?,
    val sources: Map<String, String>,
    val quality: String?,
    val error: PlaybackError?,
)

/**
 * Playback on a television.
 *
 * The rules are the shared [ge.dakalebi.presentation.PlaybackViewModel]'s: which
 * file plays, saving every [ge.dakalebi.presentation.SAVE_EVERY] seconds, the one
 * retry, the resume target and leaving for the next episode. What it does not
 * take from the web is the web's workarounds. iOS Safari silently ignores a seek
 * issued before the element is seekable, so the web retries across four media
 * events and keeps an AirPlay clock. Chromium in an Android TV WebView has no such
 * behaviour, a seek at `loadedmetadata` lands, so the resume here is one seek.
 *
 * TV differs from the web in what it asks of the ViewModel:
 * - It plays the stored link at once and swaps in the fresh one when Formula
 *   answers, instead of showing "loading" for that round trip.
 * - It starts in the best rendition on any screen, and never shows the iPhone
 *   message: a file that will not play is just a failed load here.
 * - It never remembers playing or paused: every episode starts playing. So it
 *   saves through `save` and `markWatched`, not the web's media-event methods.
 * - It goes on to the next episode at the end when the viewer's autoplay setting
 *   says so.
 */
@Composable
fun TvWatchScreen(episodeId: String) {
    val catalog = catalog()
    val sessionState = sessionState()
    val settings = settings()
    val toasts = toasts()
    val input = LocalTvInput.current

    val playback = rememberPlaybackViewModel(episodeId, maxQuality = null, appleMobile = false)
    val refs = remember(playback) { TvWatchRefs() }

    // Whose progress this is: the account's, or a guest's on this device.
    val uid = sessionState.viewerId
    val episode = catalog.byId(episodeId)
    val progressReady = !catalog.loading
    val nextEpisode = episode?.let { catalog.next(it) }

    // The rails below the player chrome. `upcoming` and `previous` already exist on the
    // store, so these are lookups, not new logic. See [TvConfig.RAIL_COUNT] for the count.
    val upcoming = episode?.let { catalog.upcoming(it, TvConfig.RAIL_COUNT) } ?: emptyList()
    val earlier = episode?.let { catalog.previous(it, TvConfig.RAIL_COUNT) } ?: emptyList()

    // The stored link, before the state is read, so the first frame already has a
    // player. The fresh one replaces it below when Formula answers.
    remember(playback, episode?.id) { episode?.let { playback.startFrom(it) } }
    val playbackState = playback.collectState()
    val view by remember(playbackState) {
        derivedStateOf(structuralEqualityPolicy()) {
            playbackState.value.let { TvPlaybackView(it.videoUrl, it.sources, it.quality, it.error) }
        }
    }

    LaunchedEffect(playback, episode?.id) {
        val current = episode ?: return@LaunchedEffect
        playback.resolve(current)
    }

    // The saves the web screen makes on `visibilitychange` and `beforeunload`. A
    // television does not background a tab, but it does get switched off at the
    // wall, and leaving the screen is the moment that matters.
    DisposableEffect(playback, uid) {
        onDispose { playback.save() }
    }

    if (episode == null) {
        Div({ classes("tv-note") }) {
            Text(if (catalog.loading) S.loading else S.episodeNotFound)
        }
        return
    }

    val events = PlayerEvents(
        onElement = { video ->
            refs.video = video
            playback.attach(video?.let { VideoPlayhead(it) })
        },
        onLoadedMetadata = { video ->
            // The whole of resume, on a platform whose seeks land: one seek, once,
            // as soon as the saved spot is known.
            if (!catalog.loading) {
                playback.resumeTarget()?.let { target ->
                    runCatching { video.currentTime = target }
                        .onFailure { Log.w("resume", "seek to ${target}s rejected", it) }
                }
                playback.resumeLanded()
            }
        },
        onTimeUpdate = { playback.onTimeUpdate() },
        onPause = { playback.save() },
        onEnded = {
            playback.markWatched()
            if (settings[Setting.AutoplayNext] && nextEpisode != null) playback.goToNext()
        },
        onError = { playback.onMediaError(refs.video?.error?.code?.toInt()) },
    )

    val url = view.videoUrl
    if (url != null && progressReady) {
        // Keyed so moving to the next episode builds a new element rather than
        // reusing one that still holds the previous file's state.
        key(episode.id) {
            TvVideoPlayer(
                src = url,
                autoPlay = true,
                sources = view.sources,
                quality = view.quality,
                onQualitySelected = { label ->
                    playback.selectQuality(label) { toasts.error(S.settingNotSynced) }
                },
                input = input,
                events = events,
                title = S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps,
                subtitle = S.seriesTitle,
                // The same account-synced setting the drawer shows, flipped from the
                // control row so it is reachable mid-episode.
                autoplayNext = settings[Setting.AutoplayNext],
                onToggleAutoplay = {
                    settings.toggle(Setting.AutoplayNext) { toasts.error(S.settingNotSynced) }
                },
                chromeExtra = {
                    if (upcoming.isNotEmpty() || earlier.isNotEmpty()) {
                        Div({ classes("tv-player-rails") }) {
                            // The head of the next rail is the true next episode, so a
                            // fresh Down lands there rather than mid-row.
                            TvRail(
                                key = "player-next",
                                title = S.nextEpisodes.caps,
                                episodes = upcoming,
                                progress = catalog.progress,
                                entryEpisodeId = upcoming.firstOrNull()?.id,
                            )
                            // The tail of the previous rail is the episode just before
                            // this one, which is the one worth landing on.
                            TvRail(
                                key = "player-prev",
                                title = S.previousEpisodes.caps,
                                episodes = earlier,
                                progress = catalog.progress,
                                entryEpisodeId = earlier.lastOrNull()?.id,
                            )
                        }
                    }
                },
                upNext = nextEpisode?.let { next -> @Composable { TvUpNextCard(next) } },
            )
        }
    } else {
        Div({ classes("tv-note") }) { Text(view.error?.let { ErrorMessages.playback(it) } ?: S.loading) }
    }
}

/**
 * The body of the up-next overlay: the next episode's still and its number.
 *
 * Passed to the player as a slot so the player itself never learns what an episode is.
 * A plain card, not a link or a focus stop — the player decides when it shows and never
 * focuses it, so OK keeps meaning play/pause. Playing it manually is the up-next rail's
 * job.
 */
@Composable
private fun TvUpNextCard(episode: Episode) {
    Div({ classes("tv-upnext-card") }) {
        Div({ classes("tv-upnext-th") }) { Thumb(episode) }
        Div({ classes("tv-upnext-b") }) {
            Div({ classes("tv-upnext-eyebrow") }) { Text(S.nextEpisode.caps) }
            Div({ classes("tv-upnext-t") }) {
                Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
            }
        }
    }
}
