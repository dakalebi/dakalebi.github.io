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
 * Records the chosen language for the account.
 *
 * Returns whether it reached the server. The caller changes the language
 * locally either way — a rules problem or a dead network should cost the
 * syncing, not the setting — but it needs to know, because silently failing
 * here looks exactly like "my other phone never updates".
 */
class ChangeLanguage(private val settings: SettingsRepository) {
    suspend operator fun invoke(uid: String, tag: String): Boolean =
        settings.save(uid, UserSettings(language = tag))
}

/**
 * Records the autoplay choice for the account.
 *
 * Account-level rather than per-device: "play the next episode automatically"
 * describes how someone watches, not which screen they are holding.
 */
class ChangeAutoplay(private val settings: SettingsRepository) {
    suspend operator fun invoke(uid: String, value: Boolean): Boolean =
        settings.save(uid, UserSettings(autoplayNext = value))
}

/**
 * Gives an account with nothing on record what this device is already using.
 *
 * Without it the first device to sign in would keep being asked and nothing
 * would ever reach the second one.
 */
class SeedSettings(private val settings: SettingsRepository) {
    suspend operator fun invoke(uid: String, settingsToSeed: UserSettings): Boolean =
        settings.save(uid, settingsToSeed)
}
