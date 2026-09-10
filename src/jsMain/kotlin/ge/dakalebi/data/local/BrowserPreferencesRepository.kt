package ge.dakalebi.data.local

import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.InterfaceScale
import ge.dakalebi.domain.repository.PreferencesRepository
import kotlinx.browser.localStorage
import kotlinx.browser.sessionStorage
import kotlinx.browser.window

/**
 * Per-device preferences in `localStorage`, with the play intent in
 * `sessionStorage` because it should not outlive the tab.
 *
 * Storage only — no Compose state. The observable half is
 * [ge.dakalebi.presentation.PreferencesStore], which reads through this.
 * Keeping them apart is what lets a test drive preference-dependent logic with
 * a plain map.
 */
class BrowserPreferencesRepository : PreferencesRepository {

    override fun autoplayNext(): Boolean = readBool(AUTOPLAY_KEY)

    override fun setAutoplayNext(value: Boolean) = writeBool(AUTOPLAY_KEY, value)

    /**
     * Defaults to on, which is what iPhone and iPad did before there was a
     * choice — so the switch changes nothing until someone touches it. Hence
     * "not off" rather than "is on": an absent key has to mean the default.
     */
    override fun useNativePlayer(): Boolean = read(NATIVE_PLAYER_KEY) != "0"

    override fun setUseNativePlayer(value: Boolean) = writeBool(NATIVE_PLAYER_KEY, value)

    override fun preferredQuality(): String? = read(QUALITY_KEY)

    override fun setPreferredQuality(label: String?) {
        if (label == null) remove(QUALITY_KEY) else write(QUALITY_KEY, label)
    }

    override fun language(): String? = read(LANGUAGE_KEY)

    override fun setLanguage(tag: String) = write(LANGUAGE_KEY, tag)

    /**
     * Normalised on the way out rather than trusted, because this is the one preference
     * whose stored value can be wrong in a way that matters: it is multiplied into the
     * root font size, so a stray string would either be ignored silently or, if it
     * parsed to something absurd, draw an interface nobody can use to fix it. See
     * [InterfaceScale.normalise].
     */
    override fun interfaceScale(): Int = InterfaceScale.normalise(read(SCALE_KEY)?.toIntOrNull())

    override fun setInterfaceScale(percent: Int) =
        write(SCALE_KEY, InterfaceScale.normalise(percent).toString())

    /**
     * Whether this episode was left playing or paused, remembered for the
     * session. Without it, navigating next/back always resumes playback even
     * when the viewer had deliberately paused.
     */
    override fun playIntent(episodeId: String): String? = runCatching {
        sessionStorage.getItem(INTENT_PREFIX + episodeId)
    }.orWarn("read session $episodeId")?.takeIf { it == "playing" || it == "paused" }

    override fun setPlayIntent(episodeId: String, intent: String) {
        runCatching { sessionStorage.setItem(INTENT_PREFIX + episodeId, intent) }
            .orWarn("write session $episodeId")
    }

    /** Fires when another tab writes to storage. */
    override fun onExternalChange(listener: () -> Unit) {
        window.addEventListener("storage", { listener() })
    }

    private fun read(key: String): String? =
        runCatching { localStorage.getItem(key) }.orWarn("read $key")

    private fun readBool(key: String): Boolean = read(key) == "1"

    private fun write(key: String, value: String) {
        runCatching { localStorage.setItem(key, value) }.orWarn("write $key")
    }

    private fun writeBool(key: String, value: Boolean) = write(key, if (value) "1" else "0")

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

    private companion object {
        const val AUTOPLAY_KEY = "autoplay_next_episode"
        const val QUALITY_KEY = "preferred_quality"
        const val NATIVE_PLAYER_KEY = "use_native_player"
        const val LANGUAGE_KEY = "language"
        const val SCALE_KEY = "tv_interface_scale"
        const val INTENT_PREFIX = "watch-player-intent:"
    }
}
