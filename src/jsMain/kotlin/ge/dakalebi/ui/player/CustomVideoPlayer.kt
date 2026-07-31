package ge.dakalebi.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.app.Log
import ge.dakalebi.app.formatTime
import ge.dakalebi.ui.Icon
import ge.dakalebi.ui.Icons
import ge.dakalebi.ui.classNames
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.max
import org.jetbrains.compose.web.attributes.min
import org.jetbrains.compose.web.attributes.step
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Video
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Mutable holders that must not trigger recomposition when they change. */
private class PlayerRefs {
    var video: HTMLVideoElement? = null
    var container: HTMLElement? = null
    var fillCur: HTMLElement? = null
    var fillBuf: HTMLElement? = null
    var knob: HTMLElement? = null
    var thinCur: HTMLElement? = null
    var scrubInput: HTMLInputElement? = null
    var raf: Int? = null
    var hideTimer: Int? = null
    var feedbackTimer: Int? = null
    var clickTimer: Int? = null
    /** Position to restore after a quality swap. */
    var pendingSeek: Double? = null
    var pendingPlay: Boolean = false
    var scrubbing: Boolean = false
}

@Composable
fun CustomVideoPlayer(
    src: String,
    autoPlay: Boolean,
    sources: Map<String, String>,
    quality: String?,
    onQualitySelected: (String) -> Unit,
    events: PlayerEvents,
    overlay: @Composable () -> Unit = {},
) {
    val refs = remember { PlayerRefs() }

    var playing by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1.0) }
    var currentSec by remember { mutableStateOf(0) }
    var durationSec by remember { mutableStateOf(0) }
    var showControls by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var qualityOpen by remember { mutableStateOf(false) }
    var castSupported by remember { mutableStateOf(false) }
    var castAvailable by remember { mutableStateOf(false) }
    var casting by remember { mutableStateOf(false) }

    // ------------------------------------------------------------- helpers

    fun paintBars() {
        val v = refs.video ?: return
        val duration = if (v.duration.isFinite() && v.duration > 0) v.duration else 0.0
        val pct = if (duration > 0) (v.currentTime / duration * 100).coerceIn(0.0, 100.0) else 0.0

        if (!refs.scrubbing) {
            refs.fillCur?.style?.width = "$pct%"
            refs.knob?.style?.left = "$pct%"
            // The bar people see is painted here, but the range input layered
            // over it for interaction is what the keyboard and screen readers
            // actually address. Leaving its value behind meant arrow-key
            // seeking jumped from a stale position and assistive tech
            // announced the wrong one. Skipped mid-drag so it never fights
            // the pointer.
            refs.scrubInput?.value = (pct * 10).roundToInt().toString()
        }
        refs.thinCur?.style?.width = "$pct%"

        var bufEnd = 0.0
        val buffered = v.buffered
        for (i in 0 until buffered.length) {
            val start = buffered.start(i)
            val end = buffered.end(i)
            if (v.currentTime in start..end) {
                bufEnd = end
                break
            }
            if (start >= v.currentTime && end > bufEnd) bufEnd = end
        }
        val bufPct = if (duration > 0) (bufEnd / duration * 100).coerceIn(0.0, 100.0) else 0.0
        refs.fillBuf?.style?.width = "$bufPct%"
    }

    fun syncClock() {
        val v = refs.video ?: return
        val cur = if (v.currentTime.isFinite()) floor(v.currentTime).toInt() else 0
        val dur = if (v.duration.isFinite() && v.duration > 0) floor(v.duration).toInt() else 0
        if (cur != currentSec) currentSec = cur
        if (dur != durationSec) durationSec = dur
    }

    fun stopLoop() {
        refs.raf?.let { window.cancelAnimationFrame(it) }
        refs.raf = null
    }

    fun startLoop() {
        stopLoop()
        fun tick(@Suppress("UNUSED_PARAMETER") ts: Double) {
            val v = refs.video
            if (v == null) {
                refs.raf = null
                return
            }
            paintBars()
            syncClock()
            refs.raf = if (!v.paused && !v.ended) window.requestAnimationFrame(::tick) else null
        }
        refs.raf = window.requestAnimationFrame(::tick)
    }

    fun flashFeedback(message: String) {
        feedback = message
        refs.feedbackTimer?.let { window.clearTimeout(it) }
        refs.feedbackTimer = window.setTimeout({ feedback = null }, 900)
    }

    fun revealControls() {
        showControls = true
        refs.hideTimer?.let { window.clearTimeout(it) }
        refs.hideTimer = window.setTimeout({
            val v = refs.video
            if (v != null && !v.paused && !qualityOpen) showControls = false
        }, 2500)
    }

    fun togglePlay() {
        val v = refs.video ?: return
        if (v.paused) v.play() else v.pause()
        revealControls()
    }

    fun seekBy(delta: Double) {
        val v = refs.video ?: return
        if (!v.duration.isFinite()) return
        v.currentTime = max(0.0, min(v.duration, v.currentTime + delta))
        paintBars()
        revealControls()
    }

    fun applyVolume(next: Double) {
        val v = refs.video ?: return
        v.volume = next.coerceIn(0.0, 1.0)
        v.muted = next <= 0.0
        volume = v.volume
        muted = v.muted
    }

    fun toggleFullscreen() {
        val el = refs.container ?: return
        val doc = document.asDynamic()
        if (doc.fullscreenElement != null || doc.webkitFullscreenElement != null) {
            runCatching { doc.exitFullscreen() }
                .recoverCatching { doc.webkitExitFullscreen() }
                .onFailure { Log.w("player", "could not leave fullscreen", it) }
        } else {
            val dyn = el.asDynamic()
            runCatching { dyn.requestFullscreen() }
                .recoverCatching { dyn.webkitRequestFullscreen() }
                .recoverCatching {
                    // iOS Safari only ever fullscreens the video element itself.
                    refs.video?.asDynamic()?.webkitEnterFullscreen()
                }
                .onFailure { Log.w("player", "could not enter fullscreen", it) }
        }
    }

    fun startCast() {
        val v = refs.video?.asDynamic() ?: return
        runCatching {
            if (jsTypeOf(v.webkitShowPlaybackTargetPicker) == "function") {
                v.webkitShowPlaybackTargetPicker()
                flashFeedback("AirPlay")
            } else if (v.remote != null && jsTypeOf(v.remote.prompt) == "function") {
                v.remote.prompt()
                flashFeedback("Cast")
            } else {
                Log.w("cast", "no AirPlay or Remote Playback API on this element")
            }
        }.onFailure { Log.w("cast", "picker refused to open", it) }
    }

    // ------------------------------------------------------------- effects

    // Quality swap: remember where we were, then restore once the new file loads.
    DisposableEffect(src) {
        started = false
        buffering = false
        showControls = true
        qualityOpen = false
        onDispose { stopLoop() }
    }

    DisposableEffect(Unit) {
        val onFs: (Event) -> Unit = {
            val doc = document.asDynamic()
            fullscreen = doc.fullscreenElement != null || doc.webkitFullscreenElement != null
        }
        document.addEventListener("fullscreenchange", onFs)
        document.addEventListener("webkitfullscreenchange", onFs)
        onDispose {
            document.removeEventListener("fullscreenchange", onFs)
            document.removeEventListener("webkitfullscreenchange", onFs)
        }
    }

    DisposableEffect(Unit) {
        val onKey: (Event) -> Unit = handler@{ raw ->
            val event = raw as? KeyboardEvent ?: return@handler
            val target = event.target.asDynamic()
            val tag = (target?.tagName as? String)?.uppercase()
            if (tag == "INPUT" || tag == "TEXTAREA" || tag == "SELECT" ||
                target?.isContentEditable == true
            ) return@handler

            // Space and Enter are how a keyboard user presses whatever is
            // focused. Claiming them unconditionally - which this did - also
            // calls preventDefault, so the button never fires and the video
            // toggles instead: every control on the page becomes unreachable
            // without a mouse. Only take them when focus is somewhere inert.
            val role = runCatching { target?.getAttribute("role") as? String }.getOrNull()
            val focusIsActivatable = tag == "BUTTON" || tag == "A" ||
                tag == "SUMMARY" || !role.isNullOrBlank()

            when {
                event.code == "Space" || event.key == "Enter" -> {
                    if (focusIsActivatable) return@handler
                    event.preventDefault(); togglePlay()
                }
                event.code == "ArrowRight" -> { event.preventDefault(); seekBy(10.0) }
                event.code == "ArrowLeft" -> { event.preventDefault(); seekBy(-10.0) }
                event.code == "ArrowUp" -> {
                    event.preventDefault()
                    applyVolume((refs.video?.volume ?: 1.0) + 0.05)
                    flashFeedback("ხმა: ${((refs.video?.volume ?: 0.0) * 100).roundToInt()}%")
                }
                event.code == "ArrowDown" -> {
                    event.preventDefault()
                    applyVolume((refs.video?.volume ?: 1.0) - 0.05)
                    flashFeedback("ხმა: ${((refs.video?.volume ?: 0.0) * 100).roundToInt()}%")
                }
                event.key == "f" || event.key == "F" -> toggleFullscreen()
                event.key == "m" || event.key == "M" -> {
                    val v = refs.video ?: return@handler
                    v.muted = !v.muted
                    muted = v.muted
                }
            }
        }
        window.addEventListener("keydown", onKey)
        onDispose { window.removeEventListener("keydown", onKey) }
    }

    DisposableEffect(Unit) {
        onDispose {
            refs.hideTimer?.let { window.clearTimeout(it) }
            refs.feedbackTimer?.let { window.clearTimeout(it) }
            refs.clickTimer?.let { window.clearTimeout(it) }
            stopLoop()
        }
    }

    // ----------------------------------------------------------------- ui

    val pctText = "${formatTime(currentSec.toDouble())} / ${formatTime(durationSec.toDouble())}"
    val controlsHidden = !showControls && playing

    Div({
        classes("player")
        ref { element ->
            refs.container = element
            onDispose { refs.container = null }
        }
        onMouseMove { revealControls() }
        onMouseLeave {
            val v = refs.video
            if (v != null && !v.paused && !qualityOpen) showControls = false
        }
    }) {
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

            onClick {
                // Delay so a double-click can cancel the play/pause toggle.
                refs.clickTimer?.let { window.clearTimeout(it) }
                refs.clickTimer = window.setTimeout({
                    togglePlay()
                    refs.clickTimer = null
                }, 220)
            }
            onDoubleClick {
                refs.clickTimer?.let { window.clearTimeout(it) }
                refs.clickTimer = null
                toggleFullscreen()
            }

            addEventListener("loadedmetadata") {
                val v = refs.video ?: return@addEventListener
                syncClock()
                paintBars()
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
                refs.video?.let { buffering = false; events.onLoadedData(it) }
            }
            addEventListener("canplay") {
                refs.video?.let { buffering = false; events.onCanPlay(it) }
            }
            addEventListener("durationchange") {
                syncClock()
                refs.video?.let { events.onDurationChange(it) }
            }
            addEventListener("timeupdate") {
                syncClock()
                refs.video?.let { events.onTimeUpdate(it) }
            }
            addEventListener("progress") { paintBars() }
            addEventListener("play") {
                playing = true
                started = true
                buffering = false
                startLoop()
                revealControls()
                events.onPlay()
            }
            addEventListener("playing") {
                playing = true
                buffering = false
                startLoop()
            }
            addEventListener("pause") {
                playing = false
                buffering = false
                stopLoop()
                showControls = true
                events.onPause()
            }
            addEventListener("waiting") { buffering = refs.video?.paused == false }
            addEventListener("stalled") { buffering = refs.video?.paused == false }
            addEventListener("seeking") { buffering = true }
            addEventListener("seeked") {
                buffering = false
                paintBars()
                refs.video?.let { events.onSeeked(it) }
            }
            addEventListener("volumechange") {
                val v = refs.video ?: return@addEventListener
                volume = v.volume
                muted = v.muted
            }
            addEventListener("ended") {
                playing = false
                stopLoop()
                showControls = true
                events.onEnded()
            }
            addEventListener("error") { events.onError() }
        })

        // Remote playback / AirPlay availability.
        Div({
            ref { _ ->
                val v = refs.video?.asDynamic()
                if (v != null) {
                    if (jsTypeOf(v.webkitShowPlaybackTargetPicker) == "function") {
                        castSupported = true
                    }
                    val remote = v.remote
                    if (remote != null && jsTypeOf(remote.watchAvailability) == "function") {
                        castSupported = true
                        runCatching {
                            remote.watchAvailability { available: Boolean ->
                                castAvailable = available
                            }
                        }.onFailure { Log.w("cast", "watchAvailability rejected", it) }
                        remote.onconnect = { casting = true }
                        remote.ondisconnect = { casting = false }
                    }
                    v.addEventListener("webkitplaybacktargetavailabilitychanged", { e: dynamic ->
                        castAvailable = e.availability == "available"
                    })
                    v.addEventListener("webkitcurrentplaybacktargetiswirelesschanged", { _: dynamic ->
                        val wireless = v.webkitCurrentPlaybackTargetIsWireless == true
                        casting = wireless
                        refs.video?.let { events.onAirPlayChange(wireless, it) }
                    })
                }
                onDispose { }
            }
            style { property("display", "none") }
        })

        if (!started || (!playing && !buffering)) {
            Div({ classes("player-center"); onClick { togglePlay() } }) {
                Div({ classes("big") }) { Icon(Icons.play) }
            }
        }

        if (buffering) {
            Div({ classes("spinner") }) { Div() }
        }

        overlay()

        feedback?.let {
            Div({ classes("feedback") }) { Text(it) }
        }

        if (qualityOpen && sources.size > 1) {
            Div({ classes("q-menu") }) {
                sources.keys.forEach { label ->
                    Button({
                        classNames("q-item", if (label == quality) "sel" else null)
                        onClick {
                            qualityOpen = false
                            val v = refs.video
                            refs.pendingSeek = v?.currentTime
                            refs.pendingPlay = v?.paused == false
                            onQualitySelected(label)
                        }
                    }) {
                        Text(label)
                        if (label == quality) Span({ classes("grow") }) { Text(" ✓") }
                    }
                }
            }
        }

        Div({ classNames("ctl", if (controlsHidden) "hide" else null) }) {
            Div({ classes("scrub") }) {
                Div({ classes("scrub-track") }) {
                    Div({
                        classes("scrub-buf")
                        ref { el -> refs.fillBuf = el; onDispose { refs.fillBuf = null } }
                    })
                    Div({
                        classes("scrub-cur")
                        ref { el -> refs.fillCur = el; onDispose { refs.fillCur = null } }
                    })
                }
                Div({
                    classes("scrub-knob")
                    ref { el -> refs.knob = el; onDispose { refs.knob = null } }
                })
                Input(InputType.Range) {
                    min("0"); max("1000"); step(1.0)
                    attr("aria-label", "დროის ხაზი")
                    ref { el -> refs.scrubInput = el; onDispose { refs.scrubInput = null } }
                    onInput { event ->
                        val v = refs.video ?: return@onInput
                        if (!v.duration.isFinite() || v.duration <= 0) return@onInput
                        val fraction = (event.value.toString().toDoubleOrNull() ?: 0.0) / 1000.0
                        v.currentTime = fraction * v.duration
                        refs.fillCur?.style?.width = "${fraction * 100}%"
                        refs.knob?.style?.left = "${fraction * 100}%"
                    }
                    addEventListener("pointerdown") { refs.scrubbing = true }
                    addEventListener("pointerup") { refs.scrubbing = false }
                    addEventListener("pointercancel") { refs.scrubbing = false }
                }
            }

            Div({ classes("ctl-row") }) {
                Button({
                    classes("icon-btn")
                    attr("aria-label", if (playing) "პაუზა" else "დაკვრა")
                    onClick { togglePlay() }
                }) { Icon(if (playing) Icons.pause else Icons.play) }

                Button({
                    classes("icon-btn")
                    attr("aria-label", "10 წამით უკან")
                    onClick { seekBy(-10.0) }
                }) { Icon(Icons.back10) }

                Div({ classes("vol-wrap") }) {
                    Button({
                        classes("icon-btn")
                        attr("aria-label", if (muted) "ხმის ჩართვა" else "ხმის გათიშვა")
                        onClick {
                            val v = refs.video ?: return@onClick
                            v.muted = !v.muted
                            muted = v.muted
                        }
                    }) { Icon(if (muted || volume == 0.0) Icons.volumeOff else Icons.volumeOn) }

                    Div({ classes("vol") }) {
                        Div({ style { property("width", "${if (muted) 0.0 else volume * 100}%") } })
                        Input(InputType.Range) {
                            min("0"); max("1"); step(0.01)
                            attr("aria-label", "ხმა")
                            value(if (muted) "0" else volume.toString())
                            onInput { event ->
                                applyVolume(event.value.toString().toDoubleOrNull() ?: 0.0)
                            }
                        }
                    }
                }

                Span({ classes("time", "mono") }) { Text(pctText) }

                Div({ classes("grow") })

                if (sources.size > 1) {
                    Button({
                        classes("q-btn", "mono")
                        attr("aria-label", "ხარისხი")
                        onClick { qualityOpen = !qualityOpen }
                    }) { Text(quality ?: sources.keys.first()) }
                }

                if (castSupported || castAvailable) {
                    Button({
                        classes("icon-btn")
                        attr("aria-label", "Cast")
                        if (casting) style { property("color", "var(--red)") }
                        onClick { startCast() }
                    }) { Icon(Icons.cast) }
                }

                Button({
                    classes("icon-btn")
                    attr("aria-label", "სრულ ეკრანზე")
                    onClick { toggleFullscreen() }
                }) { Icon(if (fullscreen) Icons.exitFullscreen else Icons.fullscreen) }
            }
        }

        // Always-visible position line: shown precisely when the control bar
        // is not, so there is never a moment without a progress indicator.
        Div({ classNames("thinbar", if (!controlsHidden) "hide" else null) }) {
            Div({ ref { el -> refs.thinCur = el; onDispose { refs.thinCur = null } } })
        }
    }
}
