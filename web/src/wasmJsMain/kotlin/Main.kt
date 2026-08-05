package ge.dakalebi.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import ge.dakalebi.web.firebase.WasmAccountRepository
import kotlinx.browser.document

/**
 * Entry point for the Compose Multiplatform web app (wasmJs / canvas).
 *
 * The first real screen: Firebase email + password sign-in. Wiring the wasm
 * [WasmAccountRepository] in here keeps [LoginScreen] itself in `commonMain`, so it can
 * later drive a native build unchanged.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val account = WasmAccountRepository()
    ComposeViewport(document.body!!) {
        AppTheme {
            LoginScreen(account)
        }
    }
}
