package ge.dakalebi.web.firebase

/**
 * Firebase web configuration for project `dakalebi-tv`.
 *
 * Public by design — these identify the project and authorize nothing; access control is
 * in the Firestore rules and the Authorized Domains list. Mirrors the root app's
 * `FirebaseConfig`; the value is the same because it is the same Firebase project, so the
 * 2.0 app sees the same accounts and data.
 */
internal object FirebaseConfig {
    const val API_KEY = "AIzaSyAF405hmXcpQ8oEV134fO0LgPIWDPAbmKo"
    const val AUTH_DOMAIN = "dakalebi-tv.firebaseapp.com"
    const val PROJECT_ID = "dakalebi-tv"
    const val STORAGE_BUCKET = "dakalebi-tv.firebasestorage.app"
    const val MESSAGING_SENDER_ID = "916841955985"
    const val APP_ID = "1:916841955985:web:dc8ee35c64237a037a3530"
}
