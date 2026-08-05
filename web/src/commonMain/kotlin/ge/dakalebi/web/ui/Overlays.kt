package ge.dakalebi.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.Toast
import ge.dakalebi.presentation.ToastKind

/**
 * A full-screen dim behind a dialog or a sheet.
 *
 * Consumes its own clicks, so anything underneath is unreachable while it is up — the modal
 * behaviour a DOM overlay gets from stacking contexts has to be stated explicitly on a canvas.
 */
@Composable
fun Scrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(
                // No ripple and no indication: this is a dismiss target, not a control.
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Wrapped so a click on the panel itself does not fall through to the scrim.
        Box(
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        ) {
            content()
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Scrim(onDismiss) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .clip(Tokens.radius)
                .background(Tokens.elev)
                .border(1.dp, Tokens.lineStrong, Tokens.radius)
                .padding(20.dp),
        ) {
            Text(title, color = Tokens.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.padding(top = 8.dp))
            Text(body, color = Tokens.txDim, fontSize = 13.sp)
            Spacer(Modifier.padding(top = 18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppButton(S.cancel.caps, onDismiss, ButtonTone.Quiet)
                Spacer(Modifier.padding(start = 8.dp))
                AppButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    tone = if (destructive) ButtonTone.Danger else ButtonTone.Primary,
                )
            }
        }
    }
}

/**
 * Transient messages, bottom centre.
 *
 * The store owns the list and the dismissal timer; this only draws it, which is what lets the
 * same store serve a screen that has no toasts at all.
 */
@Composable
fun ToastHost(toasts: List<Toast>) {
    if (toasts.isEmpty()) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            toasts.forEach { toast ->
                val accent = when (toast.kind) {
                    ToastKind.Ok -> Tokens.ok
                    ToastKind.Error -> Tokens.red
                    ToastKind.Plain -> Tokens.lineStrong
                }
                Row(
                    Modifier
                        .padding(top = 8.dp)
                        .clip(Tokens.pill)
                        .background(Tokens.elev2)
                        .border(1.dp, accent, Tokens.pill)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(toast.message, color = Tokens.tx, fontSize = 12.5.sp)
                }
            }
        }
    }
}
