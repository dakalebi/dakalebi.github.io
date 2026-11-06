package ge.dakalebi.next.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
import ge.dakalebi.next.components.QrCode
import ge.dakalebi.next.components.Spinner
import ge.dakalebi.next.components.Thumb
import ge.dakalebi.next.player.PlayerEvents
import ge.dakalebi.next.player.VideoPlayhead
import ge.dakalebi.next.tv.player.TvPlayer
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.presentation.PlaybackError
import ge.dakalebi.presentation.PlaybackViewModel
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLMediaElement
import org.w3c.dom.HTMLVideoElement

/**
 * The element, and the file it was playing and where, so a new file for the same
 * episode carries on from there.
 */
private class TvWatchRefs {
    var video: HTMLVideoElement? = null
    var heldSrc: String? = null
    var heldAt = 0.0
    var heldPlaying = true

    /** Called on every `timeupdate`. Not while a new file loads: it reports 0:00 then. */
    fun hold(video: HTMLVideoElement) {
        if (video.readyState < HTMLMediaElement.HAVE_METADATA || video.currentSrc.isEmpty()) return
        heldSrc = video.currentSrc
        heldAt = video.currentTime
        heldPlaying = !video.paused
    }
}

/**
 * What this screen draws from the playback state. The time updates several times a
 * second change the rest, and must not recompose the player and its shelf.
 */
private data class TvPlaybackView(
    val videoUrl: String?,
    val sources: Map<String, String>,
    val quality: String?,
    val error: PlaybackError?,
    val caveaUrl: String?,
)

/**
 * Playback on a television: [TvPlayer] with this episode's file, and the episodes after
 * and before it on the shelf.
 *
 * The rules are the shared [ge.dakalebi.presentation.PlaybackViewModel]'s, asked the
 * way the old TV screen asked them: the stored link at once and the fresh one when
 * Formula answers, the best rendition, one resume seek at `loadedmetadata` (a TV
 * WebView's seeks land), saves on pause and on leaving, and the next episode at the end
 * when autoplay is on.
 *
 * Two fixes over the old screen:
 * - A second file for the same episode (the fresh link, a quality pick, the retry after
 *   an error) carries on where the first one was. The old screen reloaded it at 0:00,
 *   because the resume had already landed.
 * - "From the start" on Home starts at 0:00 ([TvStartFrom]). The old button set an
 *   attribute nothing read, so it resumed.
 */
@Composable
internal fun TvWatch(episodeId: String) {
    val catalog = catalog()
    val settings = settings()
    val toasts = toasts()

    val playback = rememberPlaybackViewModel(episodeId, maxQuality = null, appleMobile = false)
    val refs = remember(playback) { TvWatchRefs() }

    // Whose progress this is: the account's, or a guest's on this device.
    val uid = sessionState().viewerId
    val episode = catalog.byId(episodeId)
    val nextEpisode = episode?.let { catalog.next(it) }
    val upcoming = episode?.let { catalog.upcoming(it, TvConfig.RAIL_COUNT) } ?: emptyList()
    val earlier = episode?.let { catalog.previous(it, TvConfig.RAIL_COUNT) } ?: emptyList()

    // The stored link before the state is read, so the first frame already has a player.
    remember(playback, episode?.id) { episode?.let { playback.startFrom(it) } }
    val playbackState = playback.collectState()
    val view by remember(playbackState) {
        derivedStateOf(structuralEqualityPolicy()) {
            playbackState.value.let { TvPlaybackView(it.videoUrl, it.sources, it.quality, it.error, it.caveaUrl) }
        }
    }

    LaunchedEffect(playback, episode?.id) {
        val current = episode ?: return@LaunchedEffect
        playback.resolve(current)
    }

    // A television does not background a tab, but it is switched off at the wall, and
    // leaving the screen is the moment that matters.
    DisposableEffect(playback, uid) {
        onDispose { playback.save() }
    }

    if (episode == null) {
        Div({ classes("tv-note") }) {
            if (catalog.loading) Spinner() else P { Text(S.episodeNotFound) }
        }
        return
    }

    val events = PlayerEvents(
        onElement = { video ->
            refs.video = video
            playback.attach(video?.let { VideoPlayhead(it) })
        },
        onLoadedMetadata = { video -> land(video, refs, episodeId, playback) },
        onTimeUpdate = { video ->
            refs.hold(video)
            playback.onTimeUpdate()
        },
        onPause = { playback.save() },
        onEnded = {
            playback.markWatched()
            if (settings[Setting.AutoplayNext] && nextEpisode != null) playback.goToNext()
        },
        onError = { playback.onMediaError(refs.video?.error?.code?.toInt()) },
    )

    // An episode that does not play here but is on Cavea Plus: the way to it, as a code
    // for a phone. Also after a file that failed to play, where there is a picture no more.
    val error = view.error
    val caveaUrl = view.caveaUrl
    if (error != null && caveaUrl != null && !catalog.loading) {
        TvCaveaNote(ErrorMessages.playback(error), caveaUrl)
        return
    }

    val url = view.videoUrl
    if (url == null || catalog.loading) {
        Div({ classes("tv-note") }) {
            if (error == null) Spinner() else P { Text(ErrorMessages.playback(error)) }
        }
        return
    }

    // Keyed, so the next episode gets a new element rather than this file's state.
    key(episode.id) {
        TvPlayer(
            src = url,
            sources = view.sources,
            quality = view.quality,
            onQualitySelected = { label ->
                playback.selectQuality(label) { toasts.error(S.settingNotSynced) }
            },
            events = events,
            title = S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps,
            subtitle = S.seriesTitle,
            autoplayNext = settings[Setting.AutoplayNext],
            onToggleAutoplay = {
                settings.toggle(Setting.AutoplayNext) { toasts.error(S.settingNotSynced) }
            },
            onFromStart = {
                refs.video?.let { video ->
                    runCatching { video.currentTime = 0.0 }
                        .onFailure { Log.w("tv-watch", "seek to the start rejected", it) }
                    playback.jumpedTo(0.0)
                    playback.watchFromStart()
                }
            },
            upNext = nextEpisode?.let { next -> @Composable { TvUpNextCard(next) } },
        ) {
            val otherSeason = { list: List<Episode> -> list.any { it.seasonNumber != episode.seasonNumber } }
            // The head of the next band is the true next episode, so a fresh Down lands there.
            if (upcoming.isNotEmpty()) {
                TvBand(S.nextEpisodes) {
                    TvEpisodeRow(
                        key = "player-next",
                        episodes = upcoming,
                        withSeason = otherSeason(upcoming),
                        entryId = upcoming.first().id,
                    )
                }
            }
            // The tail of the earlier band is the episode just before this one.
            if (earlier.isNotEmpty()) {
                TvBand(S.previousEpisodes) {
                    TvEpisodeRow(
                        key = "player-prev",
                        episodes = earlier,
                        withSeason = otherSeason(earlier),
                        entryId = earlier.last().id,
                    )
                }
            }
        }
    }
}

