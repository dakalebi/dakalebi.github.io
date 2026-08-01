package ge.dakalebi.domain.model

/**
 * The signed-in viewer.
 *
 * A plain data class rather than the SDK's user object, so nothing above the
 * data layer has to know what an authentication provider looks like — and so a
 * test can produce one without a network.
 */
data class Account(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
)

/** Settings that follow the account rather than the device. */
data class UserSettings(
    /**
     * BCP-47 tag, nullable because an account that has never chosen one should
     * leave each device on whatever it was already showing rather than being
     * dragged to a default nobody picked.
     */
    val language: String?,
)

/**
 * What a read of the settings document found.
 *
 * "Absent" and "could not read" must not collapse into one answer: treating a
 * failed read as an empty account would let a device with a stale local value
 * overwrite the real setting on the next network blip.
 */
sealed interface SettingsLoad {
    data class Found(val settings: UserSettings) : SettingsLoad
    data object Missing : SettingsLoad
    data object Failed : SettingsLoad
}
