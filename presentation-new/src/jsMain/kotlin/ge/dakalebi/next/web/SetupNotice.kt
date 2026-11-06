package ge.dakalebi.next.web

import androidx.compose.runtime.Composable
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Drawn instead of the app when `FirebaseConfig` is still empty, so a fresh checkout says
 * what is missing rather than throwing on Firebase's init. It has no graph to draw from,
 * so it is plain markup on the sign-in screen's card. Only a developer ever sees it: the
 * published site always has the config.
 */
@Composable
fun SetupNotice() {
    Main({ classes("signin") }) {
        Div({ classes("signin-card") }) {
            Div({ classes("signin-head") }) {
                P({ classes("signin-eyebrow") }) { Text(S.setupEyebrow.caps) }
                H1({ classes("signin-title") }) { Text(S.setupTitle.caps) }
            }
            P({ classes("signin-note") }) { Text(S.setupBody) }
            P({ classes("signin-note", "signin-path") }) { Text(CONFIG_PATH) }
        }
    }
}

private const val CONFIG_PATH = "data/src/jsMain/kotlin/ge/dakalebi/data/firebase/FirebaseConfig.kt"
