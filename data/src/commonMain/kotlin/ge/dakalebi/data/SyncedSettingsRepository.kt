package ge.dakalebi.data

import ge.dakalebi.core.Log
import ge.dakalebi.data.local.SettingsCache
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.SettingsLoad
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.repository.SettingsRepository
import ge.dakalebi.domain.service.SettingsSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Account settings, live across devices: where this device's copy and the
 * account's document meet.
 *
 * [settings] starts from the device copy, so the first frame renders the real
 * values instead of flashing defaults while the network answers. Once an account
 * is attached, the two meet by the rules in [SettingsSync]: a change the account
 * has not confirmed wins, otherwise the account wins, and a value the account
 * never had is uploaded from here.
 *
 * [scope] must live as long as the app, because every write and every sync runs
 * in it. Never a screen's: Firestore sends a write whether or not anyone waits
 * for the answer, so a write tied to the menu it came from lands anyway when the
 * menu closes, but its confirmation is lost. The change then stays marked
 * unconfirmed, and this device ignores every other device's value for that
 * setting from then on.
 */
class SyncedSettingsRepository(
    private val account: AccountSettingsSource,
    private val cache: SettingsCache,
    private val scope: CoroutineScope,
) : SettingsRepository {

    private val current = MutableStateFlow(cache.read())

    override val settings: StateFlow<UserSettings> = current.asStateFlow()

    private var uid: String? = null
    private var listener: Job? = null

    /**
     * The first read of the account failed, so the upload and the offline
     * changes it would have carried are still owed. Retried when the listener
     * next hears from the server, which proves it is reachable again — the case
     * that matters is a television whose app starts before its Wi-Fi does.
     */
    private var syncOwed = false

    override fun start(uid: String) {
        if (this.uid == uid) return
        stop()
        this.uid = uid

        scope.launch { sync(uid) }
        // Undispatched, so the listener is attached before this returns.
        listener = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            account.observe(uid).collect { remote -> onRemote(uid, remote) }
        }
    }

    override fun stop() {
        listener?.cancel()
        listener = null
        uid = null
        syncOwed = false
    }

    override suspend fun <T : Any> set(setting: Setting<T>, value: T): Boolean {
        val change = UserSettings().with(setting, value)
        adopt(change)
        val id = uid ?: return true
        cache.markUnsynced(id, setting)
        // In the app's scope, not the caller's (see the class comment), so a
        // caller that stops waiting cannot cost the confirmation.
        return scope.async { upload(id, change) }.await()
    }

    private suspend fun sync(uid: String) {
        val remote = when (val load = account.load(uid)) {
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

        val plan = SettingsSync.plan(remote, current.value, cache.unsynced(uid))
        adopt(plan.adopt)
        if (plan.upload.isEmpty()) return
        Log.d("settings", "uploading from this device: ${plan.upload.present}")
        // One write per setting. The rules check the whole document, so a field
        // they do not allow yet (published after the bundle that writes it)
        // would refuse every other setting sent with it, on every start.
        for (setting in plan.upload.present) {
            val one = setting.copy(plan.upload, UserSettings())
            if (!upload(uid, one)) {
                Log.w("settings", "could not upload $setting from this device; the next sync retries")
            }
        }
    }

    /**
     * Another device's change, or the echo of one of ours. Only the account side
     * of the rules applies here — never an upload — so a snapshot can never
     * trigger a write that triggers a snapshot.
     */
    private fun onRemote(uid: String, remote: UserSettings) {
        if (this.uid != uid) return
        if (syncOwed) {
            syncOwed = false
            scope.launch { sync(uid) }
            return
        }
        adopt(SettingsSync.plan(remote, current.value, cache.unsynced(uid)).adopt)
    }

    /** Writes [values] to the account and, when it lands, clears their unconfirmed mark. */
    private suspend fun upload(uid: String, values: UserSettings): Boolean {
        val saved = account.save(uid, values)
        if (saved) settle(uid, values)
        return saved
    }

    /** Clears the unconfirmed mark for what was written, unless it changed again since. */
    private fun settle(uid: String, written: UserSettings) {
        val now = current.value
        val confirmed = written.present.filterTo(mutableSetOf()) { now.valueOf(it) == written.valueOf(it) }
        cache.markSynced(uid, confirmed)
    }

    private fun adopt(values: UserSettings) {
        if (values.isEmpty()) return
        current.update { it + values }
        cache.write(values)
    }
}
