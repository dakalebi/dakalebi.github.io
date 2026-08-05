package ge.dakalebi.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ge.dakalebi.core.Log
import ge.dakalebi.di.session
import ge.dakalebi.di.toasts
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.web.ui.AppButton
import ge.dakalebi.web.ui.ButtonTone
import ge.dakalebi.web.ui.Eyebrow
import ge.dakalebi.web.ui.Tokens
import kotlinx.coroutines.launch

/**
 * Sign in, sign up, and password reset — the DOM app's login card, redrawn.
 *
 * Google sign-in is deliberately absent rather than broken: it needs a popup the wasm layer does
 * not wire yet, and an account can always be reached with the email and password it was created
 * with. The button would otherwise be a control that fails when pressed.
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

    fun run(block: suspend () -> Unit, onOk: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                onOk()
            } catch (e: Throwable) {
                // Log the raw Firebase error alongside the friendly text: the mapped message
                // hides the code, and auth failures are the hardest thing to diagnose remotely.
                Log.e("auth", "sign-in failed", e)
                toasts.error(ErrorMessages.signIn(e))
            } finally {
                busy = false
            }
        }
    }

    fun submit() {
        if (email.isBlank() || password.isBlank()) return
        val mode = signUpMode
        run({
            if (mode) session.signUp(email.trim(), password) else session.signIn(email.trim(), password)
        }) {
            if (mode) toasts.ok(S.accountCreated)
        }
    }

    Box(Modifier.fillMaxSize().background(Tokens.bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .padding(24.dp)
                .clip(Tokens.radius)
                .background(Tokens.elev)
                .border(1.dp, Tokens.line, Tokens.radius)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Eyebrow(S.signInEyebrow.caps)
            Spacer(Modifier.height(6.dp))
            Text(
                text = S.seriesTitle.caps,
                color = Tokens.tx,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(S.emailPlaceholder) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(S.passwordPlaceholder) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            AppButton(
                label = (if (signUpMode) S.signUp else S.signIn).caps,
                onClick = { submit() },
                tone = ButtonTone.Primary,
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )

            // Stacked rather than side by side: both labels are sentences in Georgian, and two
            // of them on one row overflow a card this narrow.
            Spacer(Modifier.height(10.dp))
            AppButton(
                label = (if (signUpMode) S.promptSignIn else S.promptSignUp).caps,
                onClick = { signUpMode = !signUpMode },
                tone = ButtonTone.Quiet,
            )
            if (!signUpMode) {
                AppButton(
                    label = S.forgotPassword.caps,
                    onClick = {
                        if (email.isBlank()) {
                            toasts.error(S.enterEmailFirst)
                        } else {
                            run({ session.resetPassword(email.trim()) }) {
                                toasts.ok(S.resetLinkSent)
                            }
                        }
                    },
                    tone = ButtonTone.Quiet,
                )
            }
        }
    }
}

/** The dark field treatment, so Material's default light-on-white outline never shows. */
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Tokens.tx,
    unfocusedTextColor = Tokens.tx,
    focusedContainerColor = Tokens.elev2,
    unfocusedContainerColor = Tokens.elev2,
    disabledContainerColor = Tokens.elev2,
    cursorColor = Tokens.red,
    focusedBorderColor = Tokens.lineStrong,
    unfocusedBorderColor = Tokens.line,
    focusedLabelColor = Tokens.txDim,
    unfocusedLabelColor = Tokens.mut,
)
