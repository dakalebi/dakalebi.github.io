package ge.dakalebi.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
 * Phase 0 is deliberately a hello-world: its only job is to prove the toolchain builds
 * and that the app renders on the canvas at `dakalebi.github.io/preview`. Real screens
 * arrive once the two blockers (Firebase-on-wasm, video overlay) are de-risked.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        App()
    }
}

@Composable
private fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("დაქალები 2.0", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Compose Multiplatform preview",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
