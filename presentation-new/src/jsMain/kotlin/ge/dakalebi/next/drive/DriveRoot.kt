package ge.dakalebi.next.drive

import androidx.compose.runtime.Composable
import ge.dakalebi.next.gallery.Gallery

/**
 * The redesign's driving-mode root, drawn on `/next/drive/`.
 *
 * Shows the component gallery until the drive screens arrive, at drive's larger sizes.
 */
@Composable
fun DriveRoot() {
    Gallery(shell = "drive")
}
