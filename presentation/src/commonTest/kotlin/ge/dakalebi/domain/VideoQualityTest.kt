package ge.dakalebi.domain

import ge.dakalebi.domain.service.COMPACT_SCREEN_MAX_QUALITY
import ge.dakalebi.domain.service.defaultQualityLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which rendition an episode starts in when the viewer has not picked one.
 *
 * Everywhere but a phone that is the best one. A phone starts at or under
 * [COMPACT_SCREEN_MAX_QUALITY], because the 1080p file is the slowest to start. The
 * maps are built out of order on purpose: Firestore does not keep key order.
 */
class VideoQualityTest {

    private fun sources(vararg labels: String): Map<String, String> =
        labels.associateWith { "https://cdn.example/$it.mp4" }

    @Test
    fun without_a_cap_the_best_rendition_wins() {
        assertEquals("1080p", defaultQualityLabel(sources("360p", "1080p", "720p")))
    }

    @Test
    fun a_phone_starts_at_720p_when_there_is_one() {
        assertEquals(
            "720p",
            defaultQualityLabel(sources("360p", "1080p", "720p"), COMPACT_SCREEN_MAX_QUALITY),
        )
    }

    @Test
    fun the_cap_picks_the_best_rendition_under_it() {
        assertEquals("480p", defaultQualityLabel(sources("240p", "1080p", "480p"), 720))
    }

    @Test
    fun when_every_rendition_is_above_the_cap_the_smallest_wins() {
        assertEquals("1080p", defaultQualityLabel(sources("1080p"), 720))
        assertEquals("900p", defaultQualityLabel(sources("1080p", "900p"), 720))
    }

    @Test
    fun under_a_cap_an_unnumbered_rendition_never_beats_a_numbered_one() {
        assertEquals("1080p", defaultQualityLabel(sources("original", "1080p"), 720))
    }

    @Test
    fun an_unnumbered_rendition_is_used_when_it_is_the_only_one() {
        assertEquals("original", defaultQualityLabel(sources("original"), 720))
        assertEquals("original", defaultQualityLabel(sources("original")))
    }

    @Test
    fun no_renditions_means_no_label() {
        assertNull(defaultQualityLabel(emptyMap(), 720))
        assertNull(defaultQualityLabel(emptyMap()))
    }
}
