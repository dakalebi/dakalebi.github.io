package ge.dakalebi.ui.player

import ge.dakalebi.app.nowMillis
import org.w3c.dom.HTMLVideoElement
import kotlin.math.max
import kotlin.math.min

/**
 * Works around iOS reporting `currentTime` as 0 while playback is routed to an
 * AirPlay target.
 *
 * The local element keeps the media clock stopped even though the receiver is
 * playing, so the UI would sit at 0:00 and progress would never be saved. We
 * anchor the last believable position against wall-clock time and extrapolate
 * from there, falling back to the native value the moment it becomes sane
 * again.
 */
class AirPlayClock {
    var active: Boolean = false

    private var anchorMedia: Double = 0.0
    private var anchorWall: Double = 0.0
    private var anchorDuration: Double? = null
    private var anchored: Boolean = false

    fun reset() {
        active = false
        anchored = false
        anchorMedia = 0.0
        anchorWall = 0.0
        anchorDuration = null
    }

    /** Re-anchor from whatever the element currently believes. */
    fun sync(video: HTMLVideoElement) {
        val current = if (video.currentTime.isFinite()) video.currentTime else 0.0
        val nativeDuration = if (video.duration.isFinite() && video.duration > 0) video.duration else null
        val duration = nativeDuration ?: anchorDuration

        if (current > 0.25 || duration != null) {
            anchorMedia = current
            anchorWall = nowMillis()
            anchorDuration = duration
            anchored = true
        }
    }

    fun currentTime(video: HTMLVideoElement): Double {
        val native = if (video.currentTime.isFinite()) video.currentTime else 0.0
        if (!active || native > 0.25 || !anchored) return native

        val elapsed = max(0.0, (nowMillis() - anchorWall) / 1000.0)
        val projected = anchorMedia + elapsed
        return anchorDuration?.let { min(projected, it) } ?: projected
    }

    fun duration(video: HTMLVideoElement): Double? {
        val native = if (video.duration.isFinite() && video.duration > 0) video.duration else null
        if (native != null) return native
        return if (active) anchorDuration else null
    }
}
