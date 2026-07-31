package ge.dakalebi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable

fun main() {
    renderComposable(rootElementId = "root") {
        var clicks by remember { mutableStateOf(0) }
        Div(attrs = { classes("scaffold-check") }) {
            Text("ჩემი ცოლის დაქალები — scaffold OK ($clicks)")
            Button(attrs = { onClick { clicks++ } }) { Text("დააჭირე") }
        }
    }
}
