package ge.dakalebi.domain.service

/** `MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED`: the browser could not use the source at all. */
const val MEDIA_ERR_SRC_NOT_SUPPORTED: Int = 4

/** Why a video did not play, which decides the message the watch screen shows. */
enum class PlaybackFailure {
    /**
     * An iPhone or iPad rejected the source outright. All of season 2 is stored as
     * interlaced 1080i H.264, which Safari on iOS and iPadOS cannot decode, while a
     * desktop browser plays it.
     */
    UnsupportedOnAppleMobile,

    /** Anything else: a network failure, a dead link, a decode error midway. */
    LoadFailed,
}

/** Classifies a media error by its `MediaError.code` and the device it happened on. */
fun playbackFailure(mediaErrorCode: Int?, isAppleMobile: Boolean): PlaybackFailure =
    if (isAppleMobile && mediaErrorCode == MEDIA_ERR_SRC_NOT_SUPPORTED) {
        PlaybackFailure.UnsupportedOnAppleMobile
    } else {
        PlaybackFailure.LoadFailed
    }

/**
 * The link to retry a failed video with, or null when there is nothing new to try.
 *
 * The same link again changes nothing: the element keeps its failed source and no
 * new load starts, so a screen that waits for one waits forever. That is the case
 * whenever Formula's API is down (the stored link comes back as the "fresh" one) and
 * whenever the file itself cannot play on the device.
 */
fun retryUrl(failedUrl: String?, freshUrl: String?): String? = freshUrl?.takeIf { it != failedUrl }
