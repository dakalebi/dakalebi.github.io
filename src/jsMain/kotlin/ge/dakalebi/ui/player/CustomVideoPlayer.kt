package ge.dakalebi.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import ge.dakalebi.domain.service.orderedQualityLabels
import ge.dakalebi.i18n.S
import io.github.bchmsl.keel.components.ProgressBar
import io.github.bchmsl.keel.components.Spinner
import io.github.bchmsl.keel.components.SpinnerSize
import io.github.bchmsl.keel.dom.classNames
import io.github.bchmsl.keel.icons.Icon
import io.github.bchmsl.keel.icons.LucideIcon
import kotlin.math.roundToInt
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * The web player: the page's own controls over one `<video>`.
 *
 * Split by job. [CustomPlayerState] holds the element handles, the state the chrome
 * draws and the actions; [CustomPlayerVideo] is the element and its media events;
 * [CustomPlayerControls] is the control bar. This composable wires them to the page:
 * the window and document listeners, and the layers over the picture.
 */
@Composable
fun CustomVideoPlayer(
    src: String,
    autoPlay: Boolean,
    sources: Map<String, String>,
    quality: String?,
    onQualitySelected: (String) -> Unit,
    events: PlayerEvents,
    overlay: @Composable () -> Unit = {},
) {
    val player = remember { CustomPlayerState() }
    val refs = player.refs

    // ------------------------------------------------------------- effects

    // Quality swap: remember where we were, then restore once the new file loads.
    DisposableEffect(src) {
        player.started = false
        player.buffering = false
        player.showControls = true
        player.qualityOpen = false
        onDispose { player.stopLoop() }
    }

    DisposableEffect(Unit) {
        val onFs: (Event) -> Unit = {
            val doc = document.asDynamic()
            player.fullscreen = doc.fullscreenElement != null || doc.webkitFullscreenElement != null
        }
        document.addEventListener("fullscreenchange", onFs)
        document.addEventListener("webkitfullscreenchange", onFs)
        onDispose {
            document.removeEventListener("fullscreenchange", onFs)
            document.removeEventListener("webkitfullscreenchange", onFs)
        }
    }

    DisposableEffect(Unit) {
        val onKey: (Event) -> Unit = handler@{ raw ->
            val event = raw as? KeyboardEvent ?: return@handler

            // The rules live in `mapPlayerKey` so the TV player can share them
            // under a policy that claims no arrows. `None` is not swallowed: a
            // key this player did not act on has to reach whatever is focused.
            fun volumeBy(delta: Double) {
                player.applyVolume((refs.video?.volume ?: 1.0) + delta)
                player.flashFeedback(S.volumeFeedback(((refs.video?.volume ?: 0.0) * 100).roundToInt()))
            }

            when (mapPlayerKey(event, PlayerKeyPolicy.Keyboard)) {
                PlayerKeyAction.None -> return@handler
                PlayerKeyAction.TogglePlay -> player.togglePlay()
                PlayerKeyAction.SeekForward -> player.seekBy(10.0)
                PlayerKeyAction.SeekBack -> player.seekBy(-10.0)
                PlayerKeyAction.VolumeUp -> volumeBy(0.05)
                PlayerKeyAction.VolumeDown -> volumeBy(-0.05)
                PlayerKeyAction.ToggleFullscreen -> player.toggleFullscreen()
                PlayerKeyAction.ToggleMute -> {
                    // No element yet: the key is not this player's, so it is not swallowed.
                    if (refs.video == null) return@handler
                    player.toggleMute()
                }
            }
            event.preventDefault()
        }
        window.addEventListener("keydown", onKey)
        onDispose { window.removeEventListener("keydown", onKey) }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // ----------------------------------------------------------------- ui

    val controlsHidden = !player.showControls && player.playing
    // Best-first, computed rather than taken from the map's own order, which
    // is not preserved across a Firestore round trip.
    val ordered = remember(sources) { orderedQualityLabels(sources) }

    Div({
        // `idle` is the state the control bar is hidden in, named on the player itself so the
        // stylesheet can also take the cursor away in fullscreen. Driven from the same value the
        // bar reads, so the pointer and the chrome cannot disagree about whether they are up.
        classNames("player", if (controlsHidden) "idle" else null)
        ref { element ->
            refs.container = element
            onDispose { refs.container = null }
        }
        onMouseMove { player.revealControls() }
        onMouseLeave { player.hideControlsNow() }
    }) {
        CustomPlayerVideo(player, src, autoPlay, events)

        // After the element, so its `ref` finds the video already there.
        CastAvailabilityWatcher(player, events)

        if (!player.started || (!player.playing && !player.buffering)) {
            Div({ classes("player-center"); onClick { player.togglePlay() } }) {
                // The `-1` in the CSS class name is web.css's own optical nudge for
                // a triangle glyph, which sits visually off-centre inside a circle -
                // matching `.player-center .big .ic svg { margin-left: 3px }`.
                Div({ classes("big") }) {
                    Icon(LucideIcon.Play, size = ICON_PLAYER_BIG, className = "player-glyph-nudge")
                }
            }
        }

        if (player.buffering) {
            // The wrapper is layout over the video and stays local; the ring itself is
            // keel's. It also gains a label - the hand-built one announced nothing, so
            // a screen reader user had no way to tell buffering from a stalled player.
            Div({ classes("loading-ring") }) {
                Spinner(SpinnerSize.Large, ariaLabel = S.loading)
            }
        }

        overlay()

        player.feedback?.let {
            Div({ classes("feedback") }) { Text(it) }
        }

        CustomPlayerControls(player, controlsHidden, ordered, quality, onQualitySelected)

        // Always-visible position line: shown precisely when the control bar
        // is not, so there is never a moment without a progress indicator.
        // Driven from the frame loop through `ProgressHandle`, which is what that
        // escape exists for - recomposing sixty times a second to move one element is
        // exactly what a player must not do. `fraction` is therefore a constant here.
        //
        // `aria-hidden`: this duplicates the scrub bar, which is a real range input and
        // stays in the accessibility tree even while the control row is faded out. Two
        // progressbars reporting the same position is noise, not redundancy.
        ProgressBar(
            fraction = 0.0,
            ariaLabel = S.timeline,
            onMedia = true,
            attrs = {
                classNames("thinbar", if (!controlsHidden) "hide" else null)
                attr("aria-hidden", "true")
            },
            onHandleReady = { handle -> refs.thinBar = handle },
        )
    }
}

/** Matches `.player-center .big .ic svg` in web.css. */
private const val ICON_PLAYER_BIG = 30
