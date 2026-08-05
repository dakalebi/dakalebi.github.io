package ge.dakalebi.web.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * The player, split into what the chrome reads and what the chrome asks for.
 *
 * A canvas cannot decode video, so the picture is always something else's — a DOM `<video>` here,
 * a native surface later. That makes this boundary the important one in the 2.0 player: the chrome
 * above it is ordinary Compose and moves between platforms unchanged, while everything that knows
 * about a decoder stays behind [VideoCommands] and [VideoSurface].
 */

/**
 * Everything on screen about the current playback, as Compose state.
 *
 * Written by the surface from media events, read by the chrome. Position updates a few times a
 * second, so anything reading it should be small: recomposition stops at the composable that read
 * the field, which is why the seek bar and the clock read it and the screen around them does not.
 */
class VideoState {
    var positionSeconds: Double by mutableStateOf(0.0)
    var durationSeconds: Double by mutableStateOf(0.0)
    var paused: Boolean by mutableStateOf(true)
    var buffering: Boolean by mutableStateOf(false)
    var muted: Boolean by mutableStateOf(false)
    var volume: Double by mutableStateOf(1.0)
    var fullscreen: Boolean by mutableStateOf(false)

    /** 0..1, for the seek bar. Zero rather than NaN before the duration is known. */
    val fraction: Double
        get() = if (durationSeconds > 0) (positionSeconds / durationSeconds).coerceIn(0.0, 1.0) else 0.0
}

/** What the chrome can ask of whatever is decoding the video. */
interface VideoCommands {
    fun play()
    fun pause()
    fun seekTo(seconds: Double)
    fun setMuted(muted: Boolean)
    fun setVolume(value: Double)

    /**
     * Fullscreen belongs to the *page*, not to the video element.
     *
     * Taking the element fullscreen would put the browser's own controls on top and leave the
     * app's chrome behind it — the chrome is drawn on a canvas that would no longer be on screen.
     */
    fun toggleFullscreen()

    fun togglePlay(paused: Boolean) {
        if (paused) play() else pause()
    }

    fun seekBy(delta: Double, from: Double, duration: Double) {
        val target = (from + delta).coerceIn(0.0, if (duration > 0) duration else from + delta)
        seekTo(target)
    }
}

/**
 * Mounts a decoder and reports on it.
 *
 * [startAtSeconds] is the resume point, applied by the surface rather than by the caller: only the
 * surface knows when its decoder will accept a seek, and issuing one too early is silently ignored
 * on some platforms.
 */
@Composable
expect fun VideoSurface(
    url: String,
    autoPlay: Boolean,
    startAtSeconds: Double,
    state: VideoState,
    onCommands: (VideoCommands) -> Unit,
    onTimeUpdate: (positionSeconds: Double, durationSeconds: Double) -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier,
)
