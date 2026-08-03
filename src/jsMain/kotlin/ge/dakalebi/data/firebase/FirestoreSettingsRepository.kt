package ge.dakalebi.data.firebase

import ge.dakalebi.core.Log
import ge.dakalebi.domain.Clock
import ge.dakalebi.domain.model.SettingsLoad
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.repository.SettingsRepository
import ge.dakalebi.data.firebase.externals.DocumentSnapshot
import ge.dakalebi.data.firebase.externals.doc
import ge.dakalebi.data.firebase.externals.getDoc
import ge.dakalebi.data.firebase.externals.onSnapshot
import ge.dakalebi.data.firebase.externals.setDoc
import kotlinx.coroutines.await

/**
 * Account-level settings at `users/{uid}`.
 *
 * Deliberately separate from the `progress` subcollection underneath it:
 * progress is a document per episode written constantly during playback, while
 * this is one small document written only when someone flips a switch.
 */
class FirestoreSettingsRepository(private val clock: Clock) : SettingsRepository {

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
        // about autoplay, and two devices must not undo each other.
        settings.language?.let { payload.language = it }
        settings.autoplayNext?.let { payload.autoplayNext = it }
        payload.updatedAtMillis = clock.nowMillis()
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
     * Returns the unsubscribe function; the caller must invoke it on sign-out,
     * or the listener keeps running against a uid that is no longer allowed to
     * read it and produces a permission error on every reconnect.
     */
    override fun observe(uid: String, onChange: (UserSettings) -> Unit): () -> Unit =
        onSnapshot(
            ref(uid),
            { snapshot -> if (snapshot.exists()) onChange(read(snapshot)) },
            { error -> Log.w("settings", "settings listener dropped for $uid: $error") },
        )

    private fun ref(uid: String) = doc(Firebase.db, USERS, uid)

    private fun read(snapshot: DocumentSnapshot): UserSettings {
        val data = snapshot.data()
        return UserSettings(
            language = dynString(data.language),
            // Absent must stay absent: `dynBool` would turn "never set" into
            // an explicit false and switch autoplay off on a fresh account.
            autoplayNext = dynBoolOrNull(data.autoplayNext),
        )
    }

    private companion object {
        const val USERS = "users"
    }
}
