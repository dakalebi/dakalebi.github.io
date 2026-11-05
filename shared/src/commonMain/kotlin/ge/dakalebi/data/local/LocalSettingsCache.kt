package ge.dakalebi.data.local

import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.repository.SettingsCache

/**
 * The account settings as this device last knew them, one key per setting.
 *
 * Every read and write goes through [Setting.all], so a new setting is cached
 * with no change here. Storage failures are logged and swallowed: losing the
 * copy costs a default on the first frame, never the setting itself, which the
 * account still has.
 */
class LocalSettingsCache(private val store: KeyValueStore) : SettingsCache {

    override fun read(): UserSettings = Setting.all.fold(UserSettings()) { values, setting ->
        val raw = get(storageKey(setting)) ?: return@fold values
        when (setting) {
            is Setting.Flag -> when (raw) {
                "1" -> values.with(setting, true)
                "0" -> values.with(setting, false)
                else -> values
            }
            is Setting.Text -> if (raw.isBlank()) values else values.with(setting, raw)
        }
    }

    override fun write(settings: UserSettings) {
        for (setting in settings.present) {
            val raw = when (setting) {
                is Setting.Flag -> settings[setting]?.let { if (it) "1" else "0" }
                is Setting.Text -> settings[setting]
            } ?: continue
            set(storageKey(setting), raw)
        }
    }

    /**
     * Held as `uid:key,key`. The uid is there so one account's offline change is
     * never uploaded to the next account that signs in on this device; a mark
     * for any other uid reads as nothing.
     */
    override fun unsynced(uid: String): Set<Setting<*>> {
        val raw = get(UNSYNCED_KEY) ?: return emptySet()
        val owner = raw.substringBefore(SEPARATOR)
        if (owner != uid) return emptySet()
        val keys = raw.substringAfter(SEPARATOR).split(',').toSet()
        return Setting.all.filter { it.key in keys }.toSet()
    }

    override fun markUnsynced(uid: String, setting: Setting<*>) = writeUnsynced(uid, unsynced(uid) + setting)

    override fun markSynced(uid: String, settings: Set<Setting<*>>) {
        val current = unsynced(uid)
        if (current.isEmpty() || settings.none { it in current }) return
        writeUnsynced(uid, current - settings)
    }

    private fun writeUnsynced(uid: String, settings: Set<Setting<*>>) {
        if (settings.isEmpty()) {
            runCatching { store.remove(UNSYNCED_KEY) }.onFailure { Log.w("settings", "could not clear $UNSYNCED_KEY", it) }
        } else {
            set(UNSYNCED_KEY, uid + SEPARATOR + settings.joinToString(",") { it.key })
        }
    }

    private fun get(key: String): String? =
        runCatching { store.get(key) }.onFailure { Log.w("settings", "could not read $key", it) }.getOrNull()

    private fun set(key: String, value: String) {
        runCatching { store.set(key, value) }.onFailure { Log.w("settings", "could not write $key", it) }
    }

    private companion object {
        const val UNSYNCED_KEY = "settings_unsynced"
        const val SEPARATOR = ':'

        /**
         * Where each setting lives in storage. The first three are the keys these
         * settings used when they were per-device preferences. Reading the same
         * keys is the whole local migration: a value set before this change is
         * still this device's opinion, so the first sync uploads it to an
         * account that has none rather than losing it. A new setting gets
         * `setting_<key>` and needs no entry.
         */
        val LEGACY_KEYS = mapOf(
            "language" to "language",
            "autoplayNext" to "autoplay_next_episode",
            "useNativePlayer" to "use_native_player",
            "preferredQuality" to "preferred_quality",
        )

        fun storageKey(setting: Setting<*>): String = LEGACY_KEYS[setting.key] ?: "setting_${setting.key}"
    }
}
