package ge.dakalebi.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the elements laid over the app must get out of the way.
 *
 * The stills and the player's `<video>` are DOM elements above the canvas (see [NetworkImage]),
 * which means they are also above everything Compose draws — including a scrim, a dialog and the
 * panel behind it. Left alone they stay at full brightness over a dimmed page and overlap the panel
 * itself, which is what a modal is supposed to make impossible.
 *
 * A count rather than a flag: a dialog can open from a sheet that is already up, and the layer must
 * not come back until the last of them has gone.
 *
 * Compose state, so the platform layer recomposes and hides its elements the moment this changes.
 */
object OverlayGate {
    private var modals by mutableStateOf(0)

    /** True while any modal surface is on screen. */
    val suppressed: Boolean get() = modals > 0

    internal fun enter() {
        modals += 1
    }

    internal fun leave() {
        modals -= 1
    }
}

/**
 * Suppresses the overlay layer for as long as the caller is composed.
 *
 * Belongs to whatever draws a modal surface — [Scrim] uses it, which covers every dialog and the
 * account panel with it. Toasts deliberately do not: they are transient and small, and blanking
 * every still on the page for three seconds would be the more jarring of the two.
 */
@Composable
fun SuppressOverlays() {
    DisposableEffect(Unit) {
        OverlayGate.enter()
        onDispose { OverlayGate.leave() }
    }
}
