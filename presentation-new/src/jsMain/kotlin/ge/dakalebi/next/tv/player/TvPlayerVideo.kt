package ge.dakalebi.next.tv.player

import androidx.compose.runtime.Composable
import ge.dakalebi.next.player.PlayerEvents
import org.jetbrains.compose.web.dom.Video

/**
 * `TvVideoPlayer`'s `<video>`: the element and every media event, each passed on to
 * [events] after the player's own chrome has followed it.
 *
 * Carried over from the web player, which has been exercised against real playback,
 * less what a remote cannot produce: no click or double-click handling.
 */
@Composable
fun TvPlayerVideo(
    player: TvPlayerState,
    src: String,
    autoPlay: Boolean,
    events: PlayerEvents,
) {
    val refs = player.refs
    Video({
        attr("src", src)
        attr("playsinline", "")
        attr("preload", "auto")
        // No `crossorigin`. cdn.formula.ge sends no CORS headers and adding
        // the attribute breaks playback outright.
        if (autoPlay) attr("autoplay", "")

        ref { element ->
            refs.video = element
            events.onElement(element)
            onDispose {
                events.onElement(null)
                refs.video = null
            }
        }

        addEventListener("loadedmetadata") {
            player.syncClock(); player.paintBars()
            refs.video?.let { events.onLoadedMetadata(it) }
        }
        addEventListener("loadeddata") {
            refs.video?.let { player.buffering = false; events.onLoadedData(it) }
        }
        addEventListener("canplay") {
            refs.video?.let { player.buffering = false; events.onCanPlay(it) }
        }
        addEventListener("durationchange") {
            player.syncClock()
            refs.video?.let { events.onDurationChange(it) }
        }
        addEventListener("timeupdate") {
            player.syncClock()
            refs.video?.let { events.onTimeUpdate(it) }
        }
        addEventListener("progress") { player.paintBars() }
        addEventListener("play") {
            player.playing = true; player.buffering = false
            player.startLoop(); player.revealControls()
            events.onPlay()
        }
        addEventListener("playing") { player.playing = true; player.buffering = false; player.startLoop() }
        addEventListener("pause") {
            player.playing = false; player.buffering = false
            player.stopLoop()
            // Paused chrome stays up: there is nothing to watch behind it. Not
            // mid-scrub: the scrub pauses the video itself and this event lands a
            // moment later, so forcing Controls here ended the gesture it belongs
            // to. OK then toggled play instead of committing, and the ghost went out.
            if (player.mode != Mode.Scrubbing) player.mode = Mode.Controls
            events.onPause()
        }
        addEventListener("waiting") { player.buffering = refs.video?.paused == false }
        addEventListener("stalled") { player.buffering = refs.video?.paused == false }
        addEventListener("seeking") { player.buffering = true }
        addEventListener("seeked") {
            player.buffering = false; player.paintBars()
            refs.video?.let { events.onSeeked(it) }
        }
        addEventListener("ended") {
            player.playing = false; player.stopLoop()
            player.mode = Mode.Controls
            events.onEnded()
        }
        addEventListener("error") { events.onError() }
    })
}
