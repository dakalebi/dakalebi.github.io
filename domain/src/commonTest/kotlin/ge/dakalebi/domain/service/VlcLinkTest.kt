package ge.dakalebi.domain.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The link that hands a video Safari cannot decode to VLC instead. */
class VlcLinkTest {

    private val prefix = "vlc-x-callback://x-callback-url/stream?url="

    /** The shape of a real season 2 link. */
    @Test
    fun a_cdn_link_is_encoded_whole_into_the_stream_action() {
        assertEquals(
            prefix + "https%3A%2F%2Fcdn.formula.ge%2Ftvseries%2Fseries_1%2Fseason_02%2Fepisode_10%2Fab-12_10.mp4",
            vlcStreamUrl("https://cdn.formula.ge/tvseries/series_1/season_02/episode_10/ab-12_10.mp4"),
        )
    }

    /**
     * VLC decodes the value only when it finds an encoded `://` in its first 14
     * characters. A half-encoded link would reach the player still encoded.
     */
    @Test
    fun the_encoded_scheme_separator_sits_where_vlc_looks_for_it() {
        val value = vlcStreamUrl("https://cdn.example/a.mp4").removePrefix(prefix)
        assertTrue(value.take(14).contains("%3A%2F%2F"), value)
    }

    /** Otherwise VLC would split them off as its own parameters. */
    @Test
    fun the_links_own_query_stays_inside_the_url_parameter() {
        assertEquals(
            prefix + "https%3A%2F%2Fcdn.example%2Fa.mp4%3Fv%3D2%26n%3D1",
            vlcStreamUrl("https://cdn.example/a.mp4?v=2&n=1"),
        )
    }

    @Test
    fun non_ascii_is_encoded_as_utf8() {
        assertEquals(prefix + "https%3A%2F%2Fcdn.example%2F%E1%83%90%20b.mp4", vlcStreamUrl("https://cdn.example/ა b.mp4"))
    }
}
