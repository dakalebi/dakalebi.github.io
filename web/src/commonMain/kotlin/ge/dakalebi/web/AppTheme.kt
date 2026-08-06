package ge.dakalebi.web

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import ge.dakalebi.web.resources.Res
import ge.dakalebi.web.resources.noto_sans_georgian
import org.jetbrains.compose.resources.Font

/**
 * The app's theme for the Compose Multiplatform (canvas) front end.
 *
 * The one job that cannot be skipped here is the font. A canvas has no system fonts, so
 * skiko renders any glyph it lacks as tofu — which is every Georgian character, the whole
 * UI. Noto Sans Georgian is bundled and set as the default family for the entire type
 * scale; it also covers Latin and digits, so one family serves the whole app. It includes
 * the Mtavruli range (U+1C90 and up) that the chrome's uppercase treatment relies on.
 */
/**
 * How much bigger than the browser's own scale the app is laid out.
 *
 * A canvas app sets its own physical size for everything, and matching the DOM app's CSS pixel
 * figures one for one came out noticeably smaller than the page it replaces — no browser default
 * font size underneath it, and no user zoom to lean on.
 *
 * Applied as a density override rather than by editing every size, so one figure moves the whole
 * design together: type, spacing, icons, tiles and the player's controls all keep their proportions.
 */
private const val UI_SCALE = 1.3f

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val georgian = FontFamily(Font(Res.font.noto_sans_georgian))
    val browser = LocalDensity.current

    CompositionLocalProvider(
        LocalDensity provides Density(browser.density * UI_SCALE, browser.fontScale),
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(),
            typography = Typography().withFontFamily(georgian),
            content = content,
        )
    }
}

/** Every Material type-scale style, re-based onto [family]. */
private fun Typography.withFontFamily(family: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)
