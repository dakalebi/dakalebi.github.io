package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Span

/**
 * Inline SVG icons, for the handful this app draws itself rather than through
 * keel's `LucideIcon`. Unicode glyphs were the first attempt and are not
 * dependable - the cast symbol has no glyph in the default stacks and rendered as
 * a tofu box - which is why these are static, author-controlled SVG strings.
 */
/**
 * Renders one of the [Icons] strings.
 *
 * `ref` runs once per element and never again, so writing `innerHTML` there would
 * freeze a glyph that is meant to change at whatever it was on first render - the
 * trap that cost a real bug when this composable still rendered play/pause and
 * the mute state. `DomSideEffect` re-runs whenever [markup] changes, which is
 * exactly the dependency needed for that case, and costs nothing for the
 * fixed glyphs left here.
 */
@Composable
fun Icon(markup: String, label: String? = null) {
    Span({
        classes("ic")
        if (label != null) attr("aria-label", label) else attr("aria-hidden", "true")
    }) {
        DomSideEffect(markup) { element ->
            element.innerHTML = markup
        }
    }
}

private fun svg(body: String, fill: String = "none"): String =
    """<svg viewBox="0 0 24 24" width="20" height="20" fill="$fill" stroke="currentColor"
       stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"
       focusable="false" aria-hidden="true">$body</svg>"""

/**
 * The two icons that stayed here rather than moving to keel's `LucideIcon`.
 *
 * Neither has a lucide equivalent, and the reason is the same for both: the "10" is
 * baked into the glyph as a `<text>` node, so the drawing carries a number rather
 * than only a shape. lucide ships `RotateCcw`/`RotateCw`, which are the arcs without
 * it - and an arc alone does not say how far it seeks.
 *
 * Everything else this object used to hold is gone. `volume`, `cast`, `fullscreen`,
 * `menu`, `more`, `link`, `download`, `check` and `back` moved to `LucideIcon` with
 * the web shell; `play`, `pause`, `home` and `gear` followed on the TV shell once
 * keel's `.icon` was measured to carry the same `line-height: 0` plus
 * `svg { display: block }` fix that this path's `.ic` was written for - the doubt
 * recorded here previously was about that fix, and it no longer applies.
 */
object Icons {
    val back10 = svg(
        """<path d="M3.5 8.5A9 9 0 1 1 3 12"/><path d="M3.5 3.5v5h5"/>
           <text x="12" y="15.6" font-size="8" font-family="ui-monospace,monospace"
                 fill="currentColor" stroke="none" text-anchor="middle">10</text>""",
    )
    /** [back10] mirrored. A remote has no keyboard, so forward needs a button. */
    val forward10 = svg(
        """<path d="M20.5 8.5A9 9 0 1 0 21 12"/><path d="M20.5 3.5v5h-5"/>
           <text x="12" y="15.6" font-size="8" font-family="ui-monospace,monospace"
                 fill="currentColor" stroke="none" text-anchor="middle">10</text>""",
    )
}
