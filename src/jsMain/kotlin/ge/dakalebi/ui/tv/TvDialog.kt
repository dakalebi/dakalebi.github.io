package ge.dakalebi.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.SpatialNav
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import ge.dakalebi.ui.tv.input.TvLayer
import io.github.bchmsl.keel.components.ButtonVariant
import io.github.bchmsl.keel.components.SurfacePadding
import io.github.bchmsl.keel.dom.buttonClasses
import io.github.bchmsl.keel.dom.classNames
import io.github.bchmsl.keel.dom.scrimClasses
import io.github.bchmsl.keel.dom.surfaceClasses
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/** Holds the dialog's own element so its input layer can scope focus to it. */
private class TvDialogRefs {
    var container: HTMLElement? = null
}

/**
 * A modal question on the television: a scrim, a title, one line of body, and two
 * choices.
 *
 * Unlike the web's [ge.dakalebi.ui.ConfirmDialog] this is a real focus trap, because a
 * remote has nowhere else to click. It pushes its own input layer whose root is the
 * dialog, so the spatial engine cannot see anything behind it, and an unhandled Back
 * dismisses it — the same shape the player uses one level down.
 *
 * The ring lands on **Cancel**, the safe choice, so a stray OK closes the dialog rather
 * than firing the action it guards. That is the whole reason a destructive or
 * hard-to-undo action gets one of these: signing out costs a re-login to reverse, so it
 * is worth a deliberate second press.
 */
@Composable
fun TvConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val input = LocalTvInput.current
    val refs = remember { TvDialogRefs() }

    DisposableEffect(Unit) {
        val layer = input.push(
            TvLayer(
                key = "tv-dialog",
                root = { refs.container },
                // An overlay, so an unhandled Back removes it — that press is the dismiss.
                onBack = { onDismiss(); true },
            ),
        )
        // A turn of the event loop so the buttons exist to focus. `setTimeout`, not the
        // frame clock, for the reason the rest of this layer bypasses Compose.
        val land = window.setTimeout({ refs.container?.let { SpatialNav.ensureFocused(it) } }, 0)
        onDispose {
            window.clearTimeout(land)
            layer.dismiss()
        }
    }

    // keel's scrim and keel's surface box, by class name. Not keel's `Dialog`, which
    // composes `DismissOnEscape` and so installs a second global `keydown` listener -
    // this shell has exactly one by design, and two independent listeners for the same
    // event have no ordering guarantee between them. Back is handled by the input layer
    // pushed above instead.
    Div({ classNames(scrimClasses(blurred = false)) })
    Div({
        classNames("tv-dialog", surfaceClasses(SurfacePadding.None))
        ref { element -> refs.container = element; onDispose { refs.container = null } }
    }) {
        H2({ classes("tv-dialog-title") }) { Text(title) }
        P({ classes("tv-dialog-body") }) { Text(body) }
        Div({ classes("tv-dialog-row"); focusGroup("dialog", FocusAxis.X) }) {
            // No `aria-label`: the visible text already names each action, and adding one
            // would only shadow it. See [actsAsButton].
            Div({
                classNames("tv-btn", buttonClasses(ButtonVariant.Outline))
                focusItem("dialog-cancel", entry = true)
                actsAsButton()
                onClick { onDismiss() }
            }) { Text(S.cancel.caps) }

            Div({
                classNames("tv-btn", buttonClasses(ButtonVariant.Default))
                focusItem("dialog-confirm")
                actsAsButton()
                onClick { onConfirm() }
            }) { Text(confirmLabel) }
        }
    }
}
