package ge.dakalebi.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.model.Viewer
import ge.dakalebi.domain.repository.GuestModeRepository
import ge.dakalebi.domain.usecase.CheckAdminRights
import ge.dakalebi.domain.usecase.ObserveAccount
import ge.dakalebi.domain.usecase.SendPasswordReset
import ge.dakalebi.domain.usecase.SignInWithEmail
import ge.dakalebi.domain.usecase.SignOut
import ge.dakalebi.domain.usecase.SignUpWithEmail

/** Who is signed in, or browsing as a guest, as Compose state. */
class SessionStore(
    private val observeAccount: ObserveAccount,
    private val checkAdminRights: CheckAdminRights,
    private val signInWithEmail: SignInWithEmail,
    private val signUpWithEmail: SignUpWithEmail,
    private val sendPasswordResetUseCase: SendPasswordReset,
    private val signOutUseCase: SignOut,
    private val guestMode: GuestModeRepository,
) {
    var account: Account? by mutableStateOf(null)
        private set

    /** True until the provider has reported the restored session, if any. */
    var loading: Boolean by mutableStateOf(true)
        private set

    /** This device's guest choice. Read once here so the first frame already knows it. */
    private var guestChosen: Boolean by mutableStateOf(guestMode.isGuest())

    /**
     * Who is using the app, or null when the sign-in screen is due.
     *
     * An account always wins over the guest choice, so a device that is somehow
     * both is treated as signed in and its progress goes to the account.
     */
    val viewer: Viewer?
        get() = account?.let { Viewer.Member(it) } ?: Viewer.Guest.takeIf { guestChosen }

    val isGuest: Boolean get() = viewer == Viewer.Guest

    /** The key progress is filed under: the account's uid, or the guest's device key. */
    val viewerId: String? get() = viewer?.id

    /** The signed-in account's uid. Null for a guest, who has no account to sync. */
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
            // Signing in ends guest mode, so a later sign-out returns to the
            // sign-in screen instead of silently dropping back into guest mode.
            if (next != null) chooseGuest(false)
            loading = false
        }
    }

    /** Enters the app without an account. */
    fun continueAsGuest() = chooseGuest(true)

    /** Leaves guest mode for the sign-in screen. The guest's progress stays on the device. */
    fun leaveGuestMode() = chooseGuest(false)

    /**
     * Signs out and continues as a guest, in one step.
     *
     * The choice is made before signing out, so the viewer goes straight from the
     * account to the guest without the sign-in screen flashing in between. If the
     * sign-out fails the account is still signed in, so the choice is taken back.
     */
    suspend fun switchToGuest() {
        chooseGuest(true)
        try {
            signOutUseCase()
        } catch (e: Throwable) {
            chooseGuest(false)
            throw e
        }
    }

    private fun chooseGuest(value: Boolean) {
        guestChosen = value
        guestMode.setGuest(value)
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
