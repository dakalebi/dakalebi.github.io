package ge.dakalebi.domain.model

/**
 * Who is using the app: someone signed in, or a guest.
 *
 * A guest browses and watches like anyone else, but nothing about them leaves the
 * device. Their progress is filed on this device under [Guest.id], and nothing
 * account-shaped (settings sync, the admin roster) is ever asked about them.
 */
sealed interface Viewer {
    /** The key this viewer's watch progress is filed under. */
    val id: String

    data class Member(val account: Account) : Viewer {
        override val id: String get() = account.uid
    }

    data object Guest : Viewer {
        /**
         * Not a shape a Firebase uid can take: those are alphanumeric, so the colon
         * keeps a guest's key from ever naming a real account's progress.
         */
        override val id: String = "guest:device"
    }
}
