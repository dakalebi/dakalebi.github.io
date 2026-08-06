package ge.dakalebi.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.usecase.CheckAdminRights
import ge.dakalebi.domain.usecase.ObserveAccount
import ge.dakalebi.domain.usecase.SendPasswordReset
import ge.dakalebi.domain.usecase.SignInWithEmail
import ge.dakalebi.domain.usecase.SignOut
import ge.dakalebi.domain.usecase.SignUpWithEmail

/** Who is signed in, as Compose state. */
class SessionStore(
    private val observeAccount: ObserveAccount,
    private val checkAdminRights: CheckAdminRights,
    private val signInWithEmail: SignInWithEmail,
    private val signUpWithEmail: SignUpWithEmail,
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
     * Whether to offer the catalog refresh.
     *
     * State rather than a computed property now that answering costs a
     * document read. It starts false and is corrected by
     * [refreshAdminRights]: showing the control a moment late is better than
     * showing it to everyone until the answer arrives.
     */
    var isAdmin: Boolean by mutableStateOf(false)
        private set

    fun start() {
        observeAccount { next ->
            account = next
            loading = false
        }
    }

    /** Re-checks the roster. Called whenever the signed-in account changes. */
    suspend fun refreshAdminRights() {
        isAdmin = checkAdminRights(account)
    }

    suspend fun signIn(email: String, password: String) = signInWithEmail(email, password)

    suspend fun signUp(email: String, password: String) = signUpWithEmail(email, password)


    suspend fun resetPassword(email: String) = sendPasswordResetUseCase(email)

    suspend fun signOut() = signOutUseCase()
}
