package ge.dakalebi.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    val isAdmin: Boolean get() = user?.uid?.let { it in FirebaseConfig.ADMIN_UIDS } == true

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
    val code = runCatching {
        val dyn: dynamic = error
        (dyn.code as? String) ?: (dyn.cause?.code as? String)
    }.getOrNull().orEmpty()

    return when {
        code.contains("invalid-credential") || code.contains("wrong-password") ||
            code.contains("user-not-found") -> "ელფოსტა ან პაროლი არასწორია"
        code.contains("invalid-email") -> "ელფოსტა არასწორია"
        code.contains("email-already-in-use") -> "ეს ელფოსტა უკვე რეგისტრირებულია"
        code.contains("weak-password") -> "პაროლი ძალიან მოკლეა — მინიმუმ 6 სიმბოლო"
        code.contains("too-many-requests") -> "ბევრი მცდელობა იყო — სცადე ცოტა ხანში"
        code.contains("popup-closed-by-user") || code.contains("cancelled-popup-request") ->
            "შესვლა შეწყდა"
        code.contains("popup-blocked") -> "ბრაუზერმა ფანჯარა დაბლოკა — დართე ნება და სცადე თავიდან"
        code.contains("unauthorized-domain") ->
            "ეს დომენი Firebase-ში ნებადართული არაა (Authentication → Settings → Authorized domains)"
        code.contains("network-request-failed") -> "ქსელთან კავშირი ვერ მოხერხდა"
        else -> "შესვლა ვერ მოხერხდა"
    }
}
