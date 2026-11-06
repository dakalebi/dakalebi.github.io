package ge.dakalebi.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ge.dakalebi.core.Log
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Video
import org.w3c.dom.HTMLVideoElement

/**
 * iPhone and iPad get Apple's own player, per the requirement — no custom
 * chrome at all. Beyond the requirement it is also the only way to get
 * picture-in-picture, the lock-screen controls and a reliable AirPlay picker
 * on iOS.
 *
 * There is no next-episode overlay here: you cannot draw over a native
 * fullscreen player, and the original app made the same call.
 */
@Composable
fun NativeVideoPlayer(
    src: String,
    autoPlay: Boolean,
    events: PlayerEvents,
) {
    val holder = remember { arrayOfNulls<HTMLVideoElement>(1) }

    Div({ classes("player") }) {
        Video({
            attr("src", src)
            attr("controls", "")
            attr("playsinline", "")
            attr("preload", "auto")
            attr("x-webkit-airplay", "allow")
            if (autoPlay) attr("autoplay", "")

            ref { element ->
                holder[0] = element
                events.onElement(element)

                val dyn = element.asDynamic()
                val syncAirPlay = { _: dynamic ->
                    val remoteState = dyn.remote?.state as? String
                    val wireless = dyn.webkitCurrentPlaybackTargetIsWireless == true
                    val active = wireless || remoteState == "connected" || remoteState == "connecting"
                    events.onAirPlayChange(active, element)
                }
                dyn.addEventListener("webkitcurrentplaybacktargetiswirelesschanged", syncAirPlay)
                // `remote` is absent on older WebKit; the webkit-prefixed event
                // above still covers AirPlay there, so this is not an error.
                //
                // Call the listener directly rather than through `.invoke(...)`.
                // On a `dynamic` value Kotlin emits that literally, and a JS
                // function has no `invoke` property, so every one of these threw
                // `addEventListener.invoke is not a function` and none of the
                // Remote Playback events were ever wired up.
                runCatching {
                    val remote = dyn.remote
                    if (remote != null) {
                        remote.addEventListener("connect", syncAirPlay)
                        remote.addEventListener("connecting", syncAirPlay)
                        remote.addEventListener("disconnect", syncAirPlay)
                    }
                }.onFailure { Log.d("cast", "Remote Playback API unavailable", it) }
                syncAirPlay(null)

                onDispose {
                    runCatching {
                        dyn.removeEventListener("webkitcurrentplaybacktargetiswirelesschanged", syncAirPlay)
                        val remote = dyn.remote
                        if (remote != null) {
                            remote.removeEventListener("connect", syncAirPlay)
                            remote.removeEventListener("connecting", syncAirPlay)
                            remote.removeEventListener("disconnect", syncAirPlay)
                        }
                    }.onFailure { Log.d("cast", "AirPlay listener teardown failed", it) }
                    events.onElement(null)
                    holder[0] = null
                }
            }

            addEventListener("loadedmetadata") { holder[0]?.let(events.onLoadedMetadata) }
            addEventListener("loadeddata") { holder[0]?.let(events.onLoadedData) }
            addEventListener("canplay") { holder[0]?.let(events.onCanPlay) }
            addEventListener("durationchange") { holder[0]?.let(events.onDurationChange) }
            addEventListener("timeupdate") { holder[0]?.let(events.onTimeUpdate) }
            addEventListener("seeked") { holder[0]?.let(events.onSeeked) }
            addEventListener("play") { events.onPlay() }
            addEventListener("pause") { events.onPause() }
            addEventListener("ended") { events.onEnded() }
            addEventListener("error") {
                Log.e("player", "media error (code=${holder[0]?.asDynamic()?.error?.code}, src=$src)")
                events.onError()
            }
        })
    }
}
