package ge.dakalebi.data

import ge.dakalebi.app.Log
import ge.dakalebi.app.Prefs
import ge.dakalebi.app.Toasts
import ge.dakalebi.i18n.I18n
import ge.dakalebi.i18n.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Account settings, live across devices.
 *
 * There is no state of its own here: the language *is* [I18n.current], and
 * `localStorage` holds the copy that renders the first frame. This object only
 * owns the plumbing between the two and Firestore.
 *
 * `localStorage` is kept as a cache rather than dropped. It is what shows the
 * right language immediately after a reload instead of flashing Georgian while
 * Firestore answers, and it is the only copy that works offline or signed out.
 * Firestore wins whenever it has an opinion.
 */
object Settings {
    private var unsubscribe: (() -> Unit)? = null
    private var uid: String? = null

    /** Applies the language this device last saw. Safe before sign-in. */
    fun applyCachedLanguage() {
        Prefs.cachedLanguage?.let { I18n.use(it) }
    }

    /**
     * Attaches to the signed-in account.
     *
     * An account with no language on record is seeded from this device rather
     * than left empty — otherwise the first device to sign in would keep
     * asking, and nothing would ever reach the second one.
     */
    fun start(scope: CoroutineScope, uid: String) {
        if (this.uid == uid) return
        stop()
        this.uid = uid

        scope.launch {
            when (val remote = SettingsRepository.load(uid)) {
                is SettingsRepository.Load.Found -> {
                    val tag = remote.settings.language
                    if (tag == null) {
                        seed(uid)
                    } else {
                        apply(tag)
                    }
                }

                SettingsRepository.Load.Missing -> {
                    Log.d("settings", "no settings document yet, seeding from this device")
                    seed(uid)
                }

                // Keep whatever the cache had and change nothing server-side.
                // Seeding here would push a possibly-stale local value over the
                // account's real setting just because one read failed.
                SettingsRepository.Load.Failed ->
                    Log.w("settings", "settings unavailable; staying on the cached language")
            }
        }

        unsubscribe = SettingsRepository.listen(uid) { settings ->
            settings.language?.let { apply(it) }
        }
    }

    fun stop() {
        unsubscribe?.invoke()
        unsubscribe = null
        uid = null
    }

    /**
     * Switches language now and pushes it to the account.
     *
     * The local switch is not conditional on the write succeeding. A rules
     * problem or a dead network should cost you the syncing, not the setting —
     * but it should still say so, because a silent failure here looks exactly
     * like "my other phone never updates".
     */
    fun setLanguage(scope: CoroutineScope, tag: String) {
        apply(tag)
        val id = uid ?: return
        scope.launch {
            if (!SettingsRepository.save(id, UserSettings(language = tag))) {
                Toasts.error(S.languageNotSynced)
            }
        }
    }

    private suspend fun seed(uid: String) {
        SettingsRepository.save(uid, UserSettings(language = I18n.current.tag))
    }

    /** Switches the active language and mirrors it into the local cache. */
    private fun apply(tag: String) {
        I18n.use(tag)
        Prefs.cacheLanguage(I18n.current.tag)
    }
}
