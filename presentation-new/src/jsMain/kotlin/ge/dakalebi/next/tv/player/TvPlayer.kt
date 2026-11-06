package ge.dakalebi.next.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import ge.dakalebi.core.formatTime
import ge.dakalebi.domain.service.orderedQualityLabels
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.Glyph
import ge.dakalebi.next.components.IconButton
import ge.dakalebi.next.components.IconButtonVariant
import ge.dakalebi.next.components.ProgressBar
import ge.dakalebi.next.components.Spinner
import ge.dakalebi.next.components.Switch
import ge.dakalebi.next.player.PlayerEvents
import ge.dakalebi.next.tv.TvConfig
import ge.dakalebi.next.tv.actsAsOption
import ge.dakalebi.next.tv.actsAsOptionGroup
import ge.dakalebi.next.tv.focus.FocusAxis
import ge.dakalebi.next.tv.focus.SpatialNav
import ge.dakalebi.next.tv.focus.focusGroup
import ge.dakalebi.next.tv.focus.focusItem
import ge.dakalebi.next.tv.input.LocalTvInput
import ge.dakalebi.next.tv.ownsPopup
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button as HtmlButton
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The player for a remote: the picture, the chrome over it, and the episode
 * shelf under the chrome.
 *
 * The behaviour is the old TV player's, kept whole: [TvPlayerState] holds the mode and the
 * actions, [playerLayer] says what each key does, [TvPlayerVideo] is the element. In
 * short:
 * - **Idle**, chrome hidden: a direction press only shows the chrome (Up lands on Play,
 *   anything else on the bar, which does not seek on that first press). OK plays or pauses.
 * - **Controls**: Left and Right seek on the bar and move between buttons anywhere else.
 *   The chrome hides after [TvConfig.CONTROLS_HIDE_MS] while playing.
 * - **Scrubbing**: a held press previews a position without moving the picture. OK
 *   commits, Back cancels.
 *
 * The drawing is the design system's on-media buttons, switch and progress bar, with a
 * "from the start" button beside autoplay.
 *
 * [shelf] is the bands under the chrome, one focus group each; the shelf counts them and
 * knows nothing else about them. [upNext] is the card shown in the last
 * [TvConfig.UP_NEXT_WINDOW_SECONDS]; it is never a D-pad stop, so OK keeps meaning play
 * or pause.
 */
