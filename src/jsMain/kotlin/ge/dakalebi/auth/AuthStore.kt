package ge.dakalebi.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.app.Log
import ge.dakalebi.firebase.Firebase
import ge.dakalebi.firebase.FirebaseConfig
import ge.dakalebi.firebase.externals.FirebaseUser
import ge.dakalebi.firebase.externals.GoogleAuthProvider
import ge.dakalebi.firebase.externals.createUserWithEmailAndPassword
import ge.dakalebi.firebase.externals.onAuthStateChanged
import ge.dakalebi.firebase.externals.sendPasswordResetEmail
import ge.dakalebi.firebase.externals.signInWithEmailAndPassword
import ge.dakalebi.firebase.externals.signInWithPopup
import ge.dakalebi.firebase.externals.signOut
import ge.dakalebi.i18n.S
import kotlinx.coroutines.await

object AuthStore {
    var user: FirebaseUser? by mutableStateOf(null)
        private set

    /** True until Firebase has reported the restored session, if any. */
    var loading: Boolean by mutableStateOf(true)
        private set

    val uid: String? get() = user?.uid
    val email: String? get() = user?.email

    /**
     * Controls whether the refresh action is offered. The Firestore rules are
     * what actually enforce this — the flag only avoids showing a button that
     * would fail.
     */
    val isAdmin: Boolean
        get() = user?.let {
            it.uid in FirebaseConfig.ADMIN_UIDS ||
                (it.emailVerified && it.email in FirebaseConfig.ADMIN_EMAILS)
        } == true

    fun start() {
        onAuthStateChanged(Firebase.auth) { next ->
            user = next
            loading = false
        }
    }

    suspend fun signIn(email: String, password: String) {
        signInWithEmailAndPassword(Firebase.auth, email, password).await()
    }

    suspend fun signUp(email: String, password: String) {
        createUserWithEmailAndPassword(Firebase.auth, email, password).await()
    }

    /**
     * Popup rather than redirect: with the app on `*.github.io` and the auth
     * handler on `*.firebaseapp.com`, the redirect flow depends on third-party
     * storage access that browsers now block.
     */
    suspend fun signInWithGoogle() {
        signInWithPopup(Firebase.auth, GoogleAuthProvider()).await()
    }

    suspend fun resetPassword(email: String) {
        sendPasswordResetEmail(Firebase.auth, email).await()
    }

    suspend fun signOutNow() {
        signOut(Firebase.auth).await()
    }
}

/**
 * Firebase errors arrive as `{ code, message }`. The raw codes are useless to a
 * reader, so map the ones a person can actually act on.
 */
fun authErrorMessage(error: Throwable): String {
    val code = Log.codeOf(error).orEmpty()

    return when {
        code.contains("invalid-credential") || code.contains("wrong-password") ||
            code.contains("user-not-found") -> S.errWrongCredentials
        code.contains("invalid-email") -> S.errInvalidEmail
        code.contains("email-already-in-use") -> S.errEmailInUse
        code.contains("weak-password") -> S.errWeakPassword
        code.contains("too-many-requests") -> S.errTooManyRequests
        code.contains("popup-closed-by-user") || code.contains("cancelled-popup-request") ->
            S.errPopupClosed
        code.contains("popup-blocked") -> S.errPopupBlocked
        code.contains("unauthorized-domain") ->
            S.errUnauthorizedDomain
        code.contains("network-request-failed") -> S.errNetwork
        else -> S.errSignInFailed
    }
}
