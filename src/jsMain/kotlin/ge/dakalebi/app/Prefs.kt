package ge.dakalebi.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.localStorage
import kotlinx.browser.sessionStorage
import kotlinx.browser.window

/**
 * Per-device preferences.
 *
 * Quality genuinely belongs here: the right rendition depends on the screen and
 * the connection in front of you, so a phone on mobile data should not inherit
 * what a desktop chose. Autoplay is the opposite — it is a statement about how
 * you like to watch — so it lives on the account instead, in
 * [ge.dakalebi.data.Settings]. What remains here for autoplay is only a cache,
 * so the switch renders correctly on the first frame and still works offline.
 */
object Prefs {
    private const val AUTOPLAY_KEY = "autoplay_next_episode"
    private const val QUALITY_KEY = "preferred_quality"
    private const val INTENT_PREFIX = "watch-player-intent:"

    /** Last known value of the account-level setting. Not the source of truth. */
    val cachedAutoplayNext: Boolean get() = readBool(AUTOPLAY_KEY)

    fun cacheAutoplayNext(value: Boolean) = write(AUTOPLAY_KEY, if (value) "1" else "0")

    var preferredQuality: String? by mutableStateOf(read(QUALITY_KEY))
        private set

    fun start() {
        // Keep multiple tabs in sync, same as the original did.
        window.addEventListener("storage", {
            preferredQuality = read(QUALITY_KEY)
        })
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
    }.orWarn("read session $episodeId")?.takeIf { it == "playing" || it == "paused" }

    fun setPlayIntent(episodeId: String, intent: String) {
        runCatching { sessionStorage.setItem(INTENT_PREFIX + episodeId, intent) }
            .orWarn("write session $episodeId")
    }

    private fun read(key: String): String? =
        runCatching { localStorage.getItem(key) }.orWarn("read $key")

    private fun readBool(key: String): Boolean = read(key) == "1"

    private fun write(key: String, value: String) {
        runCatching { localStorage.setItem(key, value) }.orWarn("write $key")
    }

    private fun remove(key: String) {
        runCatching { localStorage.removeItem(key) }.orWarn("remove $key")
    }

    /**
     * Storage throws outright in Safari private mode and wherever the user has
     * blocked site data. Preferences are not worth failing over, but "my volume
     * never sticks" is otherwise impossible to explain.
     */
    private fun <T> Result<T>.orWarn(what: String): T? =
        onFailure { Log.w("prefs", "$what unavailable", it) }.getOrNull()
}
