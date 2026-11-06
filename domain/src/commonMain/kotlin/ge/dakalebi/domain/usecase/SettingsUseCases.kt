package ge.dakalebi.domain.usecase

import ge.dakalebi.domain.model.SettingsLoad
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.repository.SettingsRepository

class LoadUserSettings(private val settings: SettingsRepository) {
    suspend operator fun invoke(uid: String): SettingsLoad = settings.load(uid)
}

class ObserveUserSettings(private val settings: SettingsRepository) {
    operator fun invoke(uid: String, onChange: (UserSettings) -> Unit): () -> Unit =
        settings.observe(uid, onChange)
}

/**
 * Records settings on the account: one the viewer just changed, or the values
 * a first sync uploads from this device.
 *
 * Writes only what [UserSettings] holds, so two devices changing two different
 * settings cannot undo each other. Returns whether it reached the server. The
 * caller changes the setting locally either way — a rules problem or a dead
 * network should cost the syncing, not the setting — but it needs to know,
 * because failing silently here looks exactly like "my other phone never
 * updates".
 */
class SaveUserSettings(private val settings: SettingsRepository) {
    suspend operator fun invoke(uid: String, values: UserSettings): Boolean =
        settings.save(uid, values)
}
