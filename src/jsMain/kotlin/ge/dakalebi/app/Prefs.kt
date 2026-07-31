package ge.dakalebi.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.localStorage
import kotlinx.browser.sessionStorage
import kotlinx.browser.window

/** Local, per-device preferences. Nothing here is worth a Firestore round-trip. */
object Prefs {
    private const val AUTOPLAY_KEY = "autoplay_next_episode"
    private const val QUALITY_KEY = "preferred_quality"
    private const val INTENT_PREFIX = "watch-player-intent:"

    var autoplayNext: Boolean by mutableStateOf(readBool(AUTOPLAY_KEY))
        private set

    var preferredQuality: String? by mutableStateOf(read(QUALITY_KEY))
        private set

    fun start() {
        // Keep multiple tabs in sync, same as the original did.
        window.addEventListener("storage", {
            autoplayNext = readBool(AUTOPLAY_KEY)
            preferredQuality = read(QUALITY_KEY)
        })
    }

    fun setAutoplayNext(value: Boolean) {
        autoplayNext = value
        write(AUTOPLAY_KEY, if (value) "1" else "0")
    }

    fun setPreferredQuality(label: String?) {
        preferredQuality = label
        if (label == null) remove(QUALITY_KEY) else write(QUALITY_KEY, label)
    }

    /**
     * Whether the user had this episode playing or paused, remembered for the
     * session. Without it, navigating next/back always resumes playback even
     * when the user had deliberately paused.
     */
    fun playIntent(episodeId: String): String? = runCatching {
        sessionStorage.getItem(INTENT_PREFIX + episodeId)
    }.getOrNull()?.takeIf { it == "playing" || it == "paused" }

    fun setPlayIntent(episodeId: String, intent: String) {
        runCatching { sessionStorage.setItem(INTENT_PREFIX + episodeId, intent) }
    }

    private fun read(key: String): String? = runCatching { localStorage.getItem(key) }.getOrNull()
    private fun readBool(key: String): Boolean = read(key) == "1"
    private fun write(key: String, value: String) { runCatching { localStorage.setItem(key, value) } }
    private fun remove(key: String) { runCatching { localStorage.removeItem(key) } }
}
