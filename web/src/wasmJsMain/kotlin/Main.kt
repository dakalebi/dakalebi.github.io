package ge.dakalebi.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

/**
 * Entry point for the Compose Multiplatform web app (wasmJs / canvas).
 *
 * Still a hello-world, but now under [AppTheme] (bundled Georgian font, dark scheme) and
 * on a real [Surface], so Georgian renders and the background is the app's dark, not the
 * canvas's white. Real screens replace [App] as Phase 1 proceeds.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        AppTheme {
            App()
        }
    }
}

@Composable
private fun App() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("დაქალები 2.0", style = MaterialTheme.typography.headlineMedium)
            Text("Compose Multiplatform preview", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
