package ge.dakalebi.presentation

import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.SettingsLoad
import ge.dakalebi.domain.repository.PreferencesRepository
import ge.dakalebi.domain.usecase.ChangeLanguage
import ge.dakalebi.domain.usecase.LoadUserSettings
import ge.dakalebi.domain.usecase.ObserveUserSettings
import ge.dakalebi.domain.usecase.SeedLanguage
import ge.dakalebi.i18n.I18n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Account settings, live across devices.
 *
 * There is no state of its own here: the language *is* [I18n.current], and
 * `localStorage` holds the copy that renders the first frame. This object owns
 * only the plumbing between the two and the account's document.
 *
 * The local cache is kept rather than dropped. It shows the right language
 * immediately after a reload instead of flashing Georgian while the network
 * answers, and it is the only copy that works offline or signed out. The
 * server wins whenever it has an opinion.
 */
class SettingsStore(
    private val loadUserSettings: LoadUserSettings,
    private val observeUserSettings: ObserveUserSettings,
    private val changeLanguage: ChangeLanguage,
    private val seedLanguage: SeedLanguage,
    private val prefs: PreferencesRepository,
) {
    private var unsubscribe: (() -> Unit)? = null
    private var uid: String? = null

    /** Applies the language this device last saw. Safe before sign-in. */
    fun applyCachedLanguage() {
        prefs.language()?.let { I18n.use(it) }
    }

    /**
     * Attaches to the signed-in account.
     *
     * An account with no language on record is seeded from this device rather
     * than left empty — otherwise the first device to sign in would keep being
     * asked and nothing would ever reach the second one.
     */
    fun start(scope: CoroutineScope, uid: String) {
        if (this.uid == uid) return
        stop()
        this.uid = uid

        scope.launch {
            when (val remote = loadUserSettings(uid)) {
                is SettingsLoad.Found -> {
                    val tag = remote.settings.language
                    if (tag == null) seed(uid) else apply(tag)
                }

                SettingsLoad.Missing -> {
                    Log.d("settings", "no settings document yet, seeding from this device")
                    seed(uid)
                }

                // Keep whatever the cache had and change nothing server-side.
                // Seeding here would push a possibly-stale local value over the
                // account's real setting just because one read failed.
                SettingsLoad.Failed ->
                    Log.w("settings", "settings unavailable; staying on the cached language")
            }
        }

        unsubscribe = observeUserSettings(uid) { settings ->
            settings.language?.let { apply(it) }
        }
    }

    fun stop() {
        unsubscribe?.invoke()
        unsubscribe = null
        uid = null
    }

    /**
     * Switches language now and records it for the account.
     *
     * [onSyncFailed] fires when the write was rejected. The local switch is not
     * conditional on it — a rules problem or a dead network should cost the
     * syncing, not the setting — but it must be visible, because failing
     * silently here looks exactly like "my other phone never updates".
     */
    fun setLanguage(scope: CoroutineScope, tag: String, onSyncFailed: () -> Unit) {
        apply(tag)
        val id = uid ?: return
        scope.launch { if (!changeLanguage(id, tag)) onSyncFailed() }
    }

    private suspend fun seed(uid: String) {
        seedLanguage(uid, I18n.current.tag)
    }

    /** Switches the active language and mirrors it into the local cache. */
    private fun apply(tag: String) {
        I18n.use(tag)
        prefs.setLanguage(I18n.current.tag)
    }
}
