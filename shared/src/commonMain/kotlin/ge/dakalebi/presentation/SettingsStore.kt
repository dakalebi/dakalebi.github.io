package ge.dakalebi.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.SettingsLoad
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.repository.SettingsCache
import ge.dakalebi.domain.service.SettingsSync
import ge.dakalebi.domain.usecase.LoadUserSettings
import ge.dakalebi.domain.usecase.ObserveUserSettings
import ge.dakalebi.domain.usecase.SaveUserSettings
import ge.dakalebi.i18n.I18n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Account settings, live across devices. Every [Setting] goes through here, and
 * nothing here names one except the language, which has to reach the string
 * catalogue.
 *
 * [current] starts from this device's copy, so the first frame renders the real
 * values instead of flashing defaults while the network answers. Signed in, the
 * account and the device meet by the rules in [SettingsSync]: a change the
 * account has not confirmed wins, otherwise the account wins, and a value the
 * account never had is uploaded from here.
 */
class SettingsStore(
    private val loadUserSettings: LoadUserSettings,
    private val observeUserSettings: ObserveUserSettings,
    private val saveUserSettings: SaveUserSettings,
    private val cache: SettingsCache,
) {
    /**
     * The values someone actually chose, here or on another device. One Compose
     * state for all of them: a screen that reads any setting recomposes when it
     * changes, and settings change rarely enough that finer state buys nothing.
     */
    var current: UserSettings by mutableStateOf(cache.read())
        private set

    private var uid: String? = null
    private var unsubscribe: (() -> Unit)? = null

    /**
     * Where this account's writes run: the app's own scope, handed to [start].
     * Never a screen's. Firestore sends a write whether or not anyone waits for
     * the answer, so a write tied to the menu it came from lands anyway when the
     * menu closes, but its confirmation is lost. The change then stays marked
     * unconfirmed, and this device ignores every other device's value for that
     * setting from then on.
     */
    private var scope: CoroutineScope? = null

    /**
     * The first read of the account failed, so the upload and the offline
     * changes it would have carried are still owed. Retried when the listener
     * next hears from the server, which proves it is reachable again — the case
     * that matters is a television whose app starts before its Wi-Fi does.
     */
    private var syncOwed = false

    /** The setting's value, or its default when nobody has chosen one. */
    operator fun <T : Any> get(setting: Setting<T>): T = current[setting] ?: setting.default

    /** Applies the language this device last saw. Safe before sign-in. */
    fun applyCachedLanguage() {
        current[Setting.Language]?.let(I18n::use)
    }

    /**
     * Attaches to the signed-in account. [scope] must live as long as the app,
     * because every write for this account runs in it.
     */
    fun start(scope: CoroutineScope, uid: String) {
        if (this.uid == uid) return
        stop()
        this.uid = uid
        this.scope = scope

        scope.launch { sync(uid) }
        unsubscribe = observeUserSettings(uid) { remote -> onRemote(scope, uid, remote) }
    }

    /** Detaches. This device's unconfirmed changes stay marked for their account. */
    fun stop() {
        unsubscribe?.invoke()
        unsubscribe = null
        uid = null
        scope = null
        syncOwed = false
    }

    /**
     * Changes [setting] now and records it for the account.
     *
     * [onSyncFailed] fires when the write was rejected. The local change is not
     * conditional on it — a rules problem or a dead network should cost the
     * syncing, not the setting — but it must be visible, because failing
     * silently here looks exactly like "my other phone never updates". The
     * change stays marked as unconfirmed, so the next sync uploads it.
     *
     * Takes no scope on purpose: the write runs in the app's (see [scope]), so
     * closing the screen that made the change cannot lose its confirmation.
     */
    fun <T : Any> set(setting: Setting<T>, value: T, onSyncFailed: () -> Unit) {
        val change = UserSettings().with(setting, value)
        adopt(change)
        val id = uid ?: return
        val appScope = scope ?: return
        cache.markUnsynced(id, setting)
        appScope.launch {
            if (saveUserSettings(id, change)) settle(id, change) else onSyncFailed()
        }
    }

    fun toggle(setting: Setting<Boolean>, onSyncFailed: () -> Unit) =
        set(setting, !get(setting), onSyncFailed)

    private suspend fun sync(uid: String) {
        val remote = when (val load = loadUserSettings(uid)) {
            is SettingsLoad.Found -> load.settings
            SettingsLoad.Missing -> UserSettings()
            // Change nothing either side. Uploading here would push possibly
            // stale local values over the account's real ones because one read
            // failed.
            SettingsLoad.Failed -> {
                Log.w("settings", "settings unavailable; staying on this device's copy")
                if (this.uid == uid) syncOwed = true
                return
            }
        }
        if (this.uid != uid) return

        val plan = SettingsSync.plan(remote, current, cache.unsynced(uid))
        adopt(plan.adopt)
        if (plan.upload.isEmpty()) return
        Log.d("settings", "uploading from this device: ${plan.upload.present}")
        if (saveUserSettings(uid, plan.upload)) {
            settle(uid, plan.upload)
        } else {
            Log.w("settings", "could not upload this device's settings; the next sync retries")
        }
    }

    /**
     * Another device's change, or the echo of one of ours. Only the account side
     * of the rules applies here — never an upload — so a snapshot can never
     * trigger a write that triggers a snapshot.
     */
    private fun onRemote(scope: CoroutineScope, uid: String, remote: UserSettings) {
        if (this.uid != uid) return
        if (syncOwed) {
            syncOwed = false
            scope.launch { sync(uid) }
            return
        }
        adopt(SettingsSync.plan(remote, current, cache.unsynced(uid)).adopt)
    }

    /** Clears the unconfirmed mark for what was written, unless it changed again since. */
    private fun settle(uid: String, written: UserSettings) {
        val confirmed = written.present.filterTo(mutableSetOf()) { current.valueOf(it) == written.valueOf(it) }
        cache.markSynced(uid, confirmed)
    }

    private fun adopt(values: UserSettings) {
        if (values.isEmpty()) return
        current += values
        cache.write(values)
        values[Setting.Language]?.let(I18n::use)
    }
}
