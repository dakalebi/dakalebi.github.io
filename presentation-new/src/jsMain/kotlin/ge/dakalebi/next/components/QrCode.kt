package ge.dakalebi.next.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import ge.dakalebi.core.Log
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * [text] as a QR code, for a link a television cannot open: the viewer scans it with a
 * phone. Dark squares on a light card with the standard four-square margin, so a phone
 * reads it on either theme.
 *
 * An image named [label] to assistive technology. When [text] cannot be encoded (it
 * never should: a link is far below the format's limit), the card shows [text] itself,
 * which is still something to type.
 */
@Composable
fun QrCode(text: String, label: String) {
    val svg = remember(text) { qrSvg(text) }
    if (svg == null) {
        P({ classes("qr", "qr--text") }) { Text(text) }
        return
    }
    Div({
        classes("qr")
        attr("role", "img")
        attr("aria-label", label)
    }) {
        // Markup is set directly, as in [Icon]. It holds only numbers this file computed:
        // the squares' positions, never [text] or any other data.
        DisposableEffect(svg) {
            scopeElement.innerHTML = svg
            onDispose {}
        }
    }
}

/** The empty squares around the code that a scanner needs to find it. */
private const val QUIET_ZONE = 4

/**
 * The SVG for [text], or null when the library refuses it. Each run of dark squares in
 * a row is one rectangle in a single path, so a code of about 2,000 squares is a few
 * kilobytes of markup and one element.
 */
private fun qrSvg(text: String): String? {
    val code = try {
        // 0 picks the smallest version that fits; M recovers from about 15% damage,
        // enough for a screen seen at an angle.
        qrcode(0, "M").apply {
            addData(text)
            make()
        }
    } catch (e: Throwable) {
        Log.w("qr", "could not encode ${text.length} characters", e)
        return null
    }
    val count = code.getModuleCount()
    val size = count + QUIET_ZONE * 2
    val path = StringBuilder()
    for (row in 0 until count) {
        var col = 0
        while (col < count) {
            if (!code.isDark(row, col)) {
                col++
                continue
            }
            val start = col
            while (col < count && code.isDark(row, col)) col++
            val run = col - start
            path.append("M${start + QUIET_ZONE} ${row + QUIET_ZONE}h${run}v1h-${run}z")
        }
    }
    return """<svg viewBox="0 0 $size $size" focusable="false" aria-hidden="true" """ +
        """shape-rendering="crispEdges"><path fill="currentColor" d="$path"/></svg>"""
}

/**
 * qrcode-generator 2.0.4 (Kazuhiko Arase, MIT, no dependencies). Its CommonJS build's
 * whole export is this function. [JsNonModule] only because this library compiles as
 * UMD; the app's bundle is CommonJS and always takes the module.
 */
@JsModule("qrcode-generator")
@JsNonModule
private external fun qrcode(typeNumber: Int, errorCorrectionLevel: String): QrCodeModel

private external interface QrCodeModel {
    fun addData(data: String)
    fun make()
    fun getModuleCount(): Int
    fun isDark(row: Int, col: Int): Boolean
}
