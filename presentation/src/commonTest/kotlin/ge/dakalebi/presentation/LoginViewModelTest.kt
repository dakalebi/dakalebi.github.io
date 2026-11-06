package ge.dakalebi.presentation

import ge.dakalebi.i18n.S
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sign-in form: what it sends, what it says, and that it sends one thing
 * at a time.
 */
class LoginViewModelTest {

    private val email = "a@example.invalid"

    /** A made-up value. Never a real account's password. */
    private val password = "not-a-real-pw"

    private class Setup(
        val login: LoginViewModel,
        val session: SessionViewModel,
        val accounts: ScriptedAccountRepository,
        val toasts: ToastStore,
    ) {
        val ui get() = login.state.value
        val lastToast get() = toasts.items.lastOrNull()
    }

    private fun TestScope.setup(): Setup {
        val accounts = ScriptedAccountRepository()
        val session = sessionViewModel(accounts = accounts)
        // The toasts' own timers on virtual time, so nothing outlives the test.
        val toasts = ToastStore(this)
        val login = LoginViewModel(session = session, toasts = toasts, scope = CoroutineScope(Dispatchers.Unconfined))
        return Setup(login, session, accounts, toasts)
    }

    private fun Setup.fill() {
        login.setEmail(email)
        login.setPassword(password)
    }

    @Test
    fun typing_fills_the_form() = runTest {
        val setup = setup()

        setup.fill()

        assertEquals(email, setup.ui.email)
        assertEquals(password, setup.ui.password)
        assertFalse(setup.ui.signUpMode)
    }

    @Test
    fun the_form_signs_in_without_a_message() = runTest {
        val setup = setup()
        setup.fill()

        setup.login.submit()

        assertEquals(listOf("signIn $email $password"), setup.accounts.requests)
        assertTrue(setup.toasts.items.isEmpty())
        assertFalse(setup.ui.busy)
    }

    @Test
    fun in_sign_up_mode_the_form_creates_the_account_and_says_so() = runTest {
        val setup = setup()
        setup.fill()

        setup.login.toggleMode()
        setup.login.submit()

        assertEquals(listOf("signUp $email $password"), setup.accounts.requests)
        assertEquals(S.accountCreated, setup.lastToast?.message)
        assertEquals(ToastKind.Ok, setup.lastToast?.kind)
    }

    @Test
    fun toggling_twice_is_back_to_signing_in() = runTest {
        val setup = setup()

        setup.login.toggleMode()
        assertTrue(setup.ui.signUpMode)
        setup.login.toggleMode()

        assertFalse(setup.ui.signUpMode)
    }

    @Test
    fun a_refused_sign_in_says_why_and_frees_the_form() = runTest {
        val setup = setup()
        val refusal = IllegalStateException("auth/invalid-credential")
        setup.accounts.authFailure = refusal
        setup.fill()

        setup.login.submit()

        assertEquals(ErrorMessages.signIn(refusal), setup.lastToast?.message)
        assertEquals(ToastKind.Error, setup.lastToast?.kind)
        assertFalse(setup.ui.busy)
    }

    @Test
    fun one_request_at_a_time() = runTest {
        val setup = setup()
        val gate = CompletableDeferred<Unit>()
        setup.accounts.authGate = gate
        setup.fill()

        setup.login.submit()
        assertTrue(setup.ui.busy)
        setup.login.submit()
        setup.login.sendPasswordReset()

        gate.complete(Unit)

        assertEquals(listOf("signIn $email $password"), setup.accounts.requests)
        assertFalse(setup.ui.busy)
    }

    @Test
    fun a_reset_needs_the_email_first() = runTest {
        val setup = setup()
        setup.login.setEmail("   ")

        setup.login.sendPasswordReset()

        assertTrue(setup.accounts.requests.isEmpty())
        assertEquals(S.enterEmailFirst, setup.lastToast?.message)
        assertEquals(ToastKind.Error, setup.lastToast?.kind)
    }

    @Test
    fun a_reset_sends_the_link_and_says_so() = runTest {
        val setup = setup()
        setup.login.setEmail(email)

        setup.login.sendPasswordReset()

        assertEquals(listOf("reset $email"), setup.accounts.requests)
        assertEquals(S.resetLinkSent, setup.lastToast?.message)
        assertEquals(ToastKind.Ok, setup.lastToast?.kind)
    }

    @Test
    fun continuing_as_a_guest_makes_a_guest() = runTest {
        val setup = setup()

        setup.login.continueAsGuest()

        assertTrue(setup.session.state.value.isGuest)
    }

    /** The button is off while a request is out. The ViewModel holds to that too. */
    @Test
    fun the_guest_way_waits_for_a_request_out() = runTest {
        val setup = setup()
        val gate = CompletableDeferred<Unit>()
        setup.accounts.authGate = gate
        setup.fill()
        setup.login.submit()

        setup.login.continueAsGuest()
        gate.complete(Unit)

        assertFalse(setup.session.state.value.isGuest)
    }
}
