package ge.dakalebi.next.tv.player

import ge.dakalebi.next.tv.focus.Direction
import ge.dakalebi.next.tv.focus.GROUP_ATTR
import ge.dakalebi.next.tv.focus.ITEM_ATTR
import ge.dakalebi.next.tv.focus.SpatialNav
import ge.dakalebi.next.tv.input.Key
import ge.dakalebi.next.tv.input.MediaAction
import ge.dakalebi.next.tv.input.TvLayer
import org.w3c.dom.HTMLElement

/**
 * The player's input layer: what Back, the D-pad, OK and the media keys do in each
 * [Mode]. `TvVideoPlayer` pushes it for as long as it is on the page.
 */
fun playerLayer(player: TvPlayerState): TvLayer {
    val refs = player.refs
    return TvLayer(
        key = "tv-player",
        root = { refs.container },
        // The player is the screen, not an overlay over it: an unhandled
        // Back has to reach the route table and leave, not silently remove
        // this layer and strand a player nothing is listening for.
        dismissible = false,
        onBack = {
            when {
                player.qualityOpen -> { player.qualityOpen = false; true }
                // Cancel, per the contract: the gesture is abandoned and the
                // position it started from is the one still playing.
                player.mode == Mode.Scrubbing -> { player.cancelScrub(); true }
                // Back out of the shelf before Back out of the chrome. Sending
                // the ring to the transport is the whole action: the level is
                // derived from where it lands, so the bands drop back to a peek
                // on their own.
                player.shelfLevel > 0 -> { player.focusChromeItem("play"); true }
                player.mode == Mode.Controls -> { player.hideControls(); true }
                else -> false
            }
        },
        // Media keys reveal the chrome before acting, so pressing Play on a
        // hidden player shows the controls too. Direction and Select keys are
        // deliberately *not* here: each must see the hidden state itself to act
        // correctly (a direction places the ring by entry, an OK plays rather
        // than firing whatever invisible control held focus), and `onAnyKey`
        // runs first — revealing here would flip the mode out from under them.
        onAnyKey = { key -> if (key is Key.Media) player.revealControls() },
        onDirection = { direction, repeat ->
            val sign = if (direction == Direction.Left) -1 else 1
            when {
                // Swallowed mid-scrub. The contract eats Up and Down in seek
                // mode so a stray press cannot abandon a gesture halfway
                // through, and horizontal presses are the gesture itself.
                player.mode == Mode.Scrubbing -> {
                    if (direction.isHorizontal) player.seekPress(sign, repeat)
                    true
                }

                /*
                 * Entry from the hidden state: reveal, then land the ring by the
                 * direction that opened the chrome. Up lands on the buttons,
                 * Down or a horizontal press on the bar.
                 *
                 * The horizontal case is the speed bump, kept from YouTube's
                 * December 2025 change: a Left/Right press here only *selects*
                 * the bar, it does not seek. The next press seeks, because the
                 * bar then holds the ring and the rule below fires. That is what
                 * stops a remote sat on by accident from scrubbing a running
                 * episode with a single press.
                 */
                player.mode == Mode.Idle -> {
                    player.revealControls()
                    player.focusChromeItem(if (direction == Direction.Up) "play" else "scrub")
                    true
                }

                // Up on the bar dismisses the chrome, matching Back in this
                // state. The bar is the topmost control, so Up has nothing to
                // move to, and "up and away" is the natural gesture for putting
                // the controls down. Checked before the keep-alive reveal below,
                // or the chrome would flash back the instant it was hidden.
                direction == Direction.Up && player.scrubFocused() -> { player.hideControls(); true }

                /*
                 * Up out of the shelf, stated rather than left to the engine.
                 *
                 * The engine picks vertical targets from what is *visible*, and
                 * that is the right rule — a move to something an `overflow`
                 * is painting over lands the ring where nobody can see it, which
                 * is a bug it was fixed for. But it makes Up here a dead end, in
                 * two different ways: at the first band the faded control bar is
                 * no longer above the band, it is underneath it; at any band
                 * after that the band above has been clipped out of the window
                 * entirely. Neither is reachable, and both should be.
                 *
                 * So the shelf names its own target going up. Down needs none of
                 * this: the next band is always partly on screen, which is what
                 * the peek is for.
                 */
                direction == Direction.Up && player.shelfLevel > 0 -> {
                    val above = player.shelfBands().getOrNull(player.shelfLevel - 2)
                        ?.querySelector("[$GROUP_ATTR]") as? HTMLElement
                    val container = refs.container
                    if (above != null && container != null) {
                        SpatialNav.focusInGroup(above, container)
                    } else {
                        player.revealControls()
                        player.focusChromeItem("play")
                    }
                    true
                }

                else -> {
                    // Chrome already shown: any press keeps it alive.
                    player.revealControls()
                    when {
                        // Horizontal seeks on the bar and moves focus on a
                        // button. One rule, decided by which item holds the ring.
                        direction.isHorizontal && player.scrubFocused() -> {
                            player.seekPress(sign, repeat); true
                        }
                        // Everything else is the engine's: horizontal walks the
                        // button row, vertical walks the bands — bar, buttons,
                        // and the next/previous rails below them.
                        else -> false
                    }
                }
            }
        },
        onSelect = { focused ->
            when {
                // Confirm, per the contract.
                player.mode == Mode.Scrubbing -> { player.commitScrub(); true }
                // Chrome hidden: OK reveals and plays or pauses. It must NOT
                // fire whatever control still holds the (invisible) ring —
                // focus now persists while hidden, so without this branch an
                // OK meant to pause would skip ten seconds if Forward-10 had
                // been the last thing focused. Land the ring on Play too.
                player.mode == Mode.Idle -> {
                    player.revealControls()
                    player.focusChromeItem("play")
                    player.togglePlay()
                    true
                }
                // The bar is a slider, not a button; OK on it plays or pauses
                // rather than doing nothing.
                player.scrubFocused() -> { player.togglePlay(); true }
                // A real control has the ring: let the click through.
                player.mode == Mode.Controls && focused?.hasAttribute(ITEM_ATTR) == true -> false
                else -> { player.togglePlay(); true }
            }
        },
        onMedia = { action ->
            when (action) {
                MediaAction.PlayPause -> { player.togglePlay(); true }
                MediaAction.Play -> { refs.video?.play(); true }
                MediaAction.Pause -> { refs.video?.pause(); true }
                // A dedicated transport key is always a discrete skip: it has
                // no auto-repeat contract to lean on, and a remote that has
                // these keys expects them to do something on every press.
                MediaAction.Forward -> { player.skip(1); true }
                MediaAction.Rewind -> { player.skip(-1); true }
                else -> false
            }
        },
    )
}

/**
 * The quality menu's input layer, pushed for as long as the menu is open.
 *
 * That is what makes it a menu rather than a floating panel. `handleDirection`
 * scopes the spatial engine to the top layer's root, so with the menu on the stack
 * every arrow stays inside it and Back closes it — the behaviour every television
 * platform gives a settings popup, and the thing that was missing when the engine
 * was free to walk out of the menu into whatever happened to be nearby on screen.
 */
fun qualityMenuLayer(player: TvPlayerState): TvLayer =
    TvLayer(
        key = "tv-quality",
        root = { player.refs.qualityMenu },
        onBack = { player.qualityOpen = false; true },
    )
