package ge.dakalebi.domain

import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.service.VlcPlaylistItem
import ge.dakalebi.domain.service.vlcOpenUrl
import ge.dakalebi.domain.service.vlcPlaylistUrl
import ge.dakalebi.domain.service.vlcStreamUrl
import ge.dakalebi.i18n.Ka
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The playlist "Open in VLC" hands over: what is queued, under which titles, from when. */
class VlcPlaylistTest {

    @Test
    fun without_the_worker_the_link_is_the_single_file() {
        val current = cdnEpisode(2, 9)
        assertEquals(
            vlcStreamUrl(current.videoUrl!!),
            vlcOpenUrl(current, current.videoUrl!!, listOf(cdnEpisode(2, 10)), null, 60, title, endpoint = null),
        )
    }

    /** The worker refuses links off cdn.formula.ge, so this one never reaches it. */
    @Test
    fun a_current_link_off_the_cdn_keeps_the_single_file() {
        val current = episode(2, 9)
        assertEquals(
            vlcStreamUrl(current.videoUrl!!),
            vlcOpenUrl(current, current.videoUrl!!, listOf(cdnEpisode(2, 10)), null, 60, title, ENDPOINT),
        )
    }

    @Test
    fun queues_the_next_five_in_catalog_order_across_a_season_boundary() {
        val current = cdnEpisode(1, 29)
        val upcoming = listOf(cdnEpisode(1, 30)) + (1..7).map { cdnEpisode(2, it) }

        val sent = sent(vlcOpenUrl(current, current.videoUrl!!, upcoming, null, 0, title, ENDPOINT))

        assertEquals(ENDPOINT, sent.endpoint)
        assertEquals(listOf("S1E29", "S1E30", "S2E1", "S2E2", "S2E3", "S2E4"), sent.items.map { it.title })
    }

    /** The page resolves a fresh link for the episode on screen; the stored one may be stale. */
    @Test
    fun the_current_episode_plays_the_link_the_page_resolved() {
        val current = cdnEpisode(2, 9)
        val fresh = "https://cdn.formula.ge/tvseries/series_1/season_02/episode_9/fresh_9.mp4"

        val sent = sent(vlcOpenUrl(current, fresh, emptyList(), null, 0, title, ENDPOINT))

        assertEquals(listOf(VlcPlaylistItem(fresh, "S2E9")), sent.items)
    }

    /** Season 15's fifth episode is filed under `episode_54`. */
    @Test
    fun titles_carry_the_catalog_numbers_not_the_file_path() {
        val current = cdnEpisode(15, 4)
        val fifthUrl = "https://cdn.formula.ge/tvseries/series_1/season_15/episode_54/c3_(5).mp4"
        val fifth = episode(15, 5).copy(videoUrl = fifthUrl, sources = mapOf("1080p" to fifthUrl))

        val sent = sent(
            vlcOpenUrl(current, current.videoUrl!!, listOf(fifth), null, 0, { Ka.seasonAndEpisode(it.seasonNumber, it.episodeNumber) }, ENDPOINT),
        )

        assertEquals(VlcPlaylistItem(fifthUrl, "სეზონი 15 · სერია 5"), sent.items[1])
    }

    @Test
    fun next_episodes_play_in_the_watched_quality_where_they_have_it() {
        val current = cdnEpisode(2, 9)
        val withIt = cdnEpisode(2, 10)
        val bestOnly = cdnEpisode(2, 11).let { it.copy(sources = mapOf("1080p" to it.videoUrl!!)) }

        val sent = sent(vlcOpenUrl(current, current.sources.getValue("720p"), listOf(withIt, bestOnly), "720p", 0, title, ENDPOINT))

        assertEquals(
            listOf(current.sources.getValue("720p"), withIt.sources.getValue("720p"), bestOnly.videoUrl),
            sent.items.map { it.url },
        )
    }

    /** One refused link would fail the whole playlist at the worker. */
    @Test
    fun episodes_without_a_video_or_off_the_cdn_are_left_out() {
        val current = cdnEpisode(2, 9)
        val noVideo = episode(2, 10).copy(videoUrl = null, sources = emptyMap())
        val elsewhere = episode(2, 11)

        val sent = sent(vlcOpenUrl(current, current.videoUrl!!, listOf(noVideo, elsewhere, cdnEpisode(2, 12)), null, 0, title, ENDPOINT))

        assertEquals(listOf("S2E9", "S2E12"), sent.items.map { it.title })
    }

    /** What the watch page passes when autoplay is off. */
    @Test
    fun no_upcoming_episodes_queues_the_current_one_alone() {
        val current = cdnEpisode(2, 9)

        val sent = sent(vlcOpenUrl(current, current.videoUrl!!, emptyList(), null, 754, title, ENDPOINT))

        assertEquals(listOf("S2E9"), sent.items.map { it.title })
        assertEquals(754, sent.start)
    }

