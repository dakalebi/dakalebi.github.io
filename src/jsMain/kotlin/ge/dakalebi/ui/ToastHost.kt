package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import ge.dakalebi.di.toasts
import ge.dakalebi.presentation.ToastKind
import io.github.bchmsl.keel.components.Toast as KeelToast
import io.github.bchmsl.keel.components.ToastHost as KeelToastHost
import io.github.bchmsl.keel.components.ToastPlacement
import io.github.bchmsl.keel.components.ToastTone

/**
 * The one toast host both shells render.
 *
 * keel draws the strip and the notices; the store keeps deciding what is in the list
 * and for how long, which is right - that is application state with a timer in it,
 * and no two apps want the same duration.
 *
 * The edge is the only thing that differs between the shells. A page reads its
 * notices at the top, next to the chrome they belong to; a television reads them at
 * the bottom, out of the way of a picture that fills the panel.
 */
@Composable
fun ToastHost() {
    val toasts = toasts()
    KeelToastHost(
        toasts = toasts.items.map { toast ->
            KeelToast(
                // The store's own id, so the framework matches notices by identity
                // across recompositions rather than by position. Without it,
                // dismissing the first of three re-labels the other two.
                id = toast.id.toString(),
                message = toast.message,
                tone = when (toast.kind) {
                    ToastKind.Ok -> ToastTone.Success
                    ToastKind.Error -> ToastTone.Error
                    ToastKind.Plain -> ToastTone.Neutral
                },
            )
        },
        placement = if (shell == Shell.Tv) ToastPlacement.Bottom else ToastPlacement.Top,
    )
}
