package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Span

/**
 * Inline SVG icons.
 *
 * Unicode glyphs were the first attempt and are not dependable: the cast
 * symbol has no glyph in the default stacks and rendered as a tofu box, and
 * the speaker characters come through as colour emoji, which is wrong inside a
 * monochrome player. These are static, author-controlled strings.
 */
@Composable
fun Icon(markup: String, label: String? = null) {
    Span({
        classes("ic")
        if (label != null) attr("aria-label", label) else attr("aria-hidden", "true")
        ref { element ->
            element.innerHTML = markup
            onDispose { }
        }
    })
}

private fun svg(body: String, fill: String = "none"): String =
    """<svg viewBox="0 0 24 24" width="20" height="20" fill="$fill" stroke="currentColor"
       stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"
       focusable="false" aria-hidden="true">$body</svg>"""

object Icons {
    val play = svg("""<path d="M7 4.5v15l12-7.5z"/>""", fill = "currentColor")
    val pause = svg("""<path d="M8.5 4.5v15M15.5 4.5v15"/>""")
    val back10 = svg(
        """<path d="M3.5 8.5A9 9 0 1 1 3 12"/><path d="M3.5 3.5v5h5"/>
           <text x="12" y="15.6" font-size="8" font-family="ui-monospace,monospace"
                 fill="currentColor" stroke="none" text-anchor="middle">10</text>""",
    )
    val volumeOn = svg(
        """<path d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5z" fill="currentColor"/>
           <path d="M16 9a4 4 0 0 1 0 6"/><path d="M18.5 6.5a7.5 7.5 0 0 1 0 11"/>""",
    )
    val volumeOff = svg(
        """<path d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5z" fill="currentColor"/>
           <path d="M16 9.5l5 5M21 9.5l-5 5"/>""",
    )
    val cast = svg(
        """<path d="M3 18.5a2.5 2.5 0 0 1 2.5 2.5"/>
           <path d="M3 14.5a6.5 6.5 0 0 1 6.5 6.5"/>
           <path d="M3 10.5a10.5 10.5 0 0 1 10.5 10.5"/>
           <path d="M3 8V5.5A1.5 1.5 0 0 1 4.5 4h15A1.5 1.5 0 0 1 21 5.5v13a1.5 1.5 0 0 1-1.5 1.5H17"/>""",
    )
    val fullscreen = svg("""<path d="M4 9V4h5M20 15v5h-5M20 9V4h-5M4 15v5h5"/>""")
    val exitFullscreen = svg("""<path d="M9 4v5H4M15 20v-5h5M15 4v5h5M9 20v-5H4"/>""")
    val menu = svg("""<path d="M3.5 6.5h17M3.5 12h17M3.5 17.5h17"/>""")
    val more = svg(
        """<circle cx="12" cy="5" r="1.5" fill="currentColor" stroke="none"/>
           <circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none"/>
           <circle cx="12" cy="19" r="1.5" fill="currentColor" stroke="none"/>""",
    )
    val link = svg(
        """<path d="M10 13.5a4 4 0 0 0 5.7 0l3-3a4 4 0 1 0-5.7-5.7l-1.4 1.4"/>
           <path d="M14 10.5a4 4 0 0 0-5.7 0l-3 3a4 4 0 1 0 5.7 5.7l1.4-1.4"/>""",
    )
    val download = svg("""<path d="M12 3.5v11M7.5 10.5l4.5 4.5 4.5-4.5M4.5 20h15"/>""")
    val check = svg("""<path d="M4.5 12.5l5 5 10-11"/>""")
    val back = svg("""<path d="M15 4.5L7.5 12l7.5 7.5"/>""")
}
