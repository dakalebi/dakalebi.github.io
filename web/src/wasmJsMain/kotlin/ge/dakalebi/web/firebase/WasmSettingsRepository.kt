package ge.dakalebi.web.firebase

import ge.dakalebi.core.Log
import ge.dakalebi.domain.Clock
import ge.dakalebi.domain.model.SettingsLoad
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.repository.SettingsRepository
import kotlinx.coroutines.await

/**
 * Account-level settings at `users/{uid}`.
 *
 * Deliberately separate from the `progress` subcollection underneath it: progress is a
 * document per episode written constantly during playback, while this is one small document
 * written only when someone flips a switch.
 */
class WasmSettingsRepository(private val clock: Clock) : SettingsRepository {

    override suspend fun load(uid: String): SettingsLoad = runCatching {
        val snapshot = getDoc(ref(uid)).await()
        val data = snapshot.data().takeIf { snapshot.exists() }
        if (data != null) SettingsLoad.Found(read(data)) else SettingsLoad.Missing
    }.onFailure {
        Log.w("settings", "could not read settings for $uid", it)
    }.getOrDefault(SettingsLoad.Failed)

    override suspend fun save(uid: String, settings: UserSettings): Boolean {
        val payload = newObject()
        // Only what was actually set. Writing a null would clear a field the caller has no
        // opinion about — the language picker knows nothing about autoplay, and two devices
        // must not undo each other.
        settings.language?.let { putString(payload, "language", it) }
        settings.autoplayNext?.let { putBool(payload, "autoplayNext", it) }
        putDouble(payload, "updatedAtMillis", clock.nowMillis())
        return runCatching {
            // merge: this document will gain fields later, and a settings write must never
            // clobber one it does not know about.
            setDoc(ref(uid), payload, setOptions(merge = true)).await()
            true
        }.onFailure {
            Log.e("settings", "could not save settings for $uid", it)
        }.getOrDefault(false)
    }

    /**
     * Returns the unsubscribe function; the caller must invoke it on sign-out, or the
     * listener keeps running against a uid that is no longer allowed to read it and produces
     * a permission error on every reconnect.
     */
    override fun observe(uid: String, onChange: (UserSettings) -> Unit): () -> Unit {
        val unsubscribe = onSnapshot(
            ref(uid),
            { snapshot -> snapshot.data().takeIf { snapshot.exists() }?.let { onChange(read(it)) } },
            { error -> Log.w("settings", "settings listener dropped for $uid: $error") },
        )
        return { callUnsubscribe(unsubscribe) }
    }

    private fun ref(uid: String) = doc(FirebaseWasm.db, "$USERS/$uid")

    private fun read(data: JsAny) = UserSettings(
        language = readString(data, "language"),
        // Absent must stay absent: `readBool` would turn "never set" into an explicit false
        // and switch autoplay off on a fresh account.
        autoplayNext = readBoolOrNull(data, "autoplayNext"),
    )

    private companion object {
        const val USERS = "users"
    }
}
