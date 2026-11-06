package ge.dakalebi.next.web

import androidx.compose.runtime.Composable
import ge.dakalebi.next.gallery.Gallery

/**
 * The redesign's web root, drawn on `/next/`.
 *
 * Shows the component gallery until the web screens arrive (sign-in, the tabs, Home,
 * Seasons, Settings, then the player).
 */
@Composable
fun WebRoot() {
    Gallery(shell = "web")
}
