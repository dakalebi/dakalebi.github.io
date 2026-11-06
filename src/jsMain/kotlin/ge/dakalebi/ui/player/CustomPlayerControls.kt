package ge.dakalebi.ui.player

import androidx.compose.runtime.Composable
import ge.dakalebi.core.formatTime
import ge.dakalebi.i18n.S
import ge.dakalebi.ui.Icon as PlayerIcon
import ge.dakalebi.ui.Icons as PlayerIcons
import io.github.bchmsl.keel.components.ButtonSize
import io.github.bchmsl.keel.components.ButtonVariant
import io.github.bchmsl.keel.components.DropdownMenu
import io.github.bchmsl.keel.components.DropdownMenuItem
import io.github.bchmsl.keel.components.DropdownSide
import io.github.bchmsl.keel.components.IconButton
import io.github.bchmsl.keel.components.Scrub
import io.github.bchmsl.keel.dom.buttonClasses
import io.github.bchmsl.keel.dom.classNames
import io.github.bchmsl.keel.dom.dropdownAnchorClasses
import io.github.bchmsl.keel.icons.Icon
import io.github.bchmsl.keel.icons.LucideIcon
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.max
import org.jetbrains.compose.web.attributes.min
import org.jetbrains.compose.web.attributes.step
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * [CustomVideoPlayer]'s control bar: the timeline, then the row of transport,
 * volume, time, quality, cast and fullscreen.
 *
 * [ordered] is the renditions best-first; the quality button shows only when there
 * is more than one.
 */
@Composable
internal fun CustomPlayerControls(
    player: CustomPlayerState,
    hidden: Boolean,
    ordered: List<String>,
    quality: String?,
    onQualitySelected: (String) -> Unit,
) {
    val refs = player.refs
    val pctText = "${formatTime(player.currentSec.toDouble())} / ${formatTime(player.durationSec.toDouble())}"

    Div({ classNames("ctl", if (hidden) "hide" else null) }) {
        // The track, the buffered layer, the played fill, the knob, the invisible
        // range input and all three pointer listeners are keel's. What is left
        // here is the one thing only this player knows: where in the video a
        // fraction of the timeline is.
        Scrub(
            ariaLabel = S.timeline,
            onSeek = { fraction ->
                val v = refs.video
                if (v != null && v.duration.isFinite() && v.duration > 0) {
                    v.currentTime = fraction * v.duration
                }
            },
            onHandleReady = { handle -> refs.scrub = handle },
        )

        Div({ classes("ctl-row") }) {
            IconButton(
                ariaLabel = if (player.playing) S.pause else S.play,
                onClick = { player.togglePlay() },
            ) { Icon(if (player.playing) LucideIcon.Pause else LucideIcon.Play, size = ICON_PLAYER_CTL) }

            IconButton(ariaLabel = S.back10, onClick = { player.seekBy(-10.0) }) { PlayerIcon(PlayerIcons.back10) }

            Div({ classes("vol-wrap") }) {
                IconButton(
                    ariaLabel = if (player.muted) S.unmute else S.mute,
                    onClick = { player.toggleMute() },
                ) {
                    Icon(
                        if (player.muted || player.volume == 0.0) LucideIcon.VolumeX else LucideIcon.Volume2,
                        size = ICON_PLAYER_CTL,
                    )
                }

                Div({ classes("vol") }) {
                    Div({ style { property("width", "${if (player.muted) 0.0 else player.volume * 100}%") } })
                    Input(InputType.Range) {
                        min("0"); max("1"); step(0.01)
                        attr("aria-label", S.volume)
                        value(if (player.muted) "0" else player.volume.toString())
                        onInput { event ->
                            player.applyVolume(event.value.toString().toDoubleOrNull() ?: 0.0)
                        }
                    }
                }
            }

            Span({ classes("time", "mono") }) { Text(pctText) }

            Div({ classes("grow") })

            if (ordered.size > 1) {
                QualityMenu(player, ordered, quality, onQualitySelected)
            }

            if (player.castSupported || player.castAvailable) {
                Button({
                    classNames(buttonClasses(ButtonVariant.Ghost, ButtonSize.Icon))
                    attr("aria-label", "Cast")
                    if (player.casting) style { property("color", "var(--red)") }
                    onClick { player.startCast() }
                }) { Icon(LucideIcon.Cast, size = ICON_PLAYER_CTL) }
            }

            IconButton(ariaLabel = S.fullscreen, onClick = { player.toggleFullscreen() }) {
                Icon(
                    if (player.fullscreen) LucideIcon.Minimize else LucideIcon.Maximize,
                    size = ICON_PLAYER_CTL,
                )
            }
        }
    }
}

/** The quality button and, while it is open, its menu of renditions. */
@Composable
private fun QualityMenu(
    player: CustomPlayerState,
    ordered: List<String>,
    quality: String?,
    onQualitySelected: (String) -> Unit,
) {
    // The trigger and the menu now share one positioned wrapper,
    // which is what keel's dropdown resolves against. Before, the menu
    // was a sibling of `.ctl` pinned to `.player` by a hand-measured
    // `right: 14px; bottom: 64px` - the 64 being this control bar's
    // height, so anything that changed the bar moved the menu off it.
    //
    // Nesting it inside `.ctl`, which fades out on the idle timer, is
    // safe only because that timer already refuses to fire while
    // `qualityOpen`: both paths that hide the bar test it. Without
    // those guards an open menu would fade out with the bar under it.
    Div({ classNames(dropdownAnchorClasses()) }) {
        Button({
            // On-media, because it sits on the video frame rather than
            // on the page. Small rather than the default, so it reads as
            // chrome beside the transport buttons instead of competing
            // with them.
            classNames(
                "q-btn",
                "mono",
                buttonClasses(ButtonVariant.OnMedia, ButtonSize.Small),
            )
            attr("aria-label", S.quality)
            // A disclosure, which is what this is: keel's menu is a
            // labelled *group* of buttons and says so, so
            // `aria-haspopup="menu"` would promise a role it does not
            // claim. `aria-expanded` alone is the honest half, and the
            // hand-built version announced neither.
            attr("aria-expanded", player.qualityOpen.toString())
            onClick { player.qualityOpen = !player.qualityOpen }
        }) { Text(quality ?: ordered.first()) }

        if (player.qualityOpen) {
            DropdownMenu(
                onDismiss = { player.qualityOpen = false },
                ariaLabel = S.quality,
                // The bar is pinned to the foot of the video, so a menu
                // hung downward opens off the bottom of the screen.
                side = DropdownSide.Above,
            ) {
                ordered.forEach { label ->
                    DropdownMenuItem(
                        label = label,
                        // The check and `aria-current` are keel's now.
                        // The old markup marked the current rendition
                        // with a "✓" in a `.grow` span and a red `.sel`
                        // class, and announced nothing at all - so
                        // which quality was playing was answerable only
                        // by looking.
                        selected = label == quality,
                        onClick = {
                            player.qualityOpen = false
                            player.holdPositionForQualitySwap()
                            onQualitySelected(label)
                        },
                    )
                }
            }
        }
    }
}

/** Matches `.ctl .ic svg` in web.css. */
private const val ICON_PLAYER_CTL = 24
