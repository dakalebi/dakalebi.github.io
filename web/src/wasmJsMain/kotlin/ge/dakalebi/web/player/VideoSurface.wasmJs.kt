package ge.dakalebi.web.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import ge.dakalebi.core.Log
import ge.dakalebi.web.ui.mountOverlay
import ge.dakalebi.web.ui.placeOverlay
import ge.dakalebi.web.ui.removeOverlay

/**
 * The `<video>` element, positioned over the rectangle the player screen reserved for it.
 *
 * A canvas has no way to decode video, and a decoded frame cannot be handed to Skia cheaply enough
 * to draw at 25fps, so the picture is a real DOM element (see `Overlay` for why it sits above the
 * app rather than below it). It covers what it is given, which is why the chrome is a bar beneath
 * the picture rather than an overlay across it.
 *
 * The element is created imperatively and lives outside the composition. It has to: recreating it
 * on recomposition would restart playback, and the browser's own media state — buffered ranges,
 * the decoder, the AirPlay route — belongs to the element instance, not to the tree that mentions
 * it.
 */
@Composable
actual fun VideoSurface(
    url: String,
    autoPlay: Boolean,
    startAtSeconds: Double,
    state: VideoState,
    onCommands: (VideoCommands) -> Unit,
    onTimeUpdate: (positionSeconds: Double, durationSeconds: Double) -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier,
) {
    val element = remember { createVideoElement() }
    val density = LocalDensity.current.density

    // Kept current so the listeners installed once below always call this composition's callbacks.
    val timeUpdate by rememberUpdatedState(onTimeUpdate)
    val ended by rememberUpdatedState(onEnded)
    val failed by rememberUpdatedState(onError)

    DisposableEffect(element) {
        onCommands(HtmlVideoCommands(element))
        attachListeners(
            element = element,
            resumeAt = startAtSeconds,
            onState = { position, duration, paused, buffering, muted, volume ->
                state.positionSeconds = position
                state.durationSeconds = duration
                state.paused = paused
                state.buffering = buffering
                state.muted = muted
                state.volume = volume
            },
            onTick = { position, duration -> timeUpdate(position, duration) },
            onEnded = { ended() },
            onError = { code ->
                Log.e("player", "media error (code=$code)")
                failed()
            },
            onFullscreen = { state.fullscreen = it },
        )
        onDispose { destroyVideoElement(element) }
    }

    // A new URL is a new source on the same element: a quality switch must keep the position, and
    // the resume point is re-applied by the element's own metadata handler.
    LaunchedEffect(url, autoPlay) {
        setSource(element, url, autoPlay)
    }

    Box(
        modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            val visible = coordinates.boundsInWindow()
            placeOverlay(
                element = element,
                left = (position.x / density).toDouble(),
                top = (position.y / density).toDouble(),
                width = (coordinates.size.width / density).toDouble(),
                height = (coordinates.size.height / density).toDouble(),
                visibleTop = (visible.top / density).toDouble(),
                visibleBottom = (visible.bottom / density).toDouble(),
            )
        },
    )
}

private class HtmlVideoCommands(private val element: JsAny) : VideoCommands {
    override fun play() {
        playVideo(element)
    }

    override fun pause() {
        pauseVideo(element)
    }

    override fun seekTo(seconds: Double) {
        seekVideo(element, seconds)
    }

    override fun setMuted(muted: Boolean) {
        muteVideo(element, muted)
    }

    override fun setVolume(value: Double) {
        setVideoVolume(element, value.coerceIn(0.0, 1.0))
    }

    override fun toggleFullscreen() {
        togglePageFullscreen()
    }
}

// --------------------------------------------------------------------------- element interop

/**
 * Creates the element and mounts it over the app.
 *
 * `playsinline` matters on iPhone, where a video without it takes over the screen with the
 * system's own controls and the chrome drawn above it disappears.
 */
private fun createVideoElement(): JsAny {
    val video = newVideoElement()
    mountOverlay(video)
    return video
}

private fun newVideoElement(): JsAny = js(
    """{
    var video = document.createElement('video');
    video.id = 'player-video';
    video.className = 'dk-over';
    video.playsInline = true;
    video.preload = 'auto';
    video.controls = false;
    video.style.display = 'none';
    return video;
}""",
)

private fun destroyVideoElement(element: JsAny) {
    // Emptied before it is removed: taking a still-loading element out of the page leaves the fetch
    // running, which on a slow connection keeps competing for bandwidth with the next episode.
    clearVideoSource(element)
    removeOverlay(element)
}

