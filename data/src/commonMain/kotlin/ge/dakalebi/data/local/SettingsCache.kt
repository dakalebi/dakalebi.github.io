package ge.dakalebi.data.local

import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings

/**
 * This device's copy of the account settings.
 *
 * It renders the first frame with the right values instead of flashing defaults
 * while the network answers, and it is the only copy that works offline. It also
 * remembers which of this device's changes the account has not confirmed yet,
 * per account, so a change made offline survives a reload and is uploaded on
 * the next sync instead of being overwritten. See
 * [ge.dakalebi.domain.service.SettingsSync].
 *
 * Synchronous because the first frame reads it.
 */
interface SettingsCache {
    /** What this device last knew, holding only settings that were actually set. */
    fun read(): UserSettings

    /** Stores the settings [settings] holds and leaves the rest alone. */
    fun write(settings: UserSettings)

    /** This device's changes for [uid] that the account has not confirmed. */
    fun unsynced(uid: String): Set<Setting<*>>

    fun markUnsynced(uid: String, setting: Setting<*>)

    fun markSynced(uid: String, settings: Set<Setting<*>>)
}