    @Test
    fun the_start_is_sent_from_one_second() {
        val current = cdnEpisode(2, 9)
        assertNull(sent(vlcOpenUrl(current, current.videoUrl!!, emptyList(), null, 0, title, ENDPOINT)).start)
        assertEquals(1, sent(vlcOpenUrl(current, current.videoUrl!!, emptyList(), null, 1, title, ENDPOINT)).start)
    }

    /**
     * Character for character. `workers/vlc-playlist/test` decodes this same link
     * the way VLC does and checks the worker's answer, so the two sides agree on the
     * format. The raw spaces are how Formula names some season 16 files.
     */
    @Test
    fun the_link_the_worker_test_reads() {
        val items = listOf(
            VlcPlaylistItem(
                "https://cdn.formula.ge/tvseries/series_1/season_16/episode_01/78d9_CHCD SE16 E 1 HD.mp4",
                "სეზონი 16 · სერია 1",
            ),
            VlcPlaylistItem("https://cdn.formula.ge/tvseries/series_1/season_16/episode_02/a1_2.mp4", "სეზონი 16 · სერია 2"),
        )
        assertEquals(CONTRACT_LINK, vlcPlaylistUrl(ENDPOINT, items, 754))
    }

    // ------------------------------------------------------------- helpers

    private val title: (Episode) -> String = { "S${it.seasonNumber}E${it.episodeNumber}" }

    private fun cdnEpisode(season: Int, number: Int): Episode {
        val base = "https://cdn.formula.ge/tvseries/series_1/season_$season/episode_$number/"
        return episode(season, number).copy(
            videoUrl = "${base}1080_$number.mp4",
            sources = mapOf("1080p" to "${base}1080_$number.mp4", "720p" to "${base}720_$number.mp4"),
        )
    }

    private data class Sent(val endpoint: String, val start: Int?, val items: List<VlcPlaylistItem>)

    /** What the worker receives from [link], decoded the way VLC and then the worker do. */
    private fun sent(link: String): Sent {
        val prefix = "vlc-x-callback://x-callback-url/stream?url="
        assertTrue(link.startsWith(prefix), link)
        val value = link.removePrefix(prefix)
        // VLC splits its own query on raw `&` and `=`, and decodes the value only
        // when it finds an encoded `://` in its first 14 characters.
        assertFalse(value.contains('&') || value.contains('='), value)
        assertTrue(value.take(14).contains("%3A%2F%2F"), value)

        val workerLink = percentDecode(value)
        val pairs = workerLink.substringAfter('?').split('&').map { it.substringBefore('=') to percentDecode(it.substringAfter('=')) }
        val urls = pairs.filter { it.first == "u" }.map { it.second }
        val titles = pairs.filter { it.first == "t" }.map { it.second }
        assertEquals(urls.size, titles.size)
        return Sent(
            endpoint = workerLink.substringBefore('?'),
            start = pairs.firstOrNull { it.first == "start" }?.second?.toInt(),
            items = urls.zip(titles) { url, title -> VlcPlaylistItem(url, title) },
        )
    }

    private fun percentDecode(text: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '%') {
                bytes += text.substring(i + 1, i + 3).toInt(16).toByte()
                i += 3
            } else {
                bytes += text[i].toString().encodeToByteArray().toList()
                i += 1
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private companion object {
        const val ENDPOINT = "https://dakalebi-vlc.example.workers.dev/playlist.m3u"

        const val CONTRACT_LINK = "vlc-x-callback://x-callback-url/stream?url=" +
            "https%3A%2F%2Fdakalebi-vlc.example.workers.dev%2Fplaylist.m3u%3Fstart%3D754" +
            "%26u%3Dhttps%253A%252F%252Fcdn.formula.ge%252Ftvseries%252Fseries_1%252Fseason_16%252Fepisode_01" +
            "%252F78d9_CHCD%2520SE16%2520E%25201%2520HD.mp4" +
            "%26t%3D%25E1%2583%25A1%25E1%2583%2594%25E1%2583%2596%25E1%2583%259D%25E1%2583%259C%25E1%2583%2598" +
            "%252016%2520%25C2%25B7%2520%25E1%2583%25A1%25E1%2583%2594%25E1%2583%25A0%25E1%2583%2598%25E1%2583%2590%25201" +
            "%26u%3Dhttps%253A%252F%252Fcdn.formula.ge%252Ftvseries%252Fseries_1%252Fseason_16%252Fepisode_02%252Fa1_2.mp4" +
            "%26t%3D%25E1%2583%25A1%25E1%2583%2594%25E1%2583%2596%25E1%2583%259D%25E1%2583%259C%25E1%2583%2598" +
            "%252016%2520%25C2%25B7%2520%25E1%2583%25A1%25E1%2583%2594%25E1%2583%25A0%25E1%2583%2598%25E1%2583%2590%25202"
    }
}
