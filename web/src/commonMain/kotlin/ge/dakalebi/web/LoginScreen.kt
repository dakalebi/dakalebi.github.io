package ge.dakalebi.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.repository.AccountRepository
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import kotlinx.coroutines.launch

/**
 * The first real 2.0 screen: email + password sign-in against Firebase, plus a
 * signed-in view.
 *
 * It is deliberately first. It exercises the two things the assessment flagged as most
 * likely to bite on a canvas web app — real Firebase auth over typed wasm interop, and
 * text input / IME on a canvas (there is no DOM `<input>`). Both are cheaper to learn
 * here, on one screen, than three screens deep.
 *
 * Takes an [AccountRepository] rather than reaching for Firebase itself, so this
 * composable stays in `commonMain` and can later drive a native Android TV build.
 */
@Composable
fun LoginScreen(account: AccountRepository) {
    var current by remember { mutableStateOf<Account?>(null) }
    var observed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        account.observe { user ->
            current = user
            observed = true
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                !observed -> CircularProgressIndicator()
                current != null -> SignedIn(account, current!!)
                else -> SignInForm(account)
            }
        }
    }
}

@Composable
private fun SignedIn(account: AccountRepository, user: Account) {
    val scope = rememberCoroutineScope()
    Text(S.appName + " 2.0", style = MaterialTheme.typography.headlineMedium)
    Text(user.email ?: user.uid, style = MaterialTheme.typography.bodyLarge)
    TextButton(onClick = { scope.launch { account.signOut() } }) {
        Text(S.signOut.caps)
    }
}

@Composable
private fun SignInForm(account: AccountRepository) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { account.signIn(email.trim(), password) }
                .onFailure { error = S.errWrongCredentials }
            busy = false
        }
    }

    Text(S.appName + " 2.0", style = MaterialTheme.typography.headlineMedium)

    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(S.emailPlaceholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = Modifier.width(320.dp).padding(top = 16.dp),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(S.passwordPlaceholder) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.width(320.dp).padding(top = 8.dp),
    )

    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    }

    Button(
        onClick = { submit() },
        enabled = !busy && email.isNotBlank() && password.isNotBlank(),
        modifier = Modifier.width(320.dp).padding(top = 16.dp),
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.width(18.dp)) else Text(S.signIn.caps)
    }
}
