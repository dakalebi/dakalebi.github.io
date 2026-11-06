package ge.dakalebi.ui.watch

import ge.dakalebi.core.Log
import ge.dakalebi.presentation.Playhead
import ge.dakalebi.ui.player.AirPlayClock
import org.w3c.dom.HTMLVideoElement

/**
 * The mutable, non-Compose half of the watch screen: the element, and the
 * workarounds for this browser's media quirks.
 *
 * Every field here is written from a media event handler, several times a
 * second. Holding them as Compose state would recompose the whole page on each
 * `timeupdate`; holding them in one remembered object keyed on the episode
 * gives the handlers somewhere to write that costs nothing and is reset for
 * free when the episode changes. The rules about what a position means are in
 * [ge.dakalebi.presentation.PlaybackViewModel].
 */
internal class WatchRefs {
    var video: HTMLVideoElement? = null

    // Resume retry loop, for iOS
    var resumeAttempts: Int = 0
    var resumeSeekIssued: Boolean = false
    var resumeTimer: Int? = null

    val airplay = AirPlayClock()
    var airplayTimer: Int? = null
}

/** The element's position, corrected while iOS plays through AirPlay and reports 0:00. */
internal class ElementPlayhead(
    private val video: HTMLVideoElement,
    private val airplay: AirPlayClock,
) : Playhead {
    override val position: Double get() = airplay.currentTime(video)
    override val duration: Double? get() = airplay.duration(video)
}

/** Longest wait for the spot to save before opening driving mode anyway. */
internal const val DRIVE_SAVE_WAIT_MS = 2_000L

/**
 * Assigning `currentTime` throws on an element that is not seekable yet. That
 * is expected during the resume retry loop, but a seek that keeps failing is
 * exactly why someone would report "it never remembers where I was".
 */
internal fun seekTo(video: HTMLVideoElement, target: Double, stage: String) {
    runCatching { video.currentTime = target }
        .onFailure { Log.w("resume", "seek to ${target}s rejected at stage=$stage", it) }
}
