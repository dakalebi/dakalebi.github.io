package ge.dakalebi.next.drive

import ge.dakalebi.core.Log
import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement

/**
 * Puts [video] full screen. Call from a tap: every browser refuses otherwise.
 *
 * The standard Fullscreen API where the page may use it (desktop, Android, iPad).
 * An iPhone has none for a page, only its own video player, which
 * `webkitEnterFullscreen` opens on this element. Either way the picture is the
 * element with its native controls, so leaving is the browser's own button, swipe
 * or Escape.
 */
internal fun enterVideoFullscreen(video: HTMLVideoElement) {
    val doc = document.asDynamic()
    val element = video.asDynamic()
    when {
        doc.fullscreenEnabled == true && jsTypeOf(element.requestFullscreen) == "function" -> {
            val request = runCatching { element.requestFullscreen() }
                .onFailure { Log.w("drive", "requestFullscreen threw", it) }
            if (request.isFailure) {
                enterNativeFullscreen(video)
            } else {
                // A refusal arrives later, as a rejected promise. Older engines return nothing.
                val promise = request.getOrNull()
                if (promise != null && jsTypeOf(promise.then) == "function") {
                    promise.catch { error: dynamic ->
                        Log.w("drive", "requestFullscreen refused: ${error?.name}")
                        enterNativeFullscreen(video)
                    }
                }
            }
        }
        doc.webkitFullscreenEnabled == true && jsTypeOf(element.webkitRequestFullscreen) == "function" ->
            runCatching { element.webkitRequestFullscreen() }.onFailure {
                Log.w("drive", "webkitRequestFullscreen threw", it)
                enterNativeFullscreen(video)
            }
        else -> enterNativeFullscreen(video)
    }
}

/** iPhone: Safari's own fullscreen video player. */
private fun enterNativeFullscreen(video: HTMLVideoElement) {
    val element = video.asDynamic()
    if (jsTypeOf(element.webkitEnterFullscreen) != "function") {
        Log.w("drive", "no way to go full screen in this browser")
        return
    }
    runCatching { element.webkitEnterFullscreen() }
        .onFailure { Log.w("drive", "webkitEnterFullscreen refused", it) }
}
