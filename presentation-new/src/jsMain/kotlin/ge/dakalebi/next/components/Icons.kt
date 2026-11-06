package ge.dakalebi.next.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import org.jetbrains.compose.web.dom.Span

/**
 * The icon set: 27 glyphs on a 24-unit grid, drawn with a round 2-unit stroke in
 * `currentColor`, so an icon takes the colour of the text around it.
 *
 * Each entry is the inner markup of one `<svg>`. [Icon] adds the wrapper once.
 *
 * 23 glyphs are Lucide's: 22 copied from lucide 1.34.0,
 * and [PictureInPicture], which is `picture-in-picture-2` from lucide-static 0.469.0.
 * The four seek glyphs are this app's: Lucide has the arcs but not the number, and an
 * arc alone does not say how far it seeks. [Back30] and [Forward30] are driving mode's,
 * whose skip is 30 seconds (`SKIP_SECONDS` in `DrivePlayer.kt`).
 *
 * Lucide is under the ISC licence, which asks that this notice stays with the copies:
 *
 * > ISC License. Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as
 * > part of Feather (MIT). All other copyright (c) for Lucide are held by Lucide
 * > Contributors 2022. Permission to use, copy, modify, and/or distribute this software
 * > for any purpose with or without fee is hereby granted, provided that the above
 * > copyright notice and this permission notice appear in all copies.
 * > THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD
 * > TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN
 * > NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR
 * > CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR
 * > PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
 * > ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
enum class Glyph(internal val body: String) {
    // Player
    Play("""<path d="M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z" />"""),
    Pause("""<rect x="14" y="3" width="5" height="18" rx="1" /><rect x="5" y="3" width="5" height="18" rx="1" />"""),
    SkipBack("""<path d="M17.971 4.285A2 2 0 0 1 21 6v12a2 2 0 0 1-3.029 1.715l-9.997-5.998a2 2 0 0 1-.003-3.432z" /><path d="M3 20V4" />"""),
    SkipForward("""<path d="M21 4v16" /><path d="M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z" />"""),
    Back10("""$BACK_ARC${seekFigure(10)}"""),
    Forward10("""$FORWARD_ARC${seekFigure(10)}"""),
    Back30("""$BACK_ARC${seekFigure(30)}"""),
    Forward30("""$FORWARD_ARC${seekFigure(30)}"""),
    Maximize("""<path d="M8 3H5a2 2 0 0 0-2 2v3" /><path d="M21 8V5a2 2 0 0 0-2-2h-3" /><path d="M3 16v3a2 2 0 0 0 2 2h3" /><path d="M16 21h3a2 2 0 0 0 2-2v-3" />"""),
    Minimize("""<path d="M8 3v3a2 2 0 0 1-2 2H3" /><path d="M21 8h-3a2 2 0 0 1-2-2V3" /><path d="M3 16h3a2 2 0 0 1 2 2v3" /><path d="M16 21v-3a2 2 0 0 1 2-2h3" />"""),
    Volume2("""$SPEAKER<path d="M16 9a5 5 0 0 1 0 6" /><path d="M19.364 18.364a9 9 0 0 0 0-12.728" />"""),
    VolumeX("""$SPEAKER<line x1="22" x2="16" y1="9" y2="15" /><line x1="16" x2="22" y1="9" y2="15" />"""),
    Cast("""<path d="M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6" /><path d="M2 12a9 9 0 0 1 8 8" /><path d="M2 16a5 5 0 0 1 4 4" /><line x1="2" x2="2.01" y1="20" y2="20" />"""),
    PictureInPicture("""<path d="M21 9V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10c0 1.1.9 2 2 2h4" /><rect width="10" height="7" x="12" y="13" rx="2" />"""),

    // Navigation
    House("""<path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8" /><path d="M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />"""),
    LayoutGrid("""<rect width="7" height="7" x="3" y="3" rx="1" /><rect width="7" height="7" x="14" y="3" rx="1" /><rect width="7" height="7" x="14" y="14" rx="1" /><rect width="7" height="7" x="3" y="14" rx="1" />"""),
    Settings("""<path d="M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915" /><circle cx="12" cy="12" r="3" />"""),
    ChevronLeft("""<path d="m15 18-6-6 6-6" />"""),
    ChevronDown("""<path d="m6 9 6 6 6-6" />"""),
    X("""<path d="M18 6 6 18" /><path d="m6 6 12 12" />"""),
    EllipsisVertical("""<circle cx="12" cy="12" r="1" /><circle cx="12" cy="5" r="1" /><circle cx="12" cy="19" r="1" />"""),

    // Actions and status
    Check("""<path d="M20 6 9 17l-5-5" />"""),
    Link("""<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" /><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />"""),
    ExternalLink("""<path d="M15 3h6v6" /><path d="M10 14 21 3" /><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />"""),
    Download("""<path d="M12 15V3" /><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><path d="m7 10 5 5 5-5" />"""),
    Trash2("""<path d="M10 11v6" /><path d="M14 11v6" /><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" /><path d="M3 6h18" /><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />"""),
    CircleAlert("""<circle cx="12" cy="12" r="10" /><line x1="12" x2="12" y1="8" y2="12" /><line x1="12" x2="12.01" y1="16" y2="16" />"""),
}

/** The speaker both volume glyphs share. */
private const val SPEAKER =
    """<path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z" />"""

/** The seek arcs, without their number. */
private const val BACK_ARC = """<path d="M3.5 8.5A9 9 0 1 1 3 12" /><path d="M3.5 3.5v5h5" />"""
private const val FORWARD_ARC = """<path d="M20.5 8.5A9 9 0 1 0 21 12" /><path d="M20.5 3.5v5h-5" />"""

/**
 * The number inside a seek arc. A filled `<text>`, not a stroke; `components.css` sets its
 * face to the figures font, which an SVG attribute cannot read from a token.
 */
private fun seekFigure(seconds: Int): String =
    """<text x="12" y="15.6" font-size="8" fill="currentColor" stroke="none" text-anchor="middle">$seconds</text>"""

/**
 * One glyph, sized by `--icon-size` (or `--icon-size-s` when [small]) and stroked with
 * `--icon-stroke`, so a shell's tokens resize every icon at once.
 *
 * Decorative by default (`aria-hidden`): an icon next to words, or inside a control that
 * has its own name, adds nothing to read out. Pass [label] only for an icon that stands
 * alone and means something, which makes it an image with that name.
 */
@Composable
fun Icon(glyph: Glyph, small: Boolean = false, label: String? = null) {
    Span({
        classes("icon")
        if (small) classes("icon--s")
        if (label != null) {
            attr("role", "img")
            attr("aria-label", label)
        } else {
            attr("aria-hidden", "true")
        }
    }) {
        // Markup is set directly: Compose HTML's SVG DSL is experimental, and these are
        // fixed strings from the enum above, never user input.
        DisposableEffect(glyph) {
            scopeElement.innerHTML = svg(glyph.body)
            onDispose {}
        }
    }
}

private fun svg(body: String): String =
    """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" """ +
        """stroke-linecap="round" stroke-linejoin="round" focusable="false" aria-hidden="true">$body</svg>"""
