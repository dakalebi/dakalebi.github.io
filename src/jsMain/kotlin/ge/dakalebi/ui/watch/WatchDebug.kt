package ge.dakalebi.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLVideoElement
import kotlin.js.Date

// TEMPORARY. An on-screen log of the watch path, for playback that fails on a phone
// with no console attached. Remove this file and its call sites in WatchScreen
// before the branch is merged.

/** On with `?debug=1` before the `#`, e.g. `/preview/?debug=1#/watch/531`. */
internal val watchDebugEnabled: Boolean by lazy {
    window.location.search.trimStart('?').split('&').contains("debug=1")
}

internal class WatchDebug {
    private val startedAt = Date.now()
    private var video: HTMLVideoElement? = null

    val lines = mutableStateListOf<String>()
    var snapshot by mutableStateOf("no video element yet")

    fun log(message: String) {
        val seconds = ((Date.now() - startedAt) / 100).toInt() / 10.0
        lines.add("$seconds $message")
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    fun attach(element: HTMLVideoElement?) {
        video = element
        if (element == null) {
            log("video element removed")
            return
        }
        log("video element added")
        for (name in MEDIA_EVENTS) {
            element.addEventListener(name, {
                val code = if (name == "error") " code=${element.error?.code}" else ""
                log("$name rs=${element.readyState} ns=${element.networkState} t=${element.currentTime.short()}$code")
            })
        }
    }

    fun tick() {
        val v = video ?: return
        val ranges = v.buffered
        val bufferedTo = if (ranges.length > 0) ranges.end(ranges.length - 1) else 0.0
        snapshot = "rs=${v.readyState} ns=${v.networkState} paused=${v.paused} " +
            "t=${v.currentTime.short()} buf=${bufferedTo.short()} dur=${v.duration.short()} " +
            "err=${v.error?.code}\nplaying ${describeUrl(v.currentSrc.ifEmpty { v.src })}"
    }

    private companion object {
        const val MAX_LINES = 16

        /** Every event that says where loading is; `timeupdate` and `progress` would drown them. */
        val MEDIA_EVENTS = listOf(
            "loadstart", "loadedmetadata", "loadeddata", "canplay", "canplaythrough",
            "play", "playing", "pause", "waiting", "stalled", "suspend",
            "seeking", "seeked", "error", "abort", "emptied",
        )
    }
}

/**
 * Host, the end of the path, and the `#t=` fragment. The query is left out because a
 * CDN can sign its URLs there, and this ends up in screenshots.
 */
internal fun describeUrl(url: String?): String {
    if (url.isNullOrEmpty()) return "none"
    val withoutScheme = url.substringAfter("://")
    val host = withoutScheme.substringBefore('/')
    val path = withoutScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
    val fragment = url.substringAfter('#', "")
    val query = if ('?' in url) " +query" else ""
    return "$host/…${path.takeLast(36)}$query" + if (fragment.isNotEmpty()) " #$fragment" else ""
}

private fun Double.short(): String = if (isNaN() || isInfinite()) toString() else (kotlin.math.round(this * 10) / 10).toString()

@Composable
internal fun WatchDebugPanel(debug: WatchDebug, state: String) {
    DisposableEffect(debug) {
        val timer = window.setInterval({ debug.tick() }, 1_000)
        onDispose { window.clearInterval(timer) }
    }
    Pre({
        style {
            property("position", "fixed")
            property("left", "0")
            property("right", "0")
            property("bottom", "0")
            property("z-index", "2147483647")
            property("pointer-events", "none")
            property("margin", "0")
            property("padding", "6px 8px")
            property("max-height", "45vh")
            property("overflow", "hidden")
            property("white-space", "pre-wrap")
            property("word-break", "break-all")
            property("font", "10px/1.35 ui-monospace, Menlo, monospace")
            property("color", "#fff")
            property("background", "rgba(0, 0, 0, 0.78)")
        }
    }) {
        Text(state + "\n" + debug.snapshot + "\n" + debug.lines.joinToString("\n"))
    }
}