@Composable
internal fun TvPlayer(
    src: String,
    sources: Map<String, String>,
    quality: String?,
    onQualitySelected: (String) -> Unit,
    events: PlayerEvents,
    title: String,
    subtitle: String,
    autoplayNext: Boolean,
    onToggleAutoplay: () -> Unit,
    onFromStart: () -> Unit,
    upNext: (@Composable () -> Unit)?,
    shelf: @Composable () -> Unit,
) {
    val input = LocalTvInput.current ?: return
    val player = remember { TvPlayerState() }
    val refs = player.refs
    val ordered = remember(sources) { orderedQualityLabels(sources) }

    DisposableEffect(Unit) {
        val layer = input.push(playerLayer(player))
        // The ring on the bar, so the first press is not spent finding a control.
        val land = window.setTimeout({ refs.container?.let { SpatialNav.ensureFocused(it) } }, 0)
        onDispose {
            window.clearTimeout(land)
            layer.dismiss()
        }
    }

    // The quality menu is its own layer while it is open: the arrows stay in it and Back
    // closes it. It opens on the rendition playing and gives the ring back to its button.
    DisposableEffect(player.qualityOpen) {
        if (!player.qualityOpen) {
            onDispose { }
        } else {
            val layer = input.push(qualityMenuLayer(player))
            val land = window.setTimeout({ player.focusCurrentQuality() }, 0)
            onDispose {
                window.clearTimeout(land)
                layer.dismiss()
                player.focusChromeItem("quality")
            }
        }
    }

    // The shelf follows the ring: its level is the band the ring is in, whatever moved it.
    DisposableEffect(Unit) {
        val unsubscribe = SpatialNav.onFocusChanged { item ->
            val level = player.shelfBands().indexOfFirst { it.contains(item) } + 1
            if (level != player.shelfLevel) player.shelfLevel = level
        }
        onDispose { unsubscribe() }
    }

    // After the recomposition the level caused, so the bands are measured at their new sizes.
    LaunchedEffect(player.shelfLevel, player.mode) { player.applyShelf(player.shelfLevel) }

    DisposableEffect(Unit) { onDispose { player.release() } }

    val controls = player.mode == Mode.Controls
    val durationSec = player.durationSec
    val currentSec = player.currentSec

    Div({
        classes("tv-player")
        ref { element ->
            refs.container = element
            onDispose { refs.container = null }
        }
    }) {
        TvPlayerVideo(player, src, autoPlay = true, events = events)

        if (player.buffering) {
            Div({ classes("tv-player-spinner") }) { Spinner(onMedia = true) }
        }

        // What is playing, top left, with the chrome: the episode, and the series over it.
        Div({
            classes("tv-player-title")
            if (!controls) classes("tv-player-title--hidden")
        }) {
            P({ classes("tv-player-eyebrow") }) { Text(subtitle.caps) }
            H1({ classes("tv-player-heading") }) { Text(title) }
        }

        // The last stretch: what comes next, and with autoplay on, how soon. Not over a
        // raised shelf, whose bands it would cover (the shelf lists the same episode),
        // under the quality menu, which opens over the same corner, or during a scrub,
        // whose readout sits beside it and is the one thing to read then.
        val left = durationSec - currentSec
        val upNextShown = player.shelfLevel == 0 && !player.qualityOpen &&
            player.mode != Mode.Scrubbing &&
            durationSec > 0 && left in 1..TvConfig.UP_NEXT_WINDOW_SECONDS
        if (upNext != null && upNextShown) {
            Div({ classes("tv-upnext") }) {
                upNext()
                if (autoplayNext) {
                    val elapsed = (TvConfig.UP_NEXT_WINDOW_SECONDS - left).coerceIn(0, TvConfig.UP_NEXT_WINDOW_SECONDS)
                    Div({ classes("tv-upnext-bar"); attr("aria-hidden", "true") }) {
                        Div({
                            classes("tv-upnext-fill")
                            style { property("width", "${elapsed * 100.0 / TvConfig.UP_NEXT_WINDOW_SECONDS}%") }
                        })
                    }
                }
            }
        }

        // The scrub readout, the only sign a held press registered before the picture moves.
        player.seekPreview?.let { preview ->
            Div({ classes("tv-seek-osd") }) { Text(preview) }
        }

        TvChrome(player, ordered, quality, onQualitySelected, autoplayNext, onToggleAutoplay, onFromStart)

        // The episode shelf: a window at the bottom with the bands sliding inside it. At
        // rest the first band only peeks; moving the ring into it hides the transport and
        // brings one band into view with the next one peeking. `overflow: hidden`, so the
        // focus engine's scroller leaves it alone and the transform is the one thing
        // moving it.
        Div({
            classes("tv-shelf")
            if (!controls) classes("tv-shelf--hidden")
            if (player.shelfLevel > 0) classes("tv-shelf--raised")
        }) {
            Div({
                classes("tv-shelf-track")
                ref { element ->
                    refs.shelfTrack = element
                    onDispose { refs.shelfTrack = null }
                }
            }) { shelf() }
        }

        // A position to read whenever the chrome is not there. The bar and the readout
        // announce the position; this hairline stays out of the way of a screen reader.
        Div({
            classes("tv-thinbar")
            if (player.mode != Mode.Idle) classes("tv-thinbar--hidden")
            attr("aria-hidden", "true")
        }) {
            ProgressBar(
                value = if (durationSec > 0) currentSec.toDouble() / durationSec else 0.0,
                label = S.timeline,
                thin = true,
            )
        }
    }
}

/**
 * The bar on top and the buttons under it: one Y group holding the bar and an X row, so
 * the engine moves between the two and the player only answers Left and Right on the bar.
 *
 * One X group for the whole row although it reads as three cells: a press cannot leave an
 * X group sideways, so three groups would wall the transport off from its neighbours.
 */
