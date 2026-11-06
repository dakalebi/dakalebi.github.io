package ge.dakalebi.domain.service

import kotlin.test.Test
import kotlin.test.assertEquals

/** The link that opens an episode on Cavea Plus's own site. */
class CaveaLinkTest {

    /** The shape the site itself links to, with a real id. */
    @Test
    fun an_episode_opens_on_the_series_watch_page() {
        assertEquals(
            "https://cavea.plus/ka/watch/my-wife%27s-girlfriends?episodeId=1ebb18a0-3ad0-11ed-b811-fbe65fc4b7e9",
            caveaWatchUrl("1ebb18a0-3ad0-11ed-b811-fbe65fc4b7e9"),
        )
    }

    /** An id is Cavea's data; nothing in it may add a parameter or end the query. */
    @Test
    fun the_id_is_encoded_into_its_parameter() {
        assertEquals(
            "$CAVEA_WATCH_PAGE?episodeId=a%26b%3D1%23c",
            caveaWatchUrl("a&b=1#c"),
        )
    }
}
