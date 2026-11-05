package ge.dakalebi.domain

import ge.dakalebi.domain.service.withStartFragment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The source iOS resumes from: the saved position rides on the URL as `#t=`, so the
 * first load already starts there instead of at 0:00.
 */
class ResumeFragmentTest {

    private val url = "https://cdn.example/episode.mp4"

    @Test
    fun the_fragment_carries_the_saved_seconds() {
        assertEquals("$url#t=754", withStartFragment(url, 754))
    }

    /** Matches the watch screen, which does not resume at a second or less either. */
    @Test
    fun nothing_to_resume_leaves_the_url_alone() {
        assertEquals(url, withStartFragment(url, 0))
        assertEquals(url, withStartFragment(url, 1))
    }

    @Test
    fun a_url_that_already_has_a_fragment_is_left_alone() {
        assertEquals("$url#x", withStartFragment("$url#x", 754))
    }
}
