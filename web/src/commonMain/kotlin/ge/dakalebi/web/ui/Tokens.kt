package ge.dakalebi.web.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/**
 * The design tokens, as Kotlin.
 *
 * The same palette and rounding scale as `tokens.css`, which the DOM front ends read. Kept as
 * values rather than folded into a Material `ColorScheme` because most of them have no
 * Material role: `line`, `elev2` and `mut` are surfaces and hairlines this design uses
 * directly, and mapping them onto `surfaceVariant`/`outline` would only hide which is which.
 *
 * Deliberately single-theme: this is a cinema surface, and a light variant would fight the
 * video it exists to frame. True black rather than charcoal so the player has no visible seam
 * against the page on OLED screens.
 */
object Tokens {
    val bg = Color(0xFF000000)
    val elev = Color(0xFF0D0D0F)
    val elev2 = Color(0xFF141417)
    val line = Color(0xFF1C1C20)
    val lineStrong = Color(0xFF2A2A30)
    val tx = Color(0xFFFFFFFF)
    val txDim = Color(0xFFB6B6BD)
    val mut = Color(0xFF8A8A93)
    val red = Color(0xFFE1352F)
    val redDim = Color(0xFF7A1C19)
    val ok = Color(0xFF3ECF8E)

    /**
     * What a surface becomes under the pointer.
     *
     * A canvas has no `:hover`, so every one of these is a state the app has to hold and paint
     * itself. They are a clear step up rather than a nudge — on a dark surface a two-percent change
     * is invisible, which is exactly how this design first shipped.
     */
    val elevHover = Color(0xFF1C1C21)
    val elev2Hover = Color(0xFF26262D)
    val redHover = Color(0xFFF04A44)
    val lineHover = Color(0xFF3C3C46)

    /** Cards, panels and the player. */
    val radius = RoundedCornerShape(14.dp)

    /** Small chrome sitting on top of media. */
    val radiusSmall = RoundedCornerShape(9.dp)

    val pill = RoundedCornerShape(percent = 50)

    /** Page gutter. The CSS clamps this to the viewport; here it is the middle value. */
    val pad = 28.dp
    val railGap = 14.dp
}

/**
 * Stand-in artwork for episodes without a still.
 *
 * Deterministic on season and episode so a given episode always gets the same colours — a
 * random palette per render would make the grid feel unstable. The palettes and the gradient
 * geometry are the CSS version's, so a tile looks the same in both front ends.
 */
fun fallbackBrush(season: Int, episode: Int): Brush {
    val palette = FALLBACK_PALETTES[abs(season * 7 + episode * 3) % FALLBACK_PALETTES.size]
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader = RadialGradientShader(
            center = Offset(size.width * 0.3f, size.height * 0.2f),
            radius = max(size.width, size.height) * 1.05f,
            colors = palette,
            colorStops = listOf(0f, 0.45f, 1f),
        )
    }
}

private val FALLBACK_PALETTES: List<List<Color>> = listOf(
    listOf(Color(0xFF1C2D44), Color(0xFF142033), Color(0xFF0B1220)),
    listOf(Color(0xFF1B4A3D), Color(0xFF13322B), Color(0xFF0C1A18)),
    listOf(Color(0xFF15414F), Color(0xFF0F2A32), Color(0xFF0A1A1F)),
    listOf(Color(0xFF2A2440), Color(0xFF1F1B2E), Color(0xFF15131F)),
    listOf(Color(0xFF232838), Color(0xFF191D28), Color(0xFF10131A)),
    listOf(Color(0xFF1A3346), Color(0xFF142433), Color(0xFF0D1820)),
)
