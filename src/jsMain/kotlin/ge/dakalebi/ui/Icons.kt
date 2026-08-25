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
 * The icons that stayed here rather than moving to keel's `LucideIcon`.
 *
 * [back10] and [forward10] have no lucide equivalent - the "10" is baked into the
 * glyph itself. [play], [pause], [home] and [gear] are used only by the TV shell
 * (`TvVideoPlayer.kt`, `TvNavRail.kt`), which keeps this whole rendering path
 * deliberately: the rail's `.tv-nav-item .ic` and the player's `.tv-ctl-btn .ic`
 * carry a measured `display: grid; line-height: 0` fix for a real reported
 * alignment bug, and keel's `.icon` is `inline-flex` - close enough on the web
 * shell to migrate safely, not proven close enough on a remote-driven 10-foot
 * surface to risk it.
 *
 * Every other icon this object used to hold - volume, cast, fullscreen, menu,
 * more, link, download, check, back - moved to keel's `LucideIcon` on the web
 * shell only. `grid` was already unused and is simply gone.
 */
object Icons {
    val play = svg("""<path d="M7 4.5v15l12-7.5z"/>""", fill = "currentColor")
    val pause = svg("""<path d="M8.5 4.5v15M15.5 4.5v15"/>""")
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

    // The TV navigation rail. Line icons at the same weight as the rest, because a
    // rail sets them beside each other at 24px and a mismatch in stroke shows.
    val home = svg("""<path d="M3.5 10.2L12 3.5l8.5 6.7V20h-6v-6h-5v6h-6z"/>""")
    /**
     * A cog outline. The first attempt drew eight radiating spokes around a circle,
     * which at a rail's 24px reads as a sunburst rather than a gear — the teeth have to
     * be part of the body's silhouette, not separate strokes floating outside it.
     */
    val gear = svg(
        """<circle cx="12" cy="12" r="2.9"/>
           <path d="M19.4 14.6a7.6 7.6 0 0 0 0-5.2l2-1.4-2-3.4-2.3 1a7.6 7.6 0 0 0-2.3-1.3
                    L14.4 2h-4l-.4 2.3a7.6 7.6 0 0 0-2.3 1.3l-2.3-1-2 3.4 2 1.4a7.6 7.6 0 0 0 0 5.2
                    l-2 1.4 2 3.4 2.3-1a7.6 7.6 0 0 0 2.3 1.3l.4 2.3h4l.4-2.3a7.6 7.6 0 0 0 2.3-1.3
                    l2.3 1 2-3.4z"/>""",
    )
}
