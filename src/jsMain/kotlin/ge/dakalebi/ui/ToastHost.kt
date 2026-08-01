package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import ge.dakalebi.di.toasts
import ge.dakalebi.presentation.ToastKind
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

@Composable
fun ToastHost() {
    val toasts = toasts()
    Div({ classes("toasts") }) {
        toasts.items.forEach { toast ->
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
