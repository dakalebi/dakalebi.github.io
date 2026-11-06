package ge.dakalebi.ui.tv.player

import androidx.compose.runtime.Composable
import ge.dakalebi.core.formatTime
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.ui.Icon as PlayerIcon
import ge.dakalebi.ui.Icons as PlayerIcons
import ge.dakalebi.ui.tv.actsAsButton
import ge.dakalebi.ui.tv.actsAsOption
import ge.dakalebi.ui.tv.actsAsOptionGroup
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import ge.dakalebi.ui.tv.ownsPopup
import io.github.bchmsl.keel.components.ButtonSize
import io.github.bchmsl.keel.components.ButtonVariant
import io.github.bchmsl.keel.components.SwitchSize
import io.github.bchmsl.keel.dom.buttonClasses
import io.github.bchmsl.keel.dom.classNames
import io.github.bchmsl.keel.dom.switchClasses
import io.github.bchmsl.keel.dom.switchKnobClasses
import io.github.bchmsl.keel.icons.Icon
import io.github.bchmsl.keel.icons.LucideIcon
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * [TvVideoPlayer]'s chrome: the scrubber on top, the buttons in a row beneath it.
 *
 * This is **not** the Leanback reference layout, which docks the primary
 * transport row *above* the bar. It is what YouTube on a television actually
 * does since its December 2025 redesign: the bar sits on top of the cluster
 * with the buttons immediately below, and the episode title is lifted out of
 * the cluster to the top-left of the screen entirely. Where the two disagree,
 * the brief was to look like YouTube.
 *
 * Structurally the chrome is one `Y` group holding two rows, the lower of them
 * a nested `X` group. That is what lets the engine own vertical movement
 * between the bar and the buttons while the player only has to intercept
 * horizontal presses, and nested groups are exactly the case the traversal
 * rules were fixed for.
 *
 * [ordered] is the renditions best-first; the quality button shows only when there
 * is more than one. [autoplayNext] and [onToggleAutoplay] are [TvVideoPlayer]'s own.
 */
@Composable
internal fun TvPlayerChrome(
    player: TvPlayerState,
    ordered: List<String>,
    quality: String?,
    onQualitySelected: (String) -> Unit,
    autoplayNext: Boolean?,
    onToggleAutoplay: (() -> Unit)?,
) {
    val refs = player.refs
    Div({
        // Hidden while the ring is down in the shelf, which is the other half of
        // the progressive reveal: the room the bands move into is the room the
        // transport was using. `.hide` is opacity, not `visibility`, so the bar
        // stays focusable and Up out of the first band still finds it.
        classNames(
            "tv-ctl",
            if (player.mode == Mode.Controls && player.shelfLevel == 0) null else "hide",
        )
        focusGroup("player-chrome", FocusAxis.Y)
    }) {
        // Elapsed at the start, total at the end, flanking the bar — YouTube's
        // placement. Not a focus stop, just a readout.
        Div({ classes("tv-ctl-times", "mono") }) {
            Span { Text(formatTime(player.currentSec.toDouble())) }
            Span { Text(formatTime(player.durationSec.toDouble())) }
        }

        /*
         * The bar is a focus stop, not decoration.
         *
         * Samsung's platform spec puts focus here when Left or Right opened the
         * controls, and it is what makes "arrows seek" and "arrows move between
         * buttons" coexist without a mode flag: the answer is simply which item
         * holds the ring.
         */
        Div({
            classes("tv-scrub")
            focusItem("scrub", entry = true)
            // A slider, not a button: it reports a position rather than firing an action,
            // and the position is the thing worth announcing.
            attr("role", "slider")
            attr("aria-label", S.timeline)
            attr("aria-valuemin", "0")
            attr("aria-valuemax", player.durationSec.toString())
            attr("aria-valuenow", player.currentSec.toString())
        }) {
            Div({ classes("tv-scrub-buf"); ref { el -> refs.fillBuf = el; onDispose { refs.fillBuf = null } } })
            Div({ classes("tv-scrub-cur"); ref { el -> refs.fillCur = el; onDispose { refs.fillCur = null } } })
            // The playhead marker, at the current position. Last of the fills so it
            // sits on top of them.
            Div({ classes("tv-scrub-thumb"); ref { el -> refs.thumb = el; onDispose { refs.thumb = null } } })
            // Where a scrub would land, drawn only while one is open.
            Div({
                classNames("tv-scrub-ghost", if (player.mode == Mode.Scrubbing) "on" else null)
                ref { el -> refs.ghost = el; onDispose { refs.ghost = null } }
            })
        }

        /*
         * One `X` group for the whole row, even though it reads as three clusters.
         * The clusters are a CSS concern; making them three focus groups would put
         * a wall between the transport buttons and the quality button, because
         * horizontal presses deliberately cannot leave an `X` group.
         */
        Div({ classes("tv-ctl-row"); focusGroup("player-buttons", FocusAxis.X) }) {
            // The autoplay control in the left cell, mirroring the quality button on
            // the right and keeping the transport centred. Absent when there is no
            // setting behind it.
            Div({ classes("tv-ctl-start") }) {
                if (autoplayNext != null && onToggleAutoplay != null) {
                    AutoplaySwitch(autoplayNext, onToggleAutoplay)
                }
            }

            Div({ classes("tv-ctl-mid") }) { Transport(player) }

            Div({ classes("tv-ctl-end") }) {
                if (ordered.size > 1) QualityControl(player, ordered, quality, onQualitySelected)
            }
        }
    }
}

