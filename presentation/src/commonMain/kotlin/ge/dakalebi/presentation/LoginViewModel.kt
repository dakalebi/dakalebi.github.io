package ge.dakalebi.presentation

import ge.dakalebi.core.Log
import ge.dakalebi.i18n.S
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    /** Creating an account rather than signing in to one. */
    val signUpMode: Boolean = false,
    /** A request is out. The form's buttons are off until it answers. */
    val busy: Boolean = false,
)

/**
 * The sign-in form, for every shell: the web, the television and driving mode
 * all show the same one.
 *
 * One per visit to the screen, as the remembered state it replaces was, so
 * nothing typed outlives the form. Its requests run in the app's [scope], so
 * the answer still arrives when signing in has already moved the app on.
 *
 * It says what happened itself, through [toasts]: every shell words it the same.
 * Signing in needs no message, because the app leaves the form.
 */
class LoginViewModel(
    private val session: SessionViewModel,
    private val toasts: ToastStore,
    private val scope: CoroutineScope,
) : ViewModel<LoginUiState>(LoginUiState()) {

    fun setEmail(value: String) = setState { copy(email = value) }

    fun setPassword(value: String) = setState { copy(password = value) }

    /** Switches between signing in and creating an account. */
    fun toggleMode() = setState { copy(signUpMode = !signUpMode) }

    /** Signs in with the form, or creates the account in sign-up mode. */
    fun submit() {
        val form = state.value
        request({
            if (form.signUpMode) session.signUp(form.email, form.password)
            else session.signIn(form.email, form.password)
        }) {
            if (form.signUpMode) toasts.ok(S.accountCreated)
        }
    }

    /** Sends a reset link to the email in the form, which has to be filled in first. */
    fun sendPasswordReset() {
        val email = state.value.email
        if (email.isBlank()) {
            toasts.error(S.enterEmailFirst)
            return
        }
        request({ session.resetPassword(email) }) { toasts.ok(S.resetLinkSent) }
    }

    fun continueAsGuest() {
        if (state.value.busy) return
        session.continueAsGuest()
    }

    private fun request(send: suspend () -> Unit, onDone: () -> Unit) {
        if (state.value.busy) return
        setState { copy(busy = true) }
        scope.launch {
            try {
                send()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Log the raw Firebase error alongside the friendly text: the
                // mapped message hides the code, and auth failures are the
                // hardest thing to diagnose remotely.
                Log.e("auth", "sign-in failed", e)
                toasts.error(ErrorMessages.signIn(e))
            } finally {
                setState { copy(busy = false) }
            }
        }
    }
}

/** Makes the sign-in form's ViewModel, one per visit to the screen. */
fun interface LoginViewModelFactory {
    fun create(): LoginViewModel
}
