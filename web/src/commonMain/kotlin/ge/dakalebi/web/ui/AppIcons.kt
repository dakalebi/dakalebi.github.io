package ge.dakalebi.web.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's icons, as path data on a 24-unit grid.
 *
 * The same paths the DOM front ends inline as SVG, so the two apps draw identical glyphs. They
 * stay as strings rather than becoming `ImageVector`s because that is the form they are
 * authored and reviewed in, and [AppIcon] parses each one once.
 *
 * Line icons at one weight throughout: the design sets them beside each other at 20px, and a
 * mismatch in stroke shows immediately.
 */
data class AppIcon(val strokes: List<String> = emptyList(), val fills: List<String> = emptyList())

object AppIcons {
    val play = AppIcon(fills = listOf("M7 4.5v15l12-7.5z"))
    val pause = AppIcon(strokes = listOf("M8.5 4.5v15M15.5 4.5v15"))

    /** The circular arrow only; the "10" is set as text beside it, in the app's own font. */
    val back10 = AppIcon(strokes = listOf("M3.5 8.5A9 9 0 1 1 3 12", "M3.5 3.5v5h5"))
    val forward10 = AppIcon(strokes = listOf("M20.5 8.5A9 9 0 1 0 21 12", "M20.5 3.5v5h-5"))

    val volumeOn = AppIcon(
        strokes = listOf("M16 9a4 4 0 0 1 0 6", "M18.5 6.5a7.5 7.5 0 0 1 0 11"),
        fills = listOf("M4 9.5v5h3.5L12 18.5v-13L7.5 9.5z"),
    )
    val volumeOff = AppIcon(
        strokes = listOf("M16 9.5l5 5M21 9.5l-5 5"),
        fills = listOf("M4 9.5v5h3.5L12 18.5v-13L7.5 9.5z"),
    )

    val fullscreen = AppIcon(strokes = listOf("M4 9V4h5M20 15v5h-5M20 9V4h-5M4 15v5h5"))
    val exitFullscreen = AppIcon(strokes = listOf("M9 4v5H4M15 20v-5h5M15 4v5h5M9 20v-5H4"))

    val menu = AppIcon(strokes = listOf("M3.5 6.5h17M3.5 12h17M3.5 17.5h17"))
    val more = AppIcon(
        fills = listOf(
            "M12 3.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z",
            "M12 10.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z",
            "M12 17.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z",
        ),
    )
    val link = AppIcon(
        strokes = listOf(
            "M10 13.5a4 4 0 0 0 5.7 0l3-3a4 4 0 1 0-5.7-5.7l-1.4 1.4",
            "M14 10.5a4 4 0 0 0-5.7 0l-3 3a4 4 0 1 0 5.7 5.7l1.4-1.4",
        ),
    )
    val download = AppIcon(strokes = listOf("M12 3.5v11M7.5 10.5l4.5 4.5 4.5-4.5M4.5 20h15"))
    val check = AppIcon(strokes = listOf("M4.5 12.5l5 5 10-11"))
    val back = AppIcon(strokes = listOf("M15 4.5L7.5 12l7.5 7.5"))
    val close = AppIcon(strokes = listOf("M5.5 5.5l13 13M18.5 5.5l-13 13"))
}

/** The grid the paths above are authored on. */
private const val ICON_VIEWPORT = 24f

/** Matches the SVG's `stroke-width`, in the same 24-unit space. */
private const val ICON_STROKE = 1.9f

@Composable
fun AppIconView(
    icon: AppIcon,
    tint: Color = Tokens.tx,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    // Parsed once per icon rather than on every frame the player's clock ticks.
    val strokes = remember(icon) { icon.strokes.map { it.toPath() } }
    val fills = remember(icon) { icon.fills.map { it.toPath() } }

    Canvas(modifier.size(size)) {
        val scale = this.size.minDimension / ICON_VIEWPORT
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            fills.forEach { drawPath(it, color = tint, style = Fill) }
            strokes.forEach {
                drawPath(
                    path = it,
                    color = tint,
                    style = Stroke(
                        width = ICON_STROKE,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}

private fun String.toPath(): Path = PathParser().parsePathString(this).toPath()
