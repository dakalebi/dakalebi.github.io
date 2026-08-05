package ge.dakalebi.web

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
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
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val georgian = FontFamily(Font(Res.font.noto_sans_georgian))
    MaterialTheme(
        colorScheme = darkColorScheme(),
        typography = Typography().withFontFamily(georgian),
        content = content,
    )
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
