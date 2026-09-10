package ge.dakalebi.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.domain.model.InterfaceScale
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

    /**
     * How large the television interface is drawn, as a percentage.
     *
     * Compose state so the chosen chip renders as chosen. The size itself is not applied
     * from here — it is one CSS custom property on the document element, written by the
     * shell, because a `rem` cannot be changed from inside a composition.
     */
    var interfaceScale: Int by mutableStateOf(prefs.interfaceScale())
        private set

    /** Keeps several tabs of the app in agreement. */
    fun start() {
        prefs.onExternalChange {
            autoplayNext = prefs.autoplayNext()
            useNativePlayer = prefs.useNativePlayer()
            preferredQuality = prefs.preferredQuality()
            interfaceScale = prefs.interfaceScale()
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

    fun setInterfaceScale(percent: Int) {
        val value = InterfaceScale.normalise(percent)
        interfaceScale = value
        prefs.setInterfaceScale(value)
    }

    fun playIntent(episodeId: String): String? = prefs.playIntent(episodeId)

    fun setPlayIntent(episodeId: String, intent: String) = prefs.setPlayIntent(episodeId, intent)
}
