package ge.dakalebi.ui.player

import ge.dakalebi.presentation.Playhead
import org.w3c.dom.HTMLVideoElement

/**
 * The element's own position, for a player that has nothing to correct: the TV
 * screen and driving mode. The web watch screen corrects for AirPlay instead
 * (`ElementPlayhead`).
 */
internal class VideoPlayhead(private val video: HTMLVideoElement) : Playhead {
    override val position: Double get() = video.currentTime
    override val duration: Double? get() = video.duration.takeIf { it.isFinite() && it > 0 }
}