/**
 * The account's "autoplay next" setting as a labelled pill.
 *
 * A labelled pill rather than a bare toggle, because a lone switch in a player says
 * nothing about what it does. A focus stop in the button row, so Left from the
 * transport reaches it; never the entry item, so the ring still starts on the bar.
 */
@Composable
private fun AutoplaySwitch(autoplayNext: Boolean, onToggleAutoplay: () -> Unit) {
    Div({
        // keel's on-media wash on the pill, by class name: this row
        // used to write `rgba(255, 255, 255, 0.12)` out four times,
        // and it is now one token. `ButtonSize.Icon` for its
        // `padding: 0`, which `.tv-ctl-autoplay` then sets itself -
        // no size entry means "no box", and the pill's is its own.
        classNames(
            "tv-ctl-autoplay",
            buttonClasses(ButtonVariant.OnMedia, ButtonSize.Icon),
        )
        focusItem("autoplay")
        attr("role", "switch")
        attr("aria-checked", autoplayNext.toString())
        // `switch` takes its name from the author, not its contents, so
        // it needs an explicit one — the same word the pill shows, which
        // keeps the spoken and visible labels the same (WCAG 2.5.3).
        attr("aria-label", S.autoplayShort)
        onClick { onToggleAutoplay() }
    }) {
        Span({ classes("tv-ctl-autoplay-label") }) { Text(S.autoplayShort.caps) }
        // keel's switch at its small size. `aria-checked` on the
        // pill above is what announces the state; this is the
        // picture of it, so it repeats the attribute rather than
        // carrying a class - the same thing keel keys the colour
        // off, so the two cannot disagree.
        Div({
            classNames(switchClasses(SwitchSize.Small))
            attr("aria-checked", autoplayNext.toString())
            attr("aria-hidden", "true")
        }) { Div({ classNames(switchKnobClasses()) }) }
    }
}

/** Back ten seconds, play or pause, forward ten seconds. */
@Composable
private fun Transport(player: TvPlayerState) {
    Div({
        classNames("tv-ctl-btn", buttonClasses(ButtonVariant.OnMedia, ButtonSize.Icon))
        focusItem("back10")
        actsAsButton(S.back10)
        onClick { player.skip(-1) }
    }) { PlayerIcon(PlayerIcons.back10) }

    Div({
        classNames(
            "tv-ctl-btn",
            "tv-ctl-btn-lg",
            buttonClasses(ButtonVariant.OnMedia, ButtonSize.Icon),
        )
        focusItem("play")
        actsAsButton(if (player.playing) S.pause else S.play)
        onClick { player.togglePlay() }
    }) {
        // keel's icon, sized by `.tv-ctl-ic-lg` rather than at the call
        // site: see the note in `TvNavRail`. The two glyphs either side
        // of it stay local because the "10" is inside those drawings.
        Icon(
            if (player.playing) LucideIcon.Pause else LucideIcon.Play,
            size = null,
            className = "tv-ctl-ic-lg",
        )
    }

    Div({
        classNames("tv-ctl-btn", buttonClasses(ButtonVariant.OnMedia, ButtonSize.Icon))
        focusItem("forward10")
        actsAsButton(S.forward10)
        onClick { player.skip(1) }
    }) { PlayerIcon(PlayerIcons.forward10) }
}

/** The quality button and, while it is open, its menu of renditions. */
@Composable
private fun QualityControl(
    player: TvPlayerState,
    ordered: List<String>,
    quality: String?,
    onQualitySelected: (String) -> Unit,
) {
    val refs = player.refs
    val shown = quality ?: ordered.first()
    /*
     * The menu is a child of the cell that holds the button, and is
     * placed directly above it in CSS.
     *
     * It used to be a sibling of the whole control bar, positioned
     * off the bottom of the picture — an anchor that was right when
     * the chrome was three rows tall and wrong the moment the
     * episode rails joined it: the menu then opened over the rails,
     * *behind* them (later sibling, no `z-index`), and the spatial
     * engine treated it as one more band sitting in the layout, so
     * Down from the button reached a rail tile rather than the menu.
     * Anchoring it to its own button is what makes all three go
     * away at once.
     */
    if (player.qualityOpen) {
        Div({
            classes("tv-q-menu")
            focusGroup("quality", FocusAxis.Y)
            actsAsOptionGroup(S.quality)
            ref { element ->
                refs.qualityMenu = element
                onDispose { refs.qualityMenu = null }
            }
        }) {
            ordered.forEach { label ->
                Div({
                    classNames(
                        "tv-q-item",
                        if (label == quality) "on" else null,
                    )
                    focusItem("q-$label", entry = label == quality)
                    actsAsOption(selected = label == quality)
                    onClick { player.qualityOpen = false; onQualitySelected(label) }
                }) { Text(label) }
            }
        }
    }
    Div({
        classNames(
            "tv-ctl-btn",
            "tv-q-btn",
            "mono",
            buttonClasses(ButtonVariant.OnMedia, ButtonSize.Icon),
        )
        focusItem("quality")
        // The name carries the rendition, because the rendition is
        // what this button visibly shows. "Quality" alone replaced it
        // and left the current selection unannounced — the one thing
        // someone opening this menu wants to know first.
        actsAsButton("${S.quality}: $shown")
        // And it opens a menu, which an ordinary button does not.
        ownsPopup(player.qualityOpen)
        onClick { player.qualityOpen = !player.qualityOpen }
    }) { Text(shown) }
}
