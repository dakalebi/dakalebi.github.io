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
    private const val NATIVE_PLAYER_KEY = "use_native_player"
    private const val LANGUAGE_KEY = "language"
    private const val INTENT_PREFIX = "watch-player-intent:"

    var autoplayNext: Boolean by mutableStateOf(readBool(AUTOPLAY_KEY))
        private set

    var preferredQuality: String? by mutableStateOf(read(QUALITY_KEY))
        private set

    /**
     * Whether iPhone and iPad get Apple's own player rather than the web one.
     *
     * Defaults to on, which is what these devices did before there was a
     * choice — so the switch changes nothing until someone touches it. That is
     * also why the stored value is read as "not off" rather than "is on": an
     * absent key has to mean the default, not `false`.
     *
     * Per-device on purpose. Which player suits you is a property of the
     * screen in your hand, not of your account, so it stays out of Firestore.
     */
    var useNativePlayer: Boolean by mutableStateOf(readBoolDefaultOn(NATIVE_PLAYER_KEY))
        private set

    /**
     * Last language this device saw, as a BCP-47 tag.
     *
     * A cache, not the source of truth — that is the account's settings
     * document. It exists so the first frame after a reload is already in the
     * right language instead of flashing Georgian while Firestore answers, and
     * so the login screen, which has no account to read from, is too.
     */
    val cachedLanguage: String? get() = read(LANGUAGE_KEY)

    fun start() {
        // Keep multiple tabs in sync, same as the original did.
        window.addEventListener("storage", {
            autoplayNext = readBool(AUTOPLAY_KEY)
            preferredQuality = read(QUALITY_KEY)
            useNativePlayer = readBoolDefaultOn(NATIVE_PLAYER_KEY)
        })
    }

    fun setAutoplayNext(value: Boolean) {
        autoplayNext = value
        write(AUTOPLAY_KEY, if (value) "1" else "0")
    }

    fun setUseNativePlayer(value: Boolean) {
        useNativePlayer = value
        write(NATIVE_PLAYER_KEY, if (value) "1" else "0")
    }

    fun cacheLanguage(tag: String) {
        write(LANGUAGE_KEY, tag)
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

    /** For settings whose default is on: only an explicit "0" turns them off. */
    private fun readBoolDefaultOn(key: String): Boolean = read(key) != "0"

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
