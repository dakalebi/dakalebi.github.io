package ge.dakalebi.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.js.json

/**
 * No pinch zoom, on every page.
 *
 * The viewport tag's `user-scalable=no` already stops it on Android and in desktop
 * device emulation, and `touch-action: manipulation` stops double-tap zoom everywhere.
 * iOS Safari ignores the viewport tag, but it sends a pinch to the page first as
 * WebKit's own `gesturestart` and `gesturechange`, and cancelling those keeps the page
 * at its size.
 *
 * Not on a `<video>`: a pinch out on one is how iOS opens its fullscreen player, and
 * that has to keep working.
 */
internal fun blockPinchZoom() {
    val cancel: (Event) -> Unit = { event ->
        if (event.target !is HTMLVideoElement) event.preventDefault()
    }
    // Not passive, or the cancel would be ignored.
    val options = json("passive" to false)
    document.addEventListener("gesturestart", cancel, options)
    document.addEventListener("gesturechange", cancel, options)
}
