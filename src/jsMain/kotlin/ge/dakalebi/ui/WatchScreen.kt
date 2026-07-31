package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

@Composable
fun WatchScreen(episodeId: String) {
    Div({ classes("center-note") }) { Text("watch $episodeId") }
}
