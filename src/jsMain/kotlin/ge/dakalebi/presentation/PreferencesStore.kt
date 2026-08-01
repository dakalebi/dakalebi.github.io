package ge.dakalebi.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.domain.repository.PreferencesRepository

/**
 * Observable view of the per-device preferences.
 *
 * The repository is the storage; this is the Compose state over it. Both exist
 * because a screen needs to recompose when a switch flips, and storage has no
 * opinion about recomposition.
 */
class PreferencesStore(private val prefs: PreferencesRepository) {

    var autoplayNext: Boolean by mutableStateOf(prefs.autoplayNext())
        private set

    var useNativePlayer: Boolean by mutableStateOf(prefs.useNativePlayer())
        private set

    var preferredQuality: String? by mutableStateOf(prefs.preferredQuality())
        private set

    /** Keeps several tabs of the app in agreement. */
    fun start() {
        prefs.onExternalChange {
            autoplayNext = prefs.autoplayNext()
            useNativePlayer = prefs.useNativePlayer()
            preferredQuality = prefs.preferredQuality()
        }
    }

    fun setAutoplayNext(value: Boolean) {
        autoplayNext = value
        prefs.setAutoplayNext(value)
    }

    fun setUseNativePlayer(value: Boolean) {
        useNativePlayer = value
        prefs.setUseNativePlayer(value)
    }

    fun setPreferredQuality(label: String?) {
        preferredQuality = label
        prefs.setPreferredQuality(label)
    }

    fun playIntent(episodeId: String): String? = prefs.playIntent(episodeId)

    fun setPlayIntent(episodeId: String, intent: String) = prefs.setPlayIntent(episodeId, intent)
}
