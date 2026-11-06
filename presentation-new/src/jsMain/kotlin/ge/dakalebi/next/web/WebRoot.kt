package ge.dakalebi.next.web

import androidx.compose.runtime.Composable
import ge.dakalebi.next.foundations.Foundations

/**
 * The redesign's web root, drawn on `/next/`.
 *
 * Shows the foundations until the web screens arrive (sign-in, the tabs, Home, Seasons,
 * Settings, then the player).
 */
@Composable
fun WebRoot() {
    Foundations(shell = "web")
}
