package ge.dakalebi.ui.player

import org.w3c.dom.HTMLVideoElement

/**
 * Everything the watch screen needs to observe. Grouped into one object rather
 * than a dozen parameters so both players can take the identical contract.
 */
class PlayerEvents(
    val onElement: (HTMLVideoElement?) -> Unit = {},
    val onLoadedMetadata: (HTMLVideoElement) -> Unit = {},
    val onLoadedData: (HTMLVideoElement) -> Unit = {},
    val onCanPlay: (HTMLVideoElement) -> Unit = {},
    val onDurationChange: (HTMLVideoElement) -> Unit = {},
    val onTimeUpdate: (HTMLVideoElement) -> Unit = {},
    val onSeeked: (HTMLVideoElement) -> Unit = {},
    val onPlay: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onEnded: () -> Unit = {},
    val onError: () -> Unit = {},
    /** Fires when playback moves to or from an AirPlay target. */
    val onAirPlayChange: (Boolean, HTMLVideoElement) -> Unit = { _, _ -> },
)
