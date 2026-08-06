package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.di.session
import ge.dakalebi.di.toasts
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.minLength
import org.jetbrains.compose.web.attributes.name
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.required
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement

/**
 * The one sign-in screen, for the browser and for the television.
 *
 * Both front ends use this: there is only one way into the app, so there is one screen for it. What
 * the TV needs on top of the web version is not a different design but a different *input* — a
 * remote has a D-pad and no pointer — and that is added by the `dpad*` helpers below, which write
 * attributes on a TV page and nothing at all on a web one.
 */
@Composable
fun LoginScreen() {
    val session = session()
    val toasts = toasts()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signUpMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val emailField = remember { FieldRef() }
    val passwordField = remember { FieldRef() }

    fun run(block: suspend () -> Unit, onOk: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                onOk()
            } catch (e: Throwable) {
                // Log the raw Firebase error alongside the friendly text: the
                // mapped message hides the code, and auth failures are the
                // hardest thing to diagnose remotely.
                Log.e("auth", "sign-in failed", e)
                toasts.error(ErrorMessages.signIn(e))
            } finally {
                busy = false
            }
        }
    }

    Div({ classes("login-wrap") }) {
        Div({ classes("login-bg") })

        Div({ classes("login-card"); dpadGroup("signin") }) {
            Div({ classes("login-head") }) {
                Img(src = "${assetBase}logo.png", alt = S.seriesTitle) { classes("login-mark") }
                Div({ classes("eyebrow") }) { Text(S.signInEyebrow.caps) }
                H1({ classes("login-h") }) { Text(S.seriesTitle.caps) }
            }

            Form(attrs = {
                classes("login-form")
                onSubmit { event ->
                    event.preventDefault()
                    val mode = signUpMode
                    run({
                        if (mode) session.signUp(email, password)
                        else session.signIn(email, password)
                    }) {
                        if (mode) toasts.ok(S.accountCreated)
                    }
                }
            }) {
                Div({ classes("auth-field"); dpadField("email", emailField, entry = true) }) {
                    Input(InputType.Email) {
                        classes("field")
                        name("email")
                        placeholder(S.emailPlaceholder)
                        required()
                        value(email)
                        onInput { email = it.value }
                        dpadEntry(emailField)
                    }
                }
                Div({ classes("auth-field"); dpadField("password", passwordField) }) {
                    Input(InputType.Password) {
                        classes("field")
                        name("password")
                        placeholder(S.passwordPlaceholder)
                        required()
                        minLength(6)
                        value(password)
                        onInput { password = it.value }
                        dpadEntry(passwordField)
                    }
                }
                Button({
                    classes("btn", "btn-primary")
                    style { property("justify-content", "center") }
                    if (busy) disabled()
                    dpadItem("submit")
                }) {
                    Text((if (signUpMode) S.signUp else S.signIn).caps)
                }
            }

            Div({
                style {
                    property("display", "flex")
                    property("justify-content", "space-between")
                    property("gap", "10px")
                    property("flex-wrap", "wrap")
                }
            }) {
                Button({
                    classes("btn", "btn-quiet")
                    style { property("padding", "0") }
                    dpadItem("mode")
                    onClick { signUpMode = !signUpMode }
                }) {
                    Text(
                        (if (signUpMode) S.promptSignIn else S.promptSignUp).caps,
                    )
                }

                if (!signUpMode) {
                    Button({
                        classes("btn", "btn-quiet")
                        style { property("padding", "0") }
                        dpadItem("reset")
                        onClick {
                            if (email.isBlank()) {
                                toasts.error(S.enterEmailFirst)
                            } else {
                                run({ session.resetPassword(email) }) {
                                    toasts.ok(S.resetLinkSent)
                                }
                            }
                        }
                    }) { Span { Text(S.forgotPassword.caps) } }
                }
            }
        }
    }
}

/** Holds the raw `<input>` so OK can focus it without threading a ref through Compose. */
private class FieldRef {
    var input: HTMLInputElement? = null
}

/**
 * The remote's stops on this screen, written only when this page is the TV one.
 *
 * Every one of these is a no-op on the web, and that is the point: the screen is shared, so the
 * alternative is either two screens or a web form that has quietly grown a television's attributes.
 * The gate is real rather than cosmetic — [focusItem] sets `tabindex="-1"`, which on the web page
 * would drop the sign-in form out of the tab order altogether.
 */
private fun <T : Element> AttrsScope<T>.dpadGroup(key: String) {
    // Grid rather than Y: the card is a vertical stack, but the two links at the foot of it sit
    // side by side, and only a grid group works both axes out from the geometry.
    if (shell == Shell.Tv) focusGroup(key, FocusAxis.Grid)
}

private fun <T : Element> AttrsScope<T>.dpadItem(key: String) {
    if (shell == Shell.Tv) focusItem(key)
}

/**
 * A text field's wrapper: highlighted by the D-pad, edited only on OK.
 *
 * A web `<input>` collapses two states a remote has to keep apart — being *highlighted* and being
 * *edited*. The moment the engine focuses a raw input the browser is in edit mode: the arrows move
 * the text cursor instead of leaving the field, and on an Android TV the soft keyboard is already
 * up. There is no state in which the field is merely highlighted and the arrows still navigate, so
 * the screen becomes a trap — land on the email box and the password and the button are both
 * unreachable.
 *
 * So the ring goes on the wrapper. The engine focuses the wrapper (highlighted; arrows navigate
 * past it), OK clicks it and that focuses the real input (edit mode, which is what raises the IME),
 * and Back blurs it again — the last handled by `TvInput`, which reads a focused input inside a
 * `data-tv-item` wrapper as "leave edit mode" rather than "leave the screen".
 */
private fun <T : Element> AttrsScope<T>.dpadField(
    key: String,
    field: FieldRef,
    entry: Boolean = false,
) {
    if (shell != Shell.Tv) return
    focusItem(key, entry = entry)
    onClick { field.input?.focus() }
}

/**
 * The input inside a [dpadField]: reachable by OK, skipped by everything else.
 *
 * `tabindex="-1"` only on a television, where the wrapper is the stop. On the web this is an
 * ordinary field and has to stay in the tab order.
 */
private fun AttrsScope<HTMLInputElement>.dpadEntry(field: FieldRef) {
    if (shell == Shell.Tv) attr("tabindex", "-1")
    ref { element ->
        field.input = element
        onDispose { field.input = null }
    }
}
