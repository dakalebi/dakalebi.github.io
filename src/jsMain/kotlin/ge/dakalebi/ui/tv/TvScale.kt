package ge.dakalebi.ui.tv

import ge.dakalebi.domain.model.InterfaceScale
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Writes the viewer's size preference onto the document element, where `tv.css` reads
 * it as `--tv-scale`.
 *
 * **Not Compose state, and it cannot be.** The whole TV sheet is written in `rem`, so
 * the only way to move all of it at once is to move what a `rem` *is* — the root font
 * size — and the root element is outside the composition entirely. There is nothing to
 * recompose here: one custom property changes, and the browser relays every length that
 * hangs off it. That is also why this is cheap enough to apply on each press while the
 * viewer is choosing, which is what makes the choice previewable at all.
 *
 * Called twice: once from `Main.kt` before the first paint, so a reload does not flash
 * the designed size at someone who chose a different one — the same reason the cached
 * language is applied there — and once per change from the settings screen.
 */
fun applyInterfaceScale(percent: Int) {
    (document.documentElement as? HTMLElement)
        ?.style
        ?.setProperty("--tv-scale", InterfaceScale.multiplierOf(percent))
}
