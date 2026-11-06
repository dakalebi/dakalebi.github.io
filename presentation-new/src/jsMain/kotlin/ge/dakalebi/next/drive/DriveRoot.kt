package ge.dakalebi.next.drive

import androidx.compose.runtime.Composable
import ge.dakalebi.next.foundations.Foundations

/**
 * The redesign's driving-mode root, drawn on `/next/drive/`.
 *
 * Shows the foundations until the drive screens arrive, at drive's larger sizes.
 */
@Composable
fun DriveRoot() {
    Foundations(shell = "drive")
}
