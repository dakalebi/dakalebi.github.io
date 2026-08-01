package ge.dakalebi.domain.usecase

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.repository.AccountRepository
import ge.dakalebi.domain.repository.AdminRepository

class ObserveAccount(private val accounts: AccountRepository) {
    operator fun invoke(onChange: (Account?) -> Unit) = accounts.observe(onChange)
}

class SignInWithEmail(private val accounts: AccountRepository) {
    suspend operator fun invoke(email: String, password: String) =
        accounts.signIn(email.trim(), password)
}

class SignUpWithEmail(private val accounts: AccountRepository) {
    suspend operator fun invoke(email: String, password: String) =
        accounts.signUp(email.trim(), password)
}

class SignInWithGoogle(private val accounts: AccountRepository) {
    suspend operator fun invoke() = accounts.signInWithGoogle()
}

class SendPasswordReset(private val accounts: AccountRepository) {
    suspend operator fun invoke(email: String) = accounts.sendPasswordReset(email.trim())
}

class SignOut(private val accounts: AccountRepository) {
    suspend operator fun invoke() = accounts.signOut()
}

/**
 * Whether to offer the catalog refresh.
 *
 * Advisory only — the Firestore rules re-check this server-side on every
 * write, so a forged `true` here buys nothing. The lookup exists so nobody is
 * shown a button guaranteed to fail.
 */
class CheckAdminRights(private val admins: AdminRepository) {
    suspend operator fun invoke(account: Account?): Boolean =
        account != null && admins.isAdmin(account.uid)
}
