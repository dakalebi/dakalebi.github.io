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
import kotlinx.coroutines.launch
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

@Composable
fun LoginScreen() {
    val session = session()
    val toasts = toasts()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signUpMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

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

        Div({ classes("login-card") }) {
            Div({ classes("login-head") }) {
                Img(src = "${assetBase}logo.png", alt = S.seriesTitle) { classes("login-mark") }
                Div({ classes("eyebrow") }) { Text(S.signInEyebrow.caps) }
                H1({ classes("login-h") }) { Text(S.seriesTitle.caps) }
            }

            Button({
                classes("btn", "btn-ghost")
                style { property("justify-content", "center") }
                if (busy) disabled()
                onClick { run({ session.signInWithGoogle() }) }
            }) { Text(S.signInWithGoogle.caps) }

            Div({ classes("divider") }) { Text(S.or) }

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
                Input(InputType.Email) {
                    classes("field")
                    name("email")
                    placeholder(S.emailPlaceholder)
                    required()
                    value(email)
                    onInput { email = it.value }
                }
                Input(InputType.Password) {
                    classes("field")
                    name("password")
                    placeholder(S.passwordPlaceholder)
                    required()
                    minLength(6)
                    value(password)
                    onInput { password = it.value }
                }
                Button({
                    classes("btn", "btn-primary")
                    style { property("justify-content", "center") }
                    if (busy) disabled()
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