/**
 * Why this episode does not play, and a QR code that opens it on Cavea Plus on a phone.
 * Nothing on it takes focus, so Back leaves it as it leaves the spinner.
 */
@Composable
private fun TvCaveaNote(message: String, caveaUrl: String) {
    Div({ classes("tv-note") }) {
        Div({ classes("tv-cavea") }) {
            P({ classes("tv-cavea-title") }) { Text(message) }
            QrCode(caveaUrl, label = S.openOnCavea)
            P({ classes("tv-cavea-hint") }) { Text(S.scanToOpenOnCavea) }
        }
    }
}

/**
 * Where a newly loaded file starts: where the last file of this episode was, else 0:00
 * when "from the start" was asked, else the saved spot.
 */
private fun land(
    video: HTMLVideoElement,
    refs: TvWatchRefs,
    episodeId: String,
    playback: PlaybackViewModel,
) {
    val carryFrom = refs.heldSrc
    when {
        carryFrom != null && carryFrom != video.currentSrc && refs.heldAt > 1.0 -> {
            val target = refs.heldAt
            runCatching { video.currentTime = target }
                .onFailure { Log.w("tv-watch", "carry-over seek to ${target}s rejected", it) }
            playback.resumeLanded()
            playback.jumpedTo(target)
            // `autoplay` starts every file; a pause before the swap stays a pause.
            if (!refs.heldPlaying) video.pause()
        }

        TvStartFrom.take(episodeId) -> {
            runCatching { video.currentTime = 0.0 }
            playback.resumeLanded()
            playback.watchFromStart()
        }

        else -> {
            playback.resumeTarget()?.let { target ->
                runCatching { video.currentTime = target }
                    .onFailure { Log.w("tv-watch", "resume seek to ${target}s rejected", it) }
            }
            playback.resumeLanded()
        }
    }
}

/**
 * The card in the last minutes: the next episode's still and its number. Not a D-pad
 * stop; the way to play it now is the first tile of the shelf.
 */
@Composable
private fun TvUpNextCard(episode: Episode) {
    Div({ classes("tv-upnext-card") }) {
        Div({ classes("tv-upnext-still") }) { Thumb(episode) }
        Div({ classes("tv-upnext-text") }) {
            P({ classes("tv-upnext-eyebrow") }) { Text(S.nextEpisode.caps) }
            P({ classes("tv-upnext-title") }) {
                Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
            }
        }
    }
}
