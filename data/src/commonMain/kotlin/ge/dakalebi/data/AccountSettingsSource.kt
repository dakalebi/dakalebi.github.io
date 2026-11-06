package ge.dakalebi.data

import ge.dakalebi.domain.model.SettingsLoad
import ge.dakalebi.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow

/**
 * One account's settings document: the account's half of the settings sync.
 * Firestore in the app, a map in the tests and the fixture.
 *
 * Nothing above `:data` sees this. They see [SyncedSettingsRepository], which
 * puts it together with the device copy.
 */
interface AccountSettingsSource {
    suspend fun load(uid: String): SettingsLoad

    /**
     * Writes the settings [settings] holds and leaves the rest alone.
     *
     * A setting with no value means "no opinion", not "clear it" — two devices
     * changing two different settings must not overwrite each other, and the
     * language picker knows nothing about autoplay. Returns false when the write
     * was rejected: the rules said no, or the network failed it.
     */
    suspend fun save(uid: String, settings: UserSettings): Boolean

    /**
     * The document as it stands, on every change: another device's, or the echo
     * of this one's. Collecting attaches the live listener and cancelling
     * detaches it, which has to happen on sign-out: left attached, it keeps
     * querying a document the signed-out client may no longer read.
     */
    fun observe(uid: String): Flow<UserSettings>
}
