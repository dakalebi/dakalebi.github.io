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
 * this is one small document written only when someone flips a switch.
 *
 * Nothing security-relevant lives here. Admin rights are a different
 * collection entirely — see [AdminRepository] — precisely because this
 * document *is* writable by the person it belongs to.
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

    suspend fun save(uid: String, settings: UserSettings) {
        val payload = jsObject()
        payload.autoplayNext = settings.autoplayNext
        payload.updatedAtMillis = nowMillis()
        runCatching {
            // merge: this document may gain fields later, and a settings write
            // should never clobber one it does not know about.
            setDoc(ref(uid), payload, mergeOption()).await()
        }.onFailure { Log.e("settings", "could not save settings for $uid", it) }
    }

    /**
     * Watches the document so a change made on one device reaches the others
     * without a reload — which is the whole point of storing this server-side
     * rather than in `localStorage`.
     *
     * Returns the unsubscribe function; the caller must invoke it on sign-out,
     * or the listener keeps running against a uid that is no longer allowed to
     * read it and produces a permission error every reconnect.
     */
    fun listen(uid: String, onChange: (UserSettings) -> Unit): () -> Unit =
        onSnapshot(
            ref(uid),
            { snapshot -> if (snapshot.exists()) onChange(read(snapshot)) },
            { error -> Log.w("settings", "settings listener dropped for $uid: $error") },
        )

    private fun read(snapshot: DocumentSnapshot): UserSettings {
        val data = snapshot.data()
        return UserSettings(autoplayNext = dynBool(data.autoplayNext))
    }
}

/** Settings that follow the account rather than the device. */
data class UserSettings(val autoplayNext: Boolean)
