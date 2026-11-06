package ge.dakalebi.next.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ge.dakalebi.di.rememberLoginViewModel
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.assetBase
import ge.dakalebi.next.components.Button
import ge.dakalebi.next.components.ButtonVariant
import ge.dakalebi.next.components.TextField
import ge.dakalebi.next.components.TextFieldKind
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * The way in: an account's email and password, or a guest visit.
 *
 * Not drawn by the design pass, so it is built from the tokens and components: one
 * card, the amber button for the one main action, the guest choice under a line with
 * what a guest gives up. The form and its requests are [ge.dakalebi.presentation.LoginViewModel]'s,
 * the same as on the old page; it says what went wrong in a toast.
 */
@Composable
fun SignInScreen() {
    val login = rememberLoginViewModel()
    val ui by login.state.collectAsState()

    Main({ classes("signin") }) {
        Div({ classes("signin-card") }) {
            Div({ classes("signin-head") }) {
                // The mark is lettering, so it is the name; `alt` carries it.
                Img(src = "${assetBase}logo.png", alt = S.seriesTitle) { classes("signin-mark") }
                P({ classes("signin-eyebrow") }) { Text(S.signInEyebrow.caps) }
                H1({ classes("signin-title") }) { Text(S.seriesTitle.caps) }
            }

            Form(attrs = {
                classes("signin-form")
                // The browser's own checks would show its own bubbles in its own language;
                // the provider's answer is worded for this app and arrives as a toast.
                attr("novalidate", "")
                onSubmit { event ->
                    event.preventDefault()
                    login.submit()
                }
            }) {
                TextField(
                    label = S.emailPlaceholder,
                    value = ui.email,
                    onValueChange = login::setEmail,
                    kind = TextFieldKind.Email,
                    autocomplete = "username",
                )
                TextField(
                    label = S.passwordPlaceholder,
                    value = ui.password,
                    onValueChange = login::setPassword,
                    kind = TextFieldKind.Password,
                    autocomplete = if (ui.signUpMode) "new-password" else "current-password",
                )
                Button(
                    label = if (ui.signUpMode) S.signUp else S.signIn,
                    variant = ButtonVariant.Primary,
                    busy = ui.busy,
                    submit = true,
                    attrs = { classes("signin-submit") },
                )
            }

            Div({ classes("signin-links") }) {
                Button(
                    label = if (ui.signUpMode) S.promptSignIn else S.promptSignUp,
                    onClick = login::toggleMode,
                    variant = ButtonVariant.Quiet,
                )
                if (!ui.signUpMode) {
                    Button(
                        label = S.forgotPassword,
                        onClick = login::sendPasswordReset,
                        variant = ButtonVariant.Quiet,
                        disabled = ui.busy,
                    )
                }
            }

            Div({ classes("signin-guest") }) {
                Button(
                    label = S.continueAsGuest,
                    onClick = login::continueAsGuest,
                    disabled = ui.busy,
                    attrs = { classes("signin-guest-button") },
                )
                P({ classes("signin-note") }) { Text(S.guestProgressNote) }
            }
        }
    }
}
