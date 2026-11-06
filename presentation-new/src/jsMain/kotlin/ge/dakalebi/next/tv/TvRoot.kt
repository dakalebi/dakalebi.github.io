package ge.dakalebi.next.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import ge.dakalebi.next.gallery.Gallery
import ge.dakalebi.next.tv.focus.SpatialNav
import ge.dakalebi.next.tv.input.LocalTvInput
import ge.dakalebi.next.tv.input.TvInput
import ge.dakalebi.next.tv.input.TvLayer
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLElement

/** The stage element, set by `ref`. */
private class StageRef {
    var element: HTMLElement? = null
}

/**
 * The redesign's TV root, drawn on `/next/tv/`.
 *
 * Owns the one [TvInput]: it installs the window listener, pushes the screen's base
 * layer (not dismissible, so Back at the top asks the input layer what to do), and
 * provides the input to everything below as [LocalTvInput], which is how components
 * know to draw D-pad stops instead of web keyboard handling.
 *
 * The stage is its own scroller, because the focus engine scrolls the nearest
 * overflowing ancestor to centre the ring and the page itself never scrolls on TV.
 *
 * Shows the component gallery until the TV screens arrive.
 */
@Composable
fun TvRoot() {
    val input = remember { TvInput() }
    val stage = remember { StageRef() }

    // A DisposableEffect, not a LaunchedEffect: it runs in the same frame as the first
    // composition, so no key press arrives before the listener is there.
    DisposableEffect(input) {
        val removeListeners = input.install()
        val layer = input.push(TvLayer(key = "tv-root", root = { stage.element }, dismissible = false))
        // `setTimeout`, not the frame clock, which a background WebView stops.
        val landFocus = window.setTimeout({ stage.element?.let { SpatialNav.ensureFocused(it) } }, 0)

        onDispose {
            window.clearTimeout(landFocus)
            layer.dismiss()
            removeListeners()
        }
    }

    CompositionLocalProvider(LocalTvInput provides input) {
        Div({
            classes("tv-stage")
            ref { element ->
                stage.element = element
                onDispose { stage.element = null }
            }
        }) {
            Gallery(shell = "tv")
        }
    }
}
