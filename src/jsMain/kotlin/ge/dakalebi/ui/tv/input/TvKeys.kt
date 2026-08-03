package ge.dakalebi.ui.tv.input

import ge.dakalebi.ui.tv.focus.Direction
import org.w3c.dom.events.KeyboardEvent

/** What a press means, once the platform's spelling has been sorted out. */
sealed interface Key {
    data class Dir(val direction: Direction) : Key
    data object Select : Key
    data object Back : Key
    data class Media(val action: MediaAction) : Key
    data class Other(val raw: String) : Key
}

enum class MediaAction { PlayPause, Play, Pause, Stop, Next, Previous, Forward, Rewind }

/**
 * One remote, many spellings.
 *
 * Every quirk lives here so nothing downstream has to know about any of them:
 *
 * - **Android TV** delivers D-pad presses as ordinary `ArrowUp`/`Down`/`Left`/
 *   `Right` and centre as `Enter`, for free, provided the WebView has Android
 *   focus. Nothing needs bridging for those.
 * - **Back does not arrive as a key at all on Android.** Chromium synthesises no
 *   JS event for `KEYCODE_BACK`, so the host has to hand it over — see
 *   [TvInput.back]. `Backspace` and `Escape` are here for the desktop browser and
 *   for hosts that do inject something, and 10009 / 461 are Tizen's and webOS's
 *   own Back, which cost two lines and mean this page is not Android-only.
 * - **`keyCode` is the fallback, not the primary.** It is deprecated and correct
 *   more often than `key` on old TV WebViews, several of which report
 *   `key == "Unidentified"`.
 */
fun keyOf(event: KeyboardEvent): Key {
    when (event.key) {
        "ArrowUp", "Up" -> return Key.Dir(Direction.Up)
        "ArrowDown", "Down" -> return Key.Dir(Direction.Down)
        "ArrowLeft", "Left" -> return Key.Dir(Direction.Left)
        "ArrowRight", "Right" -> return Key.Dir(Direction.Right)
        "Enter", " ", "Spacebar" -> return Key.Select
        "Backspace", "Escape", "BrowserBack", "GoBack" -> return Key.Back
        "MediaPlayPause" -> return Key.Media(MediaAction.PlayPause)
        "MediaPlay" -> return Key.Media(MediaAction.Play)
        "MediaPause" -> return Key.Media(MediaAction.Pause)
        "MediaStop" -> return Key.Media(MediaAction.Stop)
        "MediaTrackNext" -> return Key.Media(MediaAction.Next)
        "MediaTrackPrevious" -> return Key.Media(MediaAction.Previous)
        "MediaFastForward" -> return Key.Media(MediaAction.Forward)
        "MediaRewind" -> return Key.Media(MediaAction.Rewind)
    }
    return when (event.keyCode) {
        38 -> Key.Dir(Direction.Up)
        40 -> Key.Dir(Direction.Down)
        37 -> Key.Dir(Direction.Left)
        39 -> Key.Dir(Direction.Right)
        13 -> Key.Select
        8, 27, 166, 10009, 461 -> Key.Back
        179 -> Key.Media(MediaAction.PlayPause)
        176 -> Key.Media(MediaAction.Next)
        177 -> Key.Media(MediaAction.Previous)
        else -> Key.Other(event.key)
    }
}
