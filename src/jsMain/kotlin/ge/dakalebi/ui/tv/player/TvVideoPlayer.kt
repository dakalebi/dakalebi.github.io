package ge.dakalebi.ui.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import ge.dakalebi.domain.service.orderedQualityLabels
import ge.dakalebi.i18n.S
import ge.dakalebi.next.player.PlayerEvents
import ge.dakalebi.next.tv.TvConfig
import ge.dakalebi.next.tv.focus.SpatialNav
import ge.dakalebi.next.tv.input.TvInput
import ge.dakalebi.next.tv.player.Mode
import ge.dakalebi.next.tv.player.TvPlayerState
import ge.dakalebi.next.tv.player.TvPlayerVideo
import ge.dakalebi.next.tv.player.playerLayer
import ge.dakalebi.next.tv.player.qualityMenuLayer
import io.github.bchmsl.keel.components.ProgressBar
import io.github.bchmsl.keel.components.ProgressBarSize
import io.github.bchmsl.keel.dom.classNames
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * The player, for a remote.
 *
 * Not a restyling of the web player. The web one installs a `window` keydown
 * handler that `preventDefault`s all four arrows for its whole lifetime, which on a
 * television means the D-pad cannot move focus while it is mounted — it would
 * freeze the navigation the rest of the UI is built on. This one claims nothing
 * globally: it pushes an input layer and hands presses back when they are not its
 * business.
 *
 * Three modes, because a D-pad has four directions and two jobs:
 *
 * - **Idle**, chrome hidden. *Every* direction press only raises the chrome — see the
 *   speed bump in `onDirection`. OK plays or pauses.
 * - **Controls**. What a horizontal press means depends on which item holds the ring,
 *   not on a flag: on the progress bar it seeks, on a button it moves focus. Vertical
 *   movement between the bar and the button row belongs to the spatial engine, because
 *   the chrome is one `Y` group with a nested `X` row inside it.
 * - **Scrubbing**. Presses accumulate into a preview and the media does not move.
 *   **OK commits, Back cancels**, and Up/Down are swallowed so a stray press cannot
 *   abandon the gesture halfway.
 *
 * Almost none of that is invented here. `PlaybackSeekUi.Client` — the contract
 * Leanback and YouTube both implement — ends a scrub on an explicit decision: confirm
 * seeks to the previewed position, cancel restores the one it started from. Samsung's
 * platform spec is where focus-on-the-bar comes from. The speed bump is YouTube's own
 * December 2025 change. An earlier version of this player committed a seek on a 500ms
 * timer instead, which is a worse design for a reason worth recording: a timer cannot
 * express cancel, because by the time you have seen that you overshot, the seek has
 * already happened.
 *
 * Two places deliberately part company with YouTube, both noted at the code: the
 * chrome layout follows YouTube rather than Leanback, and a committed seek resumes
 * playback rather than leaving the viewer paused.
 *
 * The media handling — the paint loop, the buffered-range scan, the event wiring — is
 * carried over from the web player, which has been exercised against real playback.
 * What is dropped is everything a remote cannot produce: hover reveal, click and
 * double-click, and both `opacity: 0` range inputs, which are focus traps that draw the
 * ring around nothing.
 *
 * Split by job, as the web player is. [TvPlayerState] holds the element handles, the
 * mode and the actions; [playerLayer] is what each key does; [TvPlayerVideo] is the
 * element and its media events; [TvPlayerChrome] is the bar and the button row. This
 * composable pushes the input layers and lays out the picture, the overlays and the
 * episode shelf.
 */
