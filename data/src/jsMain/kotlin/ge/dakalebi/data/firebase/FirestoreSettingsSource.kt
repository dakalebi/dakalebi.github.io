package ge.dakalebi.data.firebase

import ge.dakalebi.core.Log
import ge.dakalebi.data.AccountSettingsSource
import ge.dakalebi.domain.Clock
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.SettingsLoad
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.data.firebase.externals.DocumentSnapshot
import ge.dakalebi.data.firebase.externals.doc
import ge.dakalebi.data.firebase.externals.getDoc
import ge.dakalebi.data.firebase.externals.onSnapshot
import ge.dakalebi.data.firebase.externals.setDoc
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Account-level settings at `users/{uid}`.
 *
 * Deliberately separate from the `progress` subcollection underneath it:
 * progress is a document per episode written constantly during playback, while
 * this is one small document written only when someone flips a switch.
 */
class FirestoreSettingsSource(private val clock: Clock) : AccountSettingsSource {

    override suspend fun load(uid: String): SettingsLoad = runCatching {
        val snapshot = getDoc(ref(uid)).await()
        if (snapshot.exists()) SettingsLoad.Found(read(snapshot)) else SettingsLoad.Missing
    }.onFailure {
        Log.w("settings", "could not read settings for $uid", it)
    }.getOrDefault(SettingsLoad.Failed)

    override suspend fun save(uid: String, settings: UserSettings): Boolean {
        val payload = jsObject()
        // Only what was actually set. Writing a null would clear a field the
        // caller has no opinion about — the language picker knows nothing
        // about autoplay, and two devices must not undo each other. Every
        // setting's value is a JS primitive already, and the rules check types.
        for (setting in settings.present) payload[setting.key] = settings.valueOf(setting)
        payload.updatedAtMillis = clock.nowMillis()
        return try {
            // merge: this document will gain fields later, and a settings write
            // must never clobber one it does not know about.
            setDoc(ref(uid), payload, mergeOption()).await()
            true
        } catch (e: CancellationException) {
            // Not a rejection: the caller stopped waiting, and the write is still
            // on its way. Reporting it as a failure is what told people a change
            // had stayed on this device when it had synced.
            throw e
        } catch (e: Throwable) {
            Log.e("settings", "could not save settings for $uid", e)
            false
        }
    }

    /**
     * Cancelling the collection calls the unsubscribe function, which has to
     * happen on sign-out: left running against a uid that is no longer allowed
     * to read it, the listener produces a permission error on every reconnect.
     *
     * A listener error ends the listener, as Firestore does, but not the flow: it
     * is logged, and the flow goes quiet until it is cancelled.
     */
    override fun observe(uid: String): Flow<UserSettings> = callbackFlow {
        val unsubscribe = onSnapshot(
            ref(uid),
            { snapshot -> if (snapshot.exists()) trySend(read(snapshot)) },
            { error -> Log.w("settings", "settings listener dropped for $uid: $error") },
        )
        awaitClose { unsubscribe() }
    }

    private fun ref(uid: String) = doc(Firebase.db, USERS, uid)

    private fun read(snapshot: DocumentSnapshot): UserSettings {
        val data = snapshot.data()
        return Setting.all.fold(UserSettings()) { values, setting ->
            when (setting) {
                // Absent must stay absent: `dynBool` would turn "never set" into
                // an explicit false and switch autoplay off on a fresh account.
                is Setting.Flag -> dynBoolOrNull(data[setting.key])?.let { values.with(setting, it) }
                is Setting.Text -> dynString(data[setting.key])?.let { values.with(setting, it) }
            } ?: values
        }
    }

    private companion object {
        const val USERS = "users"
    }
}
