package ge.dakalebi.presentation

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.model.Viewer
import ge.dakalebi.domain.repository.GuestModeRepository
import ge.dakalebi.domain.usecase.CheckAdminRights
import ge.dakalebi.domain.usecase.ObserveAccount
import ge.dakalebi.domain.usecase.SendPasswordReset
import ge.dakalebi.domain.usecase.SignInWithEmail
import ge.dakalebi.domain.usecase.SignOut
import ge.dakalebi.domain.usecase.SignUpWithEmail
import ge.dakalebi.domain.usecase.SyncUserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Who is signed in, or browsing as a guest. */
data class SessionUiState(
    val account: Account? = null,
    /** True until the provider has reported the restored session, if any. */
    val loading: Boolean = true,
    /** This device's guest choice. */
    val guestChosen: Boolean = false,
    /**
     * Whether to offer the catalog refresh.
     *
     * Starts false and is corrected by [SessionViewModel.refreshAdminRights]:
     * showing the control a moment late is better than showing it to everyone
     * until the answer arrives.
     */
    val isAdmin: Boolean = false,
) {
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
}

/**
 * The session, and the app-wide work that follows it.
 *
 * The one root ViewModel all three shells share. Besides who is signed in, it
 * does what each shell root used to repeat for itself:
 *
 * - settings sync follows the account. A guest has no settings document, so for
 *   a guest the sync stops and settings stay on the device;
 * - the catalog is dropped when nobody is using the app.
 *
 * Both run in the app's scope the moment the state changes, not in a
 * composition, so they wait for no frame and do not depend on which shell is on
 * screen.
 *
 * Loading the catalog is not here. Each shell loads when its screens need it, and
 * [CatalogStore.ensureLoaded] does not join a load that is already running, so a
 * second caller here would fetch everything twice.
 */
class SessionViewModel(
    private val observeAccount: ObserveAccount,
    private val checkAdminRights: CheckAdminRights,
    private val signInWithEmail: SignInWithEmail,
    private val signUpWithEmail: SignUpWithEmail,
    private val sendPasswordResetUseCase: SendPasswordReset,
    private val signOutUseCase: SignOut,
    private val guestMode: GuestModeRepository,
    private val syncUserSettings: SyncUserSettings,
    private val catalog: CatalogStore,
    /** The app's own scope, which the account and the work that follows it run in. */
    private val scope: CoroutineScope,
) : ViewModel<SessionUiState>(
    // The guest choice is read once here, so the first frame already knows it.
    SessionUiState(guestChosen = guestMode.isGuest()),
) {
    fun start() {
        scope.follow(observeAccount()) { next ->
            // Signing in ends guest mode, so a later sign-out returns to the
            // sign-in screen instead of silently dropping back into guest mode.
            if (next != null) guestMode.setGuest(false)
            setState { copy(account = next, loading = false, guestChosen = guestChosen && next == null) }
        }

        // Keyed on the uid alone, so nothing else that changes restarts it.
        // Detaching on sign-out matters: left attached, the listener keeps
        // querying a document the signed-out client may no longer read.
        scope.follow(state.map { it.uid }.distinctUntilChanged()) { uid -> syncUserSettings(uid) }

        // Dropped rather than refetched: a refetch races the session teardown and
        // comes back as permission errors. Switching from an account to guest
        // mode never passes through nobody, so it keeps the episodes; the
        // guest's own load replaces the account's progress.
        scope.follow(state.map { it.viewerId }.distinctUntilChanged()) { viewerId ->
            if (viewerId == null) catalog.reset()
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
        setState { copy(guestChosen = value) }
        guestMode.setGuest(value)
    }

    /**
     * Re-checks the roster. Costs a document read, so only the shell that shows
     * admin controls calls it, and only when the account changes.
     */
    suspend fun refreshAdminRights() {
        val admin = checkAdminRights(state.value.account)
        setState { copy(isAdmin = admin) }
    }

    suspend fun signIn(email: String, password: String) = signInWithEmail(email, password)

    suspend fun signUp(email: String, password: String) = signUpWithEmail(email, password)

    suspend fun resetPassword(email: String) = sendPasswordResetUseCase(email)

    suspend fun signOut() = signOutUseCase()
}
