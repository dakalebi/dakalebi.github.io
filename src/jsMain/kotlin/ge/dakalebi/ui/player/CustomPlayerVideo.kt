package ge.dakalebi.ui.player

import androidx.compose.runtime.Composable
import ge.dakalebi.core.Log
import ge.dakalebi.next.player.PlayerEvents
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Video
import org.w3c.dom.events.MouseEvent

/**
 * [CustomVideoPlayer]'s `<video>`: the element, its clicks, and every media event,
 * each passed on to [events] after the player's own chrome has followed it.
 */
@Composable
internal fun CustomPlayerVideo(
    player: CustomPlayerState,
    src: String,
    autoPlay: Boolean,
    events: PlayerEvents,
) {
    val refs = player.refs
    Video({
        attr("src", src)
        attr("playsinline", "")
        attr("preload", "auto")
        // Enables AirPlay. Note there is deliberately no `crossorigin`:
        // cdn.formula.ge sends no CORS headers and adding it breaks playback.
        attr("x-webkit-airplay", "allow")
        if (autoPlay) attr("autoplay", "")

        ref { element ->
            refs.video = element
            events.onElement(element)
            onDispose {
                events.onElement(null)
                refs.video = null
            }
        }

        onClick { event -> player.clickVideo((event.nativeEvent as? MouseEvent)?.detail ?: 1) }
        onDoubleClick { player.doubleClickVideo() }

        addEventListener("loadedmetadata") {
            val v = refs.video ?: return@addEventListener
            player.syncClock()
            player.paintBars()
            refs.pendingSeek?.let { target ->
                refs.pendingSeek = null
                // Losing this seek silently is what a quality switch that
                // "jumps back to the start" actually looks like.
                runCatching { v.currentTime = target }
                    .onFailure { Log.w("player", "quality-swap seek to ${target}s failed", it) }
                if (refs.pendingPlay) v.play()
            }
            events.onLoadedMetadata(v)
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
            player.playing = true
            player.started = true
            player.buffering = false
            player.startLoop()
            player.revealControls()
            events.onPlay()
        }
        addEventListener("playing") {
            player.playing = true
            player.buffering = false
            player.startLoop()
        }
        addEventListener("pause") {
            player.playing = false
            player.buffering = false
            player.stopLoop()
            player.showControls = true
            events.onPause()
        }
        addEventListener("waiting") { player.buffering = refs.video?.paused == false }
        // `stalled` is deliberately NOT wired to the spinner. It means "no
        // data has arrived for three seconds", which for a progressively
        // downloaded MP4 is the normal state once the browser has read
        // ahead and stopped fetching — not a sign that playback halted.
        // Safari fires it throughout untroubled playback; Chrome does not.
        // `waiting` is the event that actually means "stopped, needs data",
        // and the animation loop clears it once the clock moves again.
        addEventListener("seeking") { player.buffering = true }
        addEventListener("seeked") {
            player.buffering = false
            player.paintBars()
            refs.video?.let { events.onSeeked(it) }
        }
        addEventListener("volumechange") {
            val v = refs.video ?: return@addEventListener
            player.volume = v.volume
            player.muted = v.muted
        }
        addEventListener("ended") {
            player.playing = false
            player.stopLoop()
            player.showControls = true
            events.onEnded()
        }
        addEventListener("error") { events.onError() }
    })
}

/**
 * Remote playback / AirPlay availability.
 *
 * An empty, hidden element whose `ref` runs once [CustomPlayerVideo]'s has, so the
 * video element is there to ask. Must come after it on the page.
 */
@Composable
internal fun CastAvailabilityWatcher(player: CustomPlayerState, events: PlayerEvents) {
    val refs = player.refs
    Div({
        ref { _ ->
            val v = refs.video?.asDynamic()
            if (v != null) {
                if (jsTypeOf(v.webkitShowPlaybackTargetPicker) == "function") {
                    player.castSupported = true
                }
                val remote = v.remote
                if (remote != null && jsTypeOf(remote.watchAvailability) == "function") {
                    player.castSupported = true
                    runCatching {
                        remote.watchAvailability { available: Boolean ->
                            player.castAvailable = available
                        }
                    }.onFailure { Log.w("cast", "watchAvailability rejected", it) }
                    remote.onconnect = { player.casting = true }
                    remote.ondisconnect = { player.casting = false }
                }
                v.addEventListener("webkitplaybacktargetavailabilitychanged", { e: dynamic ->
                    player.castAvailable = e.availability == "available"
                })
                v.addEventListener("webkitcurrentplaybacktargetiswirelesschanged", { _: dynamic ->
                    val wireless = v.webkitCurrentPlaybackTargetIsWireless == true
                    player.casting = wireless
                    refs.video?.let { events.onAirPlayChange(wireless, it) }
                })
            }
            onDispose { }
        }
        style { property("display", "none") }
    })
}
