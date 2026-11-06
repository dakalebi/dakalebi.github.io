package ge.dakalebi.ui.drive

import ge.dakalebi.core.Log
import kotlinx.browser.document
import kotlinx.browser.window

/**
 * Keeps the screen on while the episode plays, so the phone does not lock in its
 * mount mid-episode. Released on pause. The browser releases it by itself whenever
 * the page is hidden, and it can only be asked for while the page is visible.
 *
 * [stillWanted] is asked when a request comes back: a lock granted after the episode
 * was paused is let go at once.
 */
internal class ScreenWakeLock(private val stillWanted: () -> Boolean) {
    private var held: dynamic = null
    private var pending = false

    fun acquire() {
        if (held != null || pending) return
        if (document.asDynamic().visibilityState != "visible") return
        val api = window.navigator.asDynamic().wakeLock ?: return
        pending = true
        runCatching {
            api.request("screen").then(
                { lock: dynamic ->
                    pending = false
                    if (stillWanted()) {
                        held = lock
                        lock.addEventListener("release", { _: dynamic -> if (held === lock) held = null })
                    } else {
                        // Paused while the request was in flight.
                        lock.release()
                    }
                },
                { error: dynamic ->
                    pending = false
                    Log.w("drive", "screen wake lock refused: ${error?.name}")
                },
            )
        }.onFailure {
            pending = false
            Log.w("drive", "screen wake lock unavailable", it)
        }
    }

    fun release() {
        val lock = held ?: return
        held = null
        runCatching { lock.release() }
    }
}
