package ge.dakalebi.data.firebase

/**
 * Firebase web app configuration for project `dakalebi-tv`.
 *
 * These values are **public by design** — they identify the project, they do not
 * authorize anything, and they have to be in the static bundle for the SDK to
 * work at all. Access control lives entirely in `firebase/firestore.rules` and
 * in the Authorized Domains list in the Firebase console.
 *
 * There is no admin allowlist here any more. It moved to the `admins`
 * collection, which the rules read with `exists()` — see
 * [ge.dakalebi.data.firebase.FirestoreAdminRepository].
 *
 * From: Firebase console -> Project settings -> General -> Your apps -> Web app
 */
object FirebaseConfig {
    const val API_KEY: String = "AIzaSyAF405hmXcpQ8oEV134fO0LgPIWDPAbmKo"
    const val AUTH_DOMAIN: String = "dakalebi-tv.firebaseapp.com"
    const val PROJECT_ID: String = "dakalebi-tv"
    const val STORAGE_BUCKET: String = "dakalebi-tv.firebasestorage.app"
    const val MESSAGING_SENDER_ID: String = "916841955985"
    const val APP_ID: String = "1:916841955985:web:dc8ee35c64237a037a3530"

    val isConfigured: Boolean get() = API_KEY != "REPLACE_ME"
}
