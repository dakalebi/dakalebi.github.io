package ge.dakalebi.domain.usecase

import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.StateFlow

class ObserveUserSettings(private val settings: SettingsRepository) {
    operator fun invoke(): StateFlow<UserSettings> = settings.settings
}

/**
 * Keeps this device and the signed-in account's settings in step, or stops with
 * null. A guest has no account, so for a guest the settings stay on the device.
 */
class SyncUserSettings(private val settings: SettingsRepository) {
    operator fun invoke(uid: String?) {
        if (uid == null) settings.stop() else settings.start(uid)
    }
}

/**
 * Changes one setting here and records it on the account.
 *
 * Writes only that setting, so two devices changing two different settings
 * cannot undo each other. Returns whether it reached the server. The device
 * keeps the change either way — a rules problem or a dead network should cost
 * the syncing, not the setting — but the caller needs to know, because failing
 * silently here looks exactly like "my other phone never updates".
 */
class ChangeUserSetting(private val settings: SettingsRepository) {
    suspend operator fun <T : Any> invoke(setting: Setting<T>, value: T): Boolean =
        settings.set(setting, value)
}
