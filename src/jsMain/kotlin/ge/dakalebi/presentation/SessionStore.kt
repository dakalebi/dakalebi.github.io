package ge.dakalebi.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.usecase.CanRefreshCatalog
import ge.dakalebi.domain.usecase.ObserveAccount
import ge.dakalebi.domain.usecase.SendPasswordReset
import ge.dakalebi.domain.usecase.SignInWithEmail
import ge.dakalebi.domain.usecase.SignInWithGoogle
import ge.dakalebi.domain.usecase.SignOut
import ge.dakalebi.domain.usecase.SignUpWithEmail

/** Who is signed in, as Compose state. */
class SessionStore(
    private val observeAccount: ObserveAccount,
    private val canRefreshCatalog: CanRefreshCatalog,
    private val signInWithEmail: SignInWithEmail,
    private val signUpWithEmail: SignUpWithEmail,
    private val signInWithGoogleUseCase: SignInWithGoogle,
    private val sendPasswordResetUseCase: SendPasswordReset,
    private val signOutUseCase: SignOut,
) {
    var account: Account? by mutableStateOf(null)
        private set

    /** True until the provider has reported the restored session, if any. */
    var loading: Boolean by mutableStateOf(true)
        private set

    val uid: String? get() = account?.uid
    val email: String? get() = account?.email

    /**
     * Whether to offer the catalog refresh. The Firestore rules are what
     * actually enforce this; the flag only avoids showing a button that would
     * certainly fail.
     */
    val isAdmin: Boolean get() = canRefreshCatalog(account)

    fun start() {
        observeAccount { next ->
            account = next
            loading = false
        }
    }

    suspend fun signIn(email: String, password: String) = signInWithEmail(email, password)

    suspend fun signUp(email: String, password: String) = signUpWithEmail(email, password)

    suspend fun signInWithGoogle() = signInWithGoogleUseCase()

    suspend fun resetPassword(email: String) = sendPasswordResetUseCase(email)

    suspend fun signOut() = signOutUseCase()
}