@Composable
fun TvVideoPlayer(
    src: String,
    autoPlay: Boolean,
    sources: Map<String, String>,
    quality: String?,
    onQualitySelected: (String) -> Unit,
    input: TvInput,
    events: PlayerEvents,
    /**
     * What is playing, shown top-left while the chrome is up — YouTube's placement.
     * The player takes it as plain strings rather than an episode so it stays a
     * player; [subtitle] is the quieter second line (the show's name).
     */
    title: String? = null,
    subtitle: String? = null,
    /**
     * The account's "autoplay next" preference and a way to flip it, surfaced as a switch
     * in the control row so it can be changed from the sofa without opening Settings
     * (where it also still lives). Null hides the switch, for a player with no such
     * setting behind it.
     */
    autoplayNext: Boolean? = null,
    onToggleAutoplay: (() -> Unit)? = null,
    /**
     * Extra bands rendered in the shelf below the transport row.
     *
     * A slot rather than parameters, so the player stays a player and does not learn
     * about episodes: the watch screen fills it with the next/previous rails. Each band
     * it supplies should be one focus group; the shelf counts them to decide how far it
     * has been pulled up, and knows nothing else about them.
     */
    chromeExtra: @Composable () -> Unit = {},
    /**
     * The up-next card, shown as an overlay in the final
     * [TvConfig.UP_NEXT_WINDOW_SECONDS] before the end.
     *
     * Deliberately **not** focusable and never focused: it is a prompt, not a control,
     * so pressing OK still pauses rather than jumping to the next episode — the defect
     * the request called out. The manual way to play it is the up-next rail below the
     * chrome, which now lands on the true next episode. Null when there is nothing after
     * this one.
     */
    upNext: (@Composable () -> Unit)? = null,
) {
    val player = remember { TvPlayerState() }
    val refs = player.refs
    val ordered = remember(sources) { orderedQualityLabels(sources) }

    // ---------------------------------------------------------------- input

    DisposableEffect(Unit) {
        val layer = input.push(playerLayer(player))
        // Land the ring on a control so the first press is not spent finding one.
        val land = window.setTimeout({ refs.container?.let { SpatialNav.ensureFocused(it) } }, 0)
        onDispose {
            window.clearTimeout(land)
            layer.dismiss()
        }
    }

    /*
     * The quality menu is its own input layer for as long as it is open (see
     * [qualityMenuLayer]).
     *
     * The ring goes in on open and comes back to the button on close, so the menu never
     * opens with the cursor still sitting outside it. It lands on the rendition that is
     * playing, every time it opens: see [TvPlayerState.focusCurrentQuality].
     */
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

    /*
     * The shelf follows the ring.
     *
     * The engine's own hook, not a DOM `focusin` listener — see the note on
     * [SpatialNav.onFocusChanged]. The ring also moves for reasons that never reach
     * `onDirection`: the layer guardian placing it after a recomposition,
     * `focusChromeItem`, a click. This catches all of them, and the level is a pure
     * function of where the ring ended up.
     */
    DisposableEffect(Unit) {
        val unsubscribe = SpatialNav.onFocusChanged { item ->
            val level = player.shelfBands().indexOfFirst { it.contains(item) } + 1
            if (level != player.shelfLevel) player.shelfLevel = level
        }
        onDispose { unsubscribe() }
    }

    // Runs after the recomposition that `shelfLevel` triggered, so the bands are laid
    // out at their new sizes before their offsets are read.
    LaunchedEffect(player.shelfLevel, player.mode) { player.applyShelf(player.shelfLevel) }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // ------------------------------------------------------------------- ui

    Div({
        classes("tv-player")
        ref { element ->
            refs.container = element
            onDispose { refs.container = null }
        }
    }) {
        TvPlayerVideo(player, src, autoPlay, events)

        if (player.buffering) Div({ classes("tv-spinner") }) { Div() }

        // What is playing, top-left, shown with the chrome. YouTube lifts the title
        // out of the control cluster to here; the episode number is the title and the
        // show's name is the quieter line under it.
        if (title != null) {
            Div({ classNames("tv-player-title", if (player.mode == Mode.Controls) null else "hide") }) {
                Div({ classes("tv-player-title-main") }) { Text(title) }
                subtitle?.let { Div({ classes("tv-player-title-sub") }) { Text(it) } }
            }
        }

        // The up-next prompt, an overlay in the final stretch. Not part of the chrome, so
        // it stays up whether the controls are showing or not, and — see [upNext] — not
        // focusable, so OK still means play/pause. When autoplay is on it also carries a
        // bar that fills as the end (and the automatic jump) approaches.
        val durationSec = player.durationSec
        val currentSec = player.currentSec
        if (upNext != null && durationSec > 0 &&
            (durationSec - currentSec) in 1..TvConfig.UP_NEXT_WINDOW_SECONDS
        ) {
            Div({ classes("tv-upnext") }) {
                upNext()
                if (autoplayNext == true) {
                    val elapsed = (TvConfig.UP_NEXT_WINDOW_SECONDS - (durationSec - currentSec))
                        .coerceIn(0, TvConfig.UP_NEXT_WINDOW_SECONDS)
                    Div({ classes("tv-upnext-bar") }) {
                        Div({
                            style {
                                property(
                                    "width",
                                    "${elapsed * 100.0 / TvConfig.UP_NEXT_WINDOW_SECONDS}%",
                                )
                            }
                        })
                    }
                }
            }
        }

        // The seek readout. Big and central, because it is the only feedback that a
        // press registered before the seek actually happens.
        player.seekPreview?.let {
            Div({ classes("tv-seek-osd", "mono") }) { Text(it) }
        }

        TvPlayerChrome(player, ordered, quality, onQualitySelected, autoplayNext, onToggleAutoplay)

        /*
         * The episode shelf.
         *
         * A window pinned to the bottom of the picture with a column of bands sliding
         * inside it, rather than more rows inside the control bar. The bar used to hold
         * them, and the arithmetic of that was the complaint: bar plus buttons plus two
         * full rails is 430px of a 540px screen, so pausing an episode replaced it with
         * a wall of interface and showed both rails whether you wanted them or not.
         *
         * What YouTube does instead, and what this now does: at rest only the top slice
         * of the first band shows, cut off by the bottom edge, saying "there is more
         * down here" without spending the screen on it. Moving the ring into it hides
         * the transport and pulls exactly one band into view with the next one peeking,
         * and each further step down does the same thing one band lower.
         *
         * `overflow: hidden` on the window, deliberately not `auto`. The engine's
         * scroller only adopts an ancestor whose computed overflow is a real scroll
         * value, so this stays invisible to it and the transform below is the single
         * thing deciding where the bands sit. Two mechanisms both moving this column
         * would fight on every press.
         */
        // `raised` is what earns the shelf its own scrim: at level 0 it is a peek over
        // an episode the viewer is watching and must not dim it, and from level 1 it is
        // what they are actually reading. See `.tv-shelf.raised` in the sheet.
        Div({
            classNames(
                "tv-shelf",
                if (player.mode == Mode.Controls) null else "hide",
                if (player.shelfLevel > 0) "raised" else null,
            )
        }) {
            Div({
                classes("tv-shelf-track")
                ref { element ->
                    refs.shelfTrack = element
                    onDispose { refs.shelfTrack = null }
                }
            }) { chromeExtra() }
        }

        // Always something to read the position from, exactly when the control bar
        // is not there. Never a moment with no indicator at all.
        // A real `fraction` rather than a `ProgressHandle`: this bar already follows
        // recomposition, because `currentSec` is state the chrome reads anyway. The
        // web player takes the handle instead, and for the opposite reason - it writes
        // the position from its own frame loop.
        //
        // `aria-hidden`, as on the web: the scrub bar and the seek readout are what
        // announce a position, and a decorative hairline reciting percentages through
        // a whole episode is noise. `ariaLabel` is still required by the signature, so
        // it stays accurate for anyone who removes the attribute.
        ProgressBar(
            fraction = if (durationSec > 0) currentSec.toDouble() / durationSec else 0.0,
            ariaLabel = S.timeline,
            size = ProgressBarSize.Small,
            onMedia = true,
            attrs = {
                classNames("tv-thinbar", if (player.mode == Mode.Controls) "hide" else null)
                attr("aria-hidden", "true")
            },
        )
    }
}
