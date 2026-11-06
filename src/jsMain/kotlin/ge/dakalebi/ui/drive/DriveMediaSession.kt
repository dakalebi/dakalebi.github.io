package ge.dakalebi.ui.drive

import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.i18n.S
import ge.dakalebi.ui.assetBase
import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.url.URL
import kotlin.js.json
import kotlin.math.max
import kotlin.math.min

/** `new MediaMetadata(init)`. Only constructed after checking the browser has it. */
private external class MediaMetadata(init: dynamic)

/**
 * [DrivePlayer]'s side of the media session.
 *
 * The lock screen, Control Center, a Bluetooth head unit and the steering-wheel
 * buttons all reach the page through this one object. Each system button calls back
 * into the player: [play], [pause], [skip] by seconds, [next] episode, [seek] to a time.
 */
internal class DriveMediaSession(
    play: () -> Unit,
    pause: () -> Unit,
    skip: (Double) -> Unit,
    next: () -> Unit,
    seek: (Double) -> Unit,
) {
    /** `undefined` where unsupported. */
    private val mediaSession: dynamic get() = window.navigator.asDynamic().mediaSession

    private val actions: List<Pair<String, (dynamic) -> Unit>> = listOf(
        "play" to { _ -> play() },
        "pause" to { _ -> pause() },
        // The system may say how far its own skip button goes. Honour it, so the icon it
        // draws and the distance moved agree.
        "seekbackward" to { details -> skip(-((details?.seekOffset as? Double) ?: SKIP_SECONDS)) },
        "seekforward" to { details -> skip((details?.seekOffset as? Double) ?: SKIP_SECONDS) },
        // Steering-wheel "previous" goes back 30 seconds rather than a whole episode: a
        // press by mistake should cost half a minute, not an episode.
        "previoustrack" to { _ -> skip(-SKIP_SECONDS) },
        "nexttrack" to { _ -> next() },
        "seekto" to { details -> (details?.seekTime as? Double)?.let { seek(it) } },
    )

    fun install() {
        val session = mediaSession ?: return
        for ((action, handler) in actions) {
            // A browser throws for an action it does not know. The others still count.
            runCatching { session.setActionHandler(action, handler) }
                .onFailure { Log.d("drive", "media session has no '$action' action") }
        }
    }

    fun clear() {
        val session = mediaSession ?: return
        for ((action, _) in actions) runCatching { session.setActionHandler(action, null) }
    }

    /** "playing" or "paused", for the system's own play button. */
    fun setPlaybackState(state: String) {
        runCatching { mediaSession?.playbackState = state }
    }

    /** What the lock screen shows: the episode, the series, and the app icon. */
    fun showEpisode(episode: Episode) {
        val session = mediaSession ?: return
        if (jsTypeOf(window.asDynamic().MediaMetadata) != "function") return
        val init: dynamic = json(
            "title" to S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber),
            "artist" to S.seriesTitle,
            "album" to S.driveMode,
            "artwork" to arrayOf(
                json(
                    // Absolute: the lock screen does not resolve against this page.
                    "src" to URL("${assetBase}icon-512.png", window.location.href).href,
                    "sizes" to "512x512",
                    "type" to "image/png",
                ),
            ),
        )
        runCatching { session.metadata = MediaMetadata(init) }
            .onFailure { Log.w("drive", "media session metadata rejected", it) }
    }

    fun clearEpisode() {
        runCatching { mediaSession?.metadata = null }
    }

    /** Lets the system draw a moving progress bar. Throws if position exceeds duration. */
    fun updatePosition(video: HTMLVideoElement) {
        val session = mediaSession ?: return
        val total = video.duration
        if (!total.isFinite() || total <= 0 || jsTypeOf(session.setPositionState) != "function") return
        runCatching {
            session.setPositionState(
                json(
                    "duration" to total,
                    "playbackRate" to video.playbackRate,
                    "position" to min(max(video.currentTime, 0.0), total),
                ),
            )
        }
    }
}
