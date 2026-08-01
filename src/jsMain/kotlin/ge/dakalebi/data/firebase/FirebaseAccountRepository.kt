package ge.dakalebi.data.firebase

import ge.dakalebi.data.firebase.externals.GoogleAuthProvider
import ge.dakalebi.data.firebase.externals.createUserWithEmailAndPassword
import ge.dakalebi.data.firebase.externals.onAuthStateChanged
import ge.dakalebi.data.firebase.externals.sendPasswordResetEmail
import ge.dakalebi.data.firebase.externals.signInWithEmailAndPassword
import ge.dakalebi.data.firebase.externals.signInWithPopup
import ge.dakalebi.data.firebase.externals.signOut
import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.repository.AccountRepository
import kotlinx.coroutines.await

/**
 * Firebase Authentication, mapped down to [Account].
 *
 * The SDK's user object never leaves this file. Everything above works with a
 * three-field data class it can construct itself, which is what makes the
 * signed-in paths testable at all.
 */
class FirebaseAccountRepository : AccountRepository {

    override fun observe(onChange: (Account?) -> Unit) {
        onAuthStateChanged(Firebase.auth) { user ->
            onChange(
                user?.let {
                    Account(uid = it.uid, email = it.email, emailVerified = it.emailVerified)
                },
            )
        }
    }

    override suspend fun signIn(email: String, password: String) {
        signInWithEmailAndPassword(Firebase.auth, email, password).await()
    }

    override suspend fun signUp(email: String, password: String) {
        createUserWithEmailAndPassword(Firebase.auth, email, password).await()
    }

    /**
     * Popup rather than redirect: with the app on `*.github.io` and the auth
     * handler on `*.firebaseapp.com`, the redirect flow depends on third-party
     * storage access that browsers now block.
     */
    override suspend fun signInWithGoogle() {
        signInWithPopup(Firebase.auth, GoogleAuthProvider()).await()
    }

    override suspend fun sendPasswordReset(email: String) {
        sendPasswordResetEmail(Firebase.auth, email).await()
    }

    override suspend fun signOut() {
        signOut(Firebase.auth).await()
    }

    override fun isAdmin(account: Account): Boolean =
        account.uid in FirebaseConfig.ADMIN_UIDS ||
            (account.emailVerified && account.email in FirebaseConfig.ADMIN_EMAILS)
}
