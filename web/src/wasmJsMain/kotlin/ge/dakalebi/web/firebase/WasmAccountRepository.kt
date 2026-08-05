package ge.dakalebi.web.firebase

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.repository.AccountRepository
import kotlinx.coroutines.await

/**
 * [AccountRepository] backed by the Firebase JS Auth SDK on wasmJs.
 *
 * The wasm twin of the root app's `FirebaseAccountRepository`: same behaviour, same
 * Firebase project, expressed through typed interop. The SDK user object never leaves
 * this file — everything above sees the plain [Account].
 */
class WasmAccountRepository : AccountRepository {

    override fun observe(onChange: (Account?) -> Unit) {
        onAuthStateChanged(FirebaseWasm.auth) { user ->
            onChange(
                user?.let {
                    Account(uid = it.uid, email = it.email, emailVerified = it.emailVerified)
                },
            )
        }
    }

    override suspend fun signIn(email: String, password: String) {
        signInWithEmailAndPassword(FirebaseWasm.auth, email, password).await()
    }

    override suspend fun signUp(email: String, password: String) {
        createUserWithEmailAndPassword(FirebaseWasm.auth, email, password).await()
    }

    /** Not wired for the 2.0 preview yet; the preview signs in with email + password. */
    override suspend fun signInWithGoogle() {
        throw UnsupportedOperationException("Google sign-in is not wired in the 2.0 preview yet")
    }

    override suspend fun sendPasswordReset(email: String) {
        sendPasswordResetEmail(FirebaseWasm.auth, email).await()
    }

    override suspend fun signOut() {
        signOut(FirebaseWasm.auth).await()
    }
}
