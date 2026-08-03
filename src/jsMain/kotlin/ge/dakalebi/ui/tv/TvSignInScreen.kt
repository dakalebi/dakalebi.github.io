package ge.dakalebi.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.di.session
import ge.dakalebi.di.toasts
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.ui.assetBase
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Email and password, once per device.
 *
 * **No Google button.** Google blocks OAuth in embedded WebViews and has since
 * 2023, and `signInWithRedirect` does not help because it is the same WebView. A
 * button that always fails is worse than no button, so this is the one screen where
 * the TV UI is not a subset of the web one but a different shape.
 *
 * Typing an email with a D-pad is genuinely unpleasant, which is why Firebase's
 * session persistence matters more here than anywhere: this screen should be seen
 * once per device and never again.
 *
 * Also absent: sign-up and password reset. Both need an inbox, and the account
 * already exists by the time a television is involved.
 */
@Composable
fun TvSignInScreen() {
    val session = session()
    val toasts = toasts()
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    /**
     * Whether a press would do anything.
     *
     * Named once and read twice — by [submit] as its guard and by the button as its
     * `aria-disabled` — so the two cannot drift. They had: the button reported only
     * `busy`, while the guard also refused a blank field, so a screen reader announced
     * an enabled control that did nothing on an empty form.
     */
    val canSubmit = !busy && email.isNotBlank() && password.isNotBlank()

    fun submit() {
        if (!canSubmit) return
        busy = true
        scope.launch {
            try {
                session.signIn(email.trim(), password)
            } catch (e: Throwable) {
                Log.e("auth", "tv sign-in failed", e)
                toasts.error(ErrorMessages.signIn(e))
            } finally {
                busy = false
            }
        }
    }

    Div({ classes("tv-signin") }) {
        Img(src = "${assetBase}logo.png", alt = S.seriesTitle) { classes("tv-signin-mark") }
        H1({ classes("tv-h") }) { Text(S.seriesTitle.caps) }
        Span({ classes("tv-eyebrow") }) { Text(S.signInEyebrow.caps) }

        Div({ classes("tv-signin-form"); focusGroup("signin", FocusAxis.Y) }) {
            Input(InputType.Email) {
                classes("tv-field")
                focusItem("email", entry = true)
                placeholder(S.emailPlaceholder)
                value(email)
                onInput { email = it.value }
            }
            Input(InputType.Password) {
                classes("tv-field")
                focusItem("password")
                placeholder(S.passwordPlaceholder)
                value(password)
                onInput { password = it.value }
            }
            Div({
                classes("tv-btn", "tv-btn-primary")
                focusItem("submit")
                // No accessible name: this control's own text already says "Sign in",
                // and an `aria-label` would replace it — which mattered here, because
                // the text changes to "Loading" while a sign-in is in flight and a
                // fixed label would keep announcing "Sign in" throughout.
                //
                // Disabled and busy by ARIA only. The control stays focusable either
                // way, because the ring cannot vanish from under the viewer mid-screen.
                actsAsButton(disabled = !canSubmit, busy = busy)
                onClick { submit() }
            }) { Text((if (busy) S.loading else S.signIn).caps) }
        }
    }
}
