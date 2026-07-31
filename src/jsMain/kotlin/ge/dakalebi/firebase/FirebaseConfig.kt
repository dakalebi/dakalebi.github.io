package ge.dakalebi.firebase

/**
 * Firebase web app configuration.
 *
 * These values are **public by design** — they identify the project, they do not
 * authorize anything, and they have to be in the static bundle for the SDK to
 * work at all. Access control lives entirely in `firebase/firestore.rules` and
 * in the Authorized Domains list in the Firebase console.
 *
 * Fill these in from:
 *   Firebase console -> Project settings -> General -> Your apps -> Web app -> Config
 */
object FirebaseConfig {
    const val API_KEY: String = "REPLACE_ME"
    const val AUTH_DOMAIN: String = "REPLACE_ME.firebaseapp.com"
    const val PROJECT_ID: String = "REPLACE_ME"
    const val STORAGE_BUCKET: String = "REPLACE_ME.firebasestorage.app"
    const val MESSAGING_SENDER_ID: String = "REPLACE_ME"
    const val APP_ID: String = "REPLACE_ME"

    /**
     * Firebase Auth UIDs allowed to refresh the episode catalog. Must match the
     * allowlist in `firebase/firestore.rules` — this one only controls whether
     * the Reload button is shown; the rules are what actually enforce it.
     */
    val ADMIN_UIDS: Set<String> = setOf("REPLACE_WITH_YOUR_FIREBASE_UID")

    val isConfigured: Boolean get() = API_KEY != "REPLACE_ME"
}
