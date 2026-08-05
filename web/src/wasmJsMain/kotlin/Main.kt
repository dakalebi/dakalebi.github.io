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
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
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
            // Real strings from :shared, proving the domain/i18n layer runs on wasm.
            Text("${S.appName} 2.0", style = MaterialTheme.typography.headlineMedium)
            Text(S.seriesTitle, style = MaterialTheme.typography.titleMedium)
            Text(S.seasonAndEpisode(2, 4).caps, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
