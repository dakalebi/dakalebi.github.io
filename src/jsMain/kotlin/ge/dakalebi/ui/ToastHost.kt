package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import ge.dakalebi.app.ToastKind
import ge.dakalebi.app.Toasts
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

@Composable
fun ToastHost() {
    Div({ classes("toasts") }) {
        Toasts.items.forEach { toast ->
            Div({
                classNames(
                    "toast",
                    when (toast.kind) {
                        ToastKind.Ok -> "ok"
                        ToastKind.Error -> "err"
                        ToastKind.Plain -> null
                    },
                )
            }) { Text(toast.message) }
        }
    }
}
