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
