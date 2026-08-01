package ge.dakalebi.ui.watch

import ge.dakalebi.core.Log
import ge.dakalebi.ui.player.AirPlayClock
import org.w3c.dom.HTMLVideoElement

/**
 * The mutable, non-Compose half of the watch screen.
 *
 * Every field here is written from a media event handler, several times a
 * second. Holding them as Compose state would recompose the whole page on each
 * `timeupdate`; holding them in one remembered object keyed on the episode
 * gives the handlers somewhere to write that costs nothing and is reset for
 * free when the episode changes.
 */
internal class WatchRefs {
    var video: HTMLVideoElement? = null
    var lastSaved: Double = 0.0
    var retried: Boolean = false
    var navigated: Boolean = false

    // Resume state machine
    var resumeApplied: Boolean = false
    var resumeAttempts: Int = 0
    var resumeSeekIssued: Boolean = false
    var resumeTimer: Int? = null

    val airplay = AirPlayClock()
    var airplayTimer: Int? = null
}

/** Seconds before the end at which the next-episode card appears. */
internal const val PROMPT_WINDOW = 180.0

/** Save at most this often during continuous playback. */
internal const val SAVE_EVERY = 7.0

/**
 * Assigning `currentTime` throws on an element that is not seekable yet. That
 * is expected during the resume retry loop, but a seek that keeps failing is
 * exactly why someone would report "it never remembers where I was".
 */
internal fun seekTo(video: HTMLVideoElement, target: Double, stage: String) {
    runCatching { video.currentTime = target }
        .onFailure { Log.w("resume", "seek to ${target}s rejected at stage=$stage", it) }
}
