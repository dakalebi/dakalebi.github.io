package ge.dakalebi.data

import ge.dakalebi.app.Log
import ge.dakalebi.app.nowMillis
import ge.dakalebi.firebase.Firebase
import ge.dakalebi.firebase.externals.DocumentSnapshot
import ge.dakalebi.firebase.externals.doc
import ge.dakalebi.firebase.externals.getDoc
import ge.dakalebi.firebase.externals.onSnapshot
import ge.dakalebi.firebase.externals.setDoc
import kotlinx.coroutines.await

/**
 * Account-level settings at `users/{uid}`.
 *
 * Deliberately separate from the `progress` subcollection underneath it:
 * progress is a document per episode written constantly during playback, while
 * this is one small document written only when someone flips a switch in the
 * drawer.
 *
 * Only settings that should follow the *person* live here. Which video player
 * suits the device in your hand does not — that stays in `Prefs`.
 */
object SettingsRepository {
    private const val USERS = "users"

    private fun ref(uid: String) = doc(Firebase.db, USERS, uid)

    /**
     * "Absent" and "could not read" must not collapse into the same answer.
     * Treating a failed read as an empty account would let a device with a
     * stale local value overwrite the real setting on the next network blip.
     */
    sealed interface Load {
        data class Found(val settings: UserSettings) : Load
        data object Missing : Load
        data object Failed : Load
    }

    suspend fun load(uid: String): Load = runCatching {
        val snapshot = getDoc(ref(uid)).await()
        if (snapshot.exists()) Load.Found(read(snapshot)) else Load.Missing
    }.onFailure {
        Log.w("settings", "could not read settings for $uid", it)
    }.getOrDefault(Load.Failed)

    suspend fun save(uid: String, settings: UserSettings): Boolean {
        val payload = jsObject()
        payload.language = settings.language
        payload.updatedAtMillis = nowMillis()
        return runCatching {
            // merge: this document will gain fields later, and a settings write
            // must never clobber one it does not know about.
            setDoc(ref(uid), payload, mergeOption()).await()
            true
        }.onFailure {
            Log.e("settings", "could not save settings for $uid", it)
        }.getOrDefault(false)
    }

    /**
     * Watches the document so a change made on one device reaches the others
     * without a reload — which is the whole point of storing this server-side
     * rather than in `localStorage`.
     *
     * Returns the unsubscribe function; the caller must invoke it on sign-out,
     * or the listener keeps running against a uid that is no longer allowed to
     * read it and produces a permission error on every reconnect.
     */
    fun listen(uid: String, onChange: (UserSettings) -> Unit): () -> Unit =
        onSnapshot(
            ref(uid),
            { snapshot -> if (snapshot.exists()) onChange(read(snapshot)) },
            { error -> Log.w("settings", "settings listener dropped for $uid: $error") },
        )

    private fun read(snapshot: DocumentSnapshot): UserSettings {
        val data = snapshot.data()
        return UserSettings(language = dynString(data.language))
    }
}

/**
 * Settings that follow the account rather than the device.
 *
 * [language] is nullable because an account that has never chosen one should
 * leave each device on whatever it was already showing, rather than being
 * dragged to a default someone never picked.
 */
data class UserSettings(val language: String?)
