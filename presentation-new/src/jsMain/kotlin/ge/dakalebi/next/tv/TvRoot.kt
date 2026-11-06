package ge.dakalebi.next.tv

import androidx.compose.runtime.Composable
import ge.dakalebi.next.foundations.Foundations

/**
 * The redesign's TV root, drawn on `/next/tv/`.
 *
 * Shows the foundations until the TV screens arrive. Nothing here takes focus yet, so a
 * remote has nothing to move; the screen is sized to fit a 960x540 panel without scrolling.
 */
@Composable
fun TvRoot() {
    Foundations(shell = "tv")
}
