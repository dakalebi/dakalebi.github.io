package ge.dakalebi.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.app.Log
import ge.dakalebi.app.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Account settings, live across devices.
 *
 * `localStorage` is kept as a cache rather than dropped. It is what makes the
 * toggle render in its real state on the very first frame instead of flashing
 * the default while Firestore answers, and it is the only copy that works
 * offline. Firestore is the source of truth whenever it has an opinion.
 */
object Settings {
    var autoplayNext: Boolean by mutableStateOf(Prefs.cachedAutoplayNext)
        private set

    private var unsubscribe: (() -> Unit)? = null
    private var uid: String? = null

    /**
     * Attaches to the signed-in account.
     *
     * If the account has no settings document yet, the device's cached value is
     * pushed up rather than discarded — otherwise the first sign-in on a second
     * device would silently reset a preference the user had already chosen.
     */
    fun start(scope: CoroutineScope, uid: String) {
        if (this.uid == uid) return
        stop()
        this.uid = uid

        scope.launch {
            when (val remote = SettingsRepository.load(uid)) {
                is SettingsRepository.Load.Found -> apply(remote.settings.autoplayNext)
                SettingsRepository.Load.Missing -> {
                    Log.d("settings", "no settings document yet, seeding from this device")
                    SettingsRepository.save(uid, UserSettings(autoplayNext = Prefs.cachedAutoplayNext))
                }
                // Keep whatever the cache had and change nothing server-side.
                // Seeding here would push a possibly-stale local value over the
                // account's real setting just because one read failed.
                SettingsRepository.Load.Failed ->
                    Log.w("settings", "settings unavailable; staying on the cached value")
            }
        }

        unsubscribe = SettingsRepository.listen(uid) { apply(it.autoplayNext) }
    }

    fun stop() {
        unsubscribe?.invoke()
        unsubscribe = null
        uid = null
    }

    fun setAutoplayNext(scope: CoroutineScope, value: Boolean) {
        apply(value)
        val id = uid ?: return
        scope.launch { SettingsRepository.save(id, UserSettings(autoplayNext = value)) }
    }

    /** Updates the state and mirrors it into the local cache. */
    private fun apply(value: Boolean) {
        autoplayNext = value
        Prefs.cacheAutoplayNext(value)
    }
}
