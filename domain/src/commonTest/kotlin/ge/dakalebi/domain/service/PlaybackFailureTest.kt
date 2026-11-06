package ge.dakalebi.domain.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the watch screen does after a media error: which message it shows, and
 * whether a retry has anything new to load.
 */
class PlaybackFailureTest {

    private val stored = "https://cdn.example/episode.mp4"
    private val fresh = "https://cdn.example/episode-new.mp4"

    /** Season 2's interlaced files on an iPhone or iPad. */
    @Test
    fun a_rejected_source_on_an_apple_phone_or_tablet_is_unsupported_there() {
        assertEquals(
            PlaybackFailure.UnsupportedOnAppleMobile,
            playbackFailure(MEDIA_ERR_SRC_NOT_SUPPORTED, isAppleMobile = true),
        )
    }

    /** A desktop browser decodes those files, so a rejection there is something else. */
    @Test
    fun a_rejected_source_elsewhere_is_a_plain_load_failure() {
        assertEquals(PlaybackFailure.LoadFailed, playbackFailure(MEDIA_ERR_SRC_NOT_SUPPORTED, isAppleMobile = false))
    }

    @Test
    fun other_errors_on_an_apple_phone_or_tablet_are_plain_load_failures() {
        assertEquals(PlaybackFailure.LoadFailed, playbackFailure(2, isAppleMobile = true))
        assertEquals(PlaybackFailure.LoadFailed, playbackFailure(3, isAppleMobile = true))
        assertEquals(PlaybackFailure.LoadFailed, playbackFailure(null, isAppleMobile = true))
    }

    @Test
    fun a_new_link_is_retried() {
        assertEquals(fresh, retryUrl(failedUrl = stored, freshUrl = fresh))
    }

    /** What Formula's API being down looks like: the stored link comes back. */
    @Test
    fun the_same_link_again_is_not_a_retry() {
        assertNull(retryUrl(failedUrl = stored, freshUrl = stored))
    }

    @Test
    fun no_link_at_all_is_not_a_retry() {
        assertNull(retryUrl(failedUrl = stored, freshUrl = null))
    }
}
