package ge.dakalebi.next.tv

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
import ge.dakalebi.next.tv.focus.FocusAxis
import ge.dakalebi.next.tv.focus.focusGroup
import ge.dakalebi.next.tv.focus.focusItem
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * The way in on TV: the mark and the name on the left, the form on the right.
 *
 * The same form and requests as the web's ([ge.dakalebi.presentation.LoginViewModel]).
 * A field is one D-pad stop: OK starts typing on the TV's keyboard, Back stops. The ring
 * starts on the email field; the guest choice is one press down from the form.
 */
@Composable
internal fun TvSignIn() {
    val login = rememberLoginViewModel()
    val ui by login.state.collectAsState()

    Main({ classes("tv-signin") }) {
        Div({ classes("tv-signin-brand") }) {
            Img(src = "${assetBase}logo.png", alt = S.seriesTitle) { classes("tv-signin-mark") }
            P({ classes("tv-signin-eyebrow") }) { Text(S.signInEyebrow.caps) }
            H1({ classes("tv-signin-title") }) { Text(S.seriesTitle.caps) }
        }

        Div({ classes("tv-signin-card") }) {
            Form(attrs = {
                classes("tv-signin-form")
                attr("novalidate", "")
                focusGroup("signin-form", FocusAxis.Y)
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
                    attrs = { focusItem("email", entry = true) },
                )
                TextField(
                    label = S.passwordPlaceholder,
                    value = ui.password,
                    onValueChange = login::setPassword,
                    kind = TextFieldKind.Password,
                    autocomplete = if (ui.signUpMode) "new-password" else "current-password",
                    attrs = { focusItem("password") },
                )
                Button(
                    label = if (ui.signUpMode) S.signUp else S.signIn,
                    variant = ButtonVariant.Primary,
                    busy = ui.busy,
                    submit = true,
                    attrs = { focusItem("submit") },
                )
            }

            // A column, not a row: in Georgian the two do not fit on one line, and a row
            // that wraps leaves the second link where no D-pad direction reaches it.
            Div({ classes("tv-signin-links"); focusGroup("signin-links", FocusAxis.Y) }) {
                Button(
                    label = if (ui.signUpMode) S.promptSignIn else S.promptSignUp,
                    onClick = login::toggleMode,
                    variant = ButtonVariant.Quiet,
                    attrs = { focusItem("toggle-mode") },
                )
                if (!ui.signUpMode) {
                    Button(
                        label = S.forgotPassword,
                        onClick = login::sendPasswordReset,
                        variant = ButtonVariant.Quiet,
                        disabled = ui.busy,
                        attrs = { focusItem("forgot") },
                    )
                }
            }

            Div({ classes("tv-signin-guest"); focusGroup("signin-guest", FocusAxis.Y) }) {
                Button(
                    label = S.continueAsGuest,
                    onClick = login::continueAsGuest,
                    disabled = ui.busy,
                    attrs = { focusItem("guest") },
                )
                P({ classes("tv-signin-note") }) { Text(S.guestProgressNote) }
            }
        }
    }
}