@Composable
private fun TvChrome(
    player: TvPlayerState,
    ordered: List<String>,
    quality: String?,
    onQualitySelected: (String) -> Unit,
    autoplayNext: Boolean,
    onToggleAutoplay: () -> Unit,
    onFromStart: () -> Unit,
) {
    val refs = player.refs
    Div({
        classes("tv-ctl")
        // Opacity, not `visibility`, so the bar can still take the ring in the frame the
        // chrome comes back. Shown while a scrub is open too: the ghost knob on the bar is
        // where it lands. (The old TV player hid the bar then, so its ghost never showed.)
        if (player.mode == Mode.Idle || player.shelfLevel > 0) classes("tv-ctl--hidden")
        focusGroup("player-chrome", FocusAxis.Y)
    }) {
        Div({ classes("tv-ctl-times") }) {
            Span { Text(formatTime(player.currentSec.toDouble())) }
            Span { Text(formatTime(player.durationSec.toDouble())) }
        }

        // A D-pad stop, not decoration: with the ring here Left and Right seek.
        Div({
            classes("tv-scrub")
            focusItem("scrub", entry = true)
            attr("role", "slider")
            attr("aria-label", S.timeline)
            attr("aria-valuemin", "0")
            attr("aria-valuemax", player.durationSec.toString())
            attr("aria-valuenow", player.currentSec.toString())
            attr("aria-valuetext", formatTime(player.currentSec.toDouble()))
        }) {
            Div({ classes("tv-scrub-buf"); ref { el -> refs.fillBuf = el; onDispose { refs.fillBuf = null } } })
            Div({ classes("tv-scrub-cur"); ref { el -> refs.fillCur = el; onDispose { refs.fillCur = null } } })
            Div({ classes("tv-scrub-thumb"); ref { el -> refs.thumb = el; onDispose { refs.thumb = null } } })
            // Where a scrub would land, drawn only while one is open.
            Div({
                classes("tv-scrub-ghost")
                if (player.mode == Mode.Scrubbing) classes("tv-scrub-ghost--on")
                ref { el -> refs.ghost = el; onDispose { refs.ghost = null } }
            })
        }

        Div({ classes("tv-ctl-row"); focusGroup("player-buttons", FocusAxis.X) }) {
            Div({ classes("tv-ctl-start") }) {
                Switch(
                    label = S.autoplayShort,
                    checked = autoplayNext,
                    onCheckedChange = { onToggleAutoplay() },
                    attrs = { classes("tv-ctl-autoplay"); focusItem("autoplay") },
                )
                IconButton(
                    Glyph.SkipBack,
                    label = S.watchFromStart,
                    onClick = onFromStart,
                    variant = IconButtonVariant.OnMedia,
                    attrs = { focusItem("from-start") },
                )
            }

            Div({ classes("tv-ctl-mid") }) {
                IconButton(
                    Glyph.Back10,
                    label = S.back10,
                    onClick = { player.skip(-1) },
                    variant = IconButtonVariant.OnMedia,
                    attrs = { focusItem("back10") },
                )
                IconButton(
                    if (player.playing) Glyph.Pause else Glyph.Play,
                    label = if (player.playing) S.pause else S.play,
                    onClick = player::togglePlay,
                    variant = IconButtonVariant.OnMedia,
                    attrs = { classes("tv-ctl-play"); focusItem("play") },
                )
                IconButton(
                    Glyph.Forward10,
                    label = S.forward10,
                    onClick = { player.skip(1) },
                    variant = IconButtonVariant.OnMedia,
                    attrs = { focusItem("forward10") },
                )
            }

            Div({ classes("tv-ctl-end") }) {
                if (ordered.size > 1) QualityControl(player, ordered, quality, onQualitySelected)
            }
        }
    }
}

/**
 * The rendition playing, as a button, and while it is open the menu of the others,
 * directly above it. The button's name carries the rendition, because that is what it
 * shows.
 */
@Composable
private fun QualityControl(
    player: TvPlayerState,
    ordered: List<String>,
    quality: String?,
    onQualitySelected: (String) -> Unit,
) {
    val refs = player.refs
    val shown = quality ?: ordered.first()

    if (player.qualityOpen) {
        Div({
            classes("tv-quality-menu")
            focusGroup("quality", FocusAxis.Y)
            actsAsOptionGroup(S.quality)
            ref { element ->
                refs.qualityMenu = element
                onDispose { refs.qualityMenu = null }
            }
        }) {
            ordered.forEach { label ->
                HtmlButton({
                    type(ButtonType.Button)
                    classes("tv-quality-item")
                    focusItem("q-$label", entry = label == shown)
                    actsAsOption(selected = label == shown)
                    onClick {
                        player.qualityOpen = false
                        if (label != shown) onQualitySelected(label)
                    }
                }) { Text(label) }
            }
        }
    }

    HtmlButton({
        type(ButtonType.Button)
        classes("icon-button", "icon-button--on-media", "tv-ctl-quality")
        focusItem("quality")
        attr("aria-label", "${S.quality}: $shown")
        ownsPopup(player.qualityOpen)
        onClick { player.qualityOpen = !player.qualityOpen }
    }) { Text(shown) }
}