private fun clearVideoSource(element: JsAny) {
    js(
        """{
        try { element.pause(); } catch (e) {}
        element.removeAttribute('src');
        element.load();
    }""",
    )
}

private fun setSource(element: JsAny, url: String, autoPlay: Boolean) {
    js(
        """{
        if (element.src !== url) {
            element.src = url;
            element.load();
        }
        element.style.display = 'block';
        if (autoPlay) {
            var started = element.play();
            // Autoplay is refused unless the media is muted or the user has interacted with the
            // page. Not an error: the chrome shows a play button and the viewer presses it.
            if (started && started.catch) started.catch(function () {});
        }
    }""",
    )
}

private fun playVideo(element: JsAny) {
    js("{ var p = element.play(); if (p && p.catch) p.catch(function () {}); }")
}

private fun pauseVideo(element: JsAny) {
    js("element.pause()")
}

private fun seekVideo(element: JsAny, seconds: Double) {
    // Assigning currentTime throws while the element is not seekable yet, which is expected during
    // the resume retry and must not take the app down with it.
    js("{ try { element.currentTime = seconds; } catch (e) {} }")
}

private fun muteVideo(element: JsAny, muted: Boolean) {
    js("element.muted = muted")
}

private fun setVideoVolume(element: JsAny, value: Double) {
    js("{ element.volume = value; element.muted = value <= 0; }")
}

private fun togglePageFullscreen() {
    js(
        """{
        if (document.fullscreenElement) {
            document.exitFullscreen();
        } else if (document.documentElement.requestFullscreen) {
            document.documentElement.requestFullscreen();
        }
    }""",
    )
}

/**
 * Wires every media event the chrome and the persistence need, in one place.
 *
 * Written as a single JS block rather than a dozen typed externals because these listeners are one
 * unit: they all read the same element and they must be installed and removed together. The
 * resume, in particular, only works as one piece — the seek is re-issued on each stage the
 * element passes through, since a seek accepted before the media is seekable is silently dropped
 * and one issued after playback starts is visible as a jump.
 */
private fun attachListeners(
    element: JsAny,
    resumeAt: Double,
    onState: (
        position: Double,
        duration: Double,
        paused: Boolean,
        buffering: Boolean,
        muted: Boolean,
        volume: Double,
    ) -> Unit,
    onTick: (position: Double, duration: Double) -> Unit,
    onEnded: () -> Unit,
    onError: (code: Int) -> Unit,
    onFullscreen: (Boolean) -> Unit,
) {
    js(
        """{
        var attempts = 0;
        var applied = resumeAt <= 1;

        var report = function (buffering) {
            var duration = isFinite(element.duration) ? element.duration : 0;
            onState(element.currentTime || 0, duration, element.paused, !!buffering,
                element.muted, element.volume);
        };

        var resume = function () {
            if (applied || attempts >= 8) return;
            var duration = isFinite(element.duration) ? element.duration : 0;
            if (duration <= 0) return;
            // Never the very end: landing there means an episode reopens as already finished.
            var target = Math.min(resumeAt, Math.max(0, duration - 5));
            if (target <= 1) { applied = true; return; }
            attempts += 1;
            if (Math.abs(element.currentTime - target) < 0.75) { applied = true; return; }
            try { element.currentTime = target; } catch (e) {}
        };

        element.addEventListener('loadedmetadata', function () { resume(); report(false); });
        element.addEventListener('loadeddata', function () { resume(); report(false); });
        element.addEventListener('canplay', function () { resume(); report(false); });
        element.addEventListener('durationchange', function () { report(false); });
        element.addEventListener('seeked', function () { resume(); report(false); });
        element.addEventListener('play', function () { report(false); });
        element.addEventListener('playing', function () { report(false); });
        element.addEventListener('pause', function () { report(false); });
        element.addEventListener('waiting', function () { report(true); });
        element.addEventListener('stalled', function () { report(true); });
        element.addEventListener('volumechange', function () { report(false); });
        element.addEventListener('timeupdate', function () {
            report(false);
            var duration = isFinite(element.duration) ? element.duration : 0;
            onTick(element.currentTime || 0, duration);
        });
        element.addEventListener('ended', function () { report(false); onEnded(); });
        element.addEventListener('error', function () {
            onError(element.error ? element.error.code : 0);
        });
        document.addEventListener('fullscreenchange', function () {
            onFullscreen(!!document.fullscreenElement);
        });
    }""",
    )
}
