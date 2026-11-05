package ge.dakalebi.domain.service

import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings

/**
 * Which side wins, setting by setting, when this device and the account meet.
 *
 * Three rules, in this order:
 *
 *  1. **A change this device made that the account has not confirmed wins.** It
 *     was made offline, or the write was rejected, or the page closed before the
 *     write landed. Firestore here keeps pending writes in memory only, so without
 *     this the next load would quietly hand the setting back to the server's older
 *     value — the "my switch didn't stick" bug.
 *  2. **Otherwise the account wins** whenever it has a value. That is what makes a
 *     change on one device arrive on the others.
 *  3. **A value the account has never had is uploaded from this device.** This is
 *     the migration path: a setting chosen before it synced (System player was
 *     per-device for a long time) becomes the account's on the first sign-in,
 *     rather than being overwritten by a default nobody picked.
 *
 * A setting neither side has an opinion on is left alone on both. So a device
 * that never touched a switch never pushes its default over someone's choice.
 */
object SettingsSync {

    /** What to apply on this device, and what to write to the account. */
    data class Plan(val adopt: UserSettings, val upload: UserSettings)

    fun plan(remote: UserSettings, local: UserSettings, unsynced: Set<Setting<*>>): Plan {
        var adopt = UserSettings()
        var upload = UserSettings()
        for (setting in Setting.all) {
            val mine = local.valueOf(setting)
            val theirs = remote.valueOf(setting)
            when {
                mine != null && setting in unsynced -> upload = setting.copy(local, upload)
                theirs != null -> if (theirs != mine) adopt = setting.copy(remote, adopt)
                mine != null -> upload = setting.copy(local, upload)
            }
        }
        return Plan(adopt, upload)
    }
}
