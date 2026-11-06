package ge.dakalebi.data.local

import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.InterfaceScale
import ge.dakalebi.domain.repository.PreferencesRepository
import kotlinx.browser.localStorage
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.w3c.dom.events.Event

/**
 * Per-device preferences in `localStorage`, with the play intent in
 * `sessionStorage` because it should not outlive the tab. Account settings are
 * not here: their device copy is [LocalSettingsCache].
 *
 * Storage only — no Compose state. The observable half is
 * [ge.dakalebi.presentation.PreferencesStore], which reads through this.
 * Keeping them apart is what lets a test drive preference-dependent logic with
 * a plain map.
 */
class BrowserPreferencesRepository : PreferencesRepository {

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

    /** Another tab writing to storage. Cancelling the collection removes the listener. */
    override val externalChanges: Flow<Unit> = callbackFlow {
        val listener: (Event) -> Unit = { trySend(Unit) }
        window.addEventListener("storage", listener)
        awaitClose { window.removeEventListener("storage", listener) }
    }

    private fun read(key: String): String? =
        runCatching { localStorage.getItem(key) }.orWarn("read $key")

    private fun write(key: String, value: String) {
        runCatching { localStorage.setItem(key, value) }.orWarn("write $key")
    }

    /**
     * Storage throws outright in Safari private mode and wherever the user has
     * blocked site data. Preferences are not worth failing over, but "my volume
     * never sticks" is otherwise impossible to explain.
     */
    private fun <T> Result<T>.orWarn(what: String): T? =
        onFailure { Log.w("prefs", "$what unavailable", it) }.getOrNull()

    // `language`, `autoplay_next_episode`, `use_native_player` and `preferred_quality`
    // used to live here too. They are account settings now, and `LocalSettingsCache`
    // reads the same keys, so a value stored before that move is still found.
    private companion object {
        const val SCALE_KEY = "tv_interface_scale"
        const val INTENT_PREFIX = "watch-player-intent:"
    }
}
