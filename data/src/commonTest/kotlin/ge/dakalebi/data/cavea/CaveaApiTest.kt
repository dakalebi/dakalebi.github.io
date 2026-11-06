package ge.dakalebi.data.cavea

import ge.dakalebi.domain.model.CaveaEpisode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading Cavea Plus's series answer.
 *
 * The fixture is a trimmed copy of the real one: the same keys, the fields this
 * never reads, and season 19's episodes named "New season" instead of S19E<n>.
 */
class CaveaApiTest {

    private val series = """
        {
          "id": "554b7850-3036-11ed-b171-093ad0cab9c3",
          "name": "ჩემი ცოლის დაქალები",
          "slug": "my-wife's-girlfriends",
          "price": 0,
          "rating": 2.5,
          "episodes": [
            {
              "id": "1ebb18a0-3ad0-11ed-b811-fbe65fc4b7e9",
              "imdb": 7.5,
              "status": "AV",
              "episodeNumber": 1,
              "seasonNumber": 1,
              "movieId": "554b7850-3036-11ed-b171-093ad0cab9c3",
              "introStart": null,
              "duration": null,
              "thumbnail": "/static/images/178004495437186.jpg",
              "name": "S1E1",
              "description": null
            },
            {
              "id": "ff6eb5c0-1b60-11ee-bd2f-9dc0734c11ac",
              "status": "AV",
              "episodeNumber": 1,
              "seasonNumber": 19,
              "thumbnail": "/static/images/178004805711088.jpg",
              "name": "ახალი სეზონი "
            },
            {
              "id": "f8c34360-1db0-11ee-9e90-6be325afb81f",
              "episodeNumber": 2,
              "seasonNumber": 19,
              "thumbnail": null,
              "name": "ახალი სეზონი"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun every_listed_episode_is_read_with_its_numbers_and_an_absolute_still() {
        val episodes = CaveaApi().parseSeries(series)

        assertEquals(
            listOf(
                CaveaEpisode("1ebb18a0-3ad0-11ed-b811-fbe65fc4b7e9", 1, 1, "https://cavea.plus/static/images/178004495437186.jpg"),
                CaveaEpisode("ff6eb5c0-1b60-11ee-bd2f-9dc0734c11ac", 19, 1, "https://cavea.plus/static/images/178004805711088.jpg"),
                CaveaEpisode("f8c34360-1db0-11ee-9e90-6be325afb81f", 19, 2, null),
            ),
            episodes,
        )
    }

    /** The answer for another title must never become this series' catalog. */
    @Test
    fun another_series_is_refused() {
        // The first occurrence is the series' own id; the episodes' movieId follows.
        val other = series.replaceFirst("554b7850-3036-11ed-b171-093ad0cab9c3", "00000000-0000-0000-0000-000000000000")

        assertFailsWith<CaveaApiException> { CaveaApi().parseSeries(other) }
    }

    /** An empty list is a broken answer, not a series with no episodes: the stored one is kept. */
    @Test
    fun an_empty_list_is_refused() {
        assertFailsWith<CaveaApiException> {
            CaveaApi().parseSeries("""{"id": "554b7850-3036-11ed-b171-093ad0cab9c3", "episodes": []}""")
        }
    }

    @Test
    fun the_refresh_asks_for_the_georgian_series_page_with_a_bound() = runTest {
        var asked: Pair<String, Int?>? = null
        val api = CaveaApi(fetch = { url, timeout ->
            asked = url to timeout
            series
        })

        assertEquals(3, api.fetchEpisodes().size)
        assertEquals(
            "https://cavea.plus/api/v1/movies/my-wife%27s-girlfriends?lang=ka" to 15_000,
            asked,
        )
    }

    @Test
    fun the_name_is_checked_against_the_numbers_without_relying_on_it() {
        val named = CaveaEpisodeDto("a", seasonNumber = 1, episodeNumber = 1, name = " S1E1 ")
        val renamed = CaveaEpisodeDto("b", seasonNumber = 19, episodeNumber = 1, name = "ახალი სეზონი")
        val unnamed = CaveaEpisodeDto("c", seasonNumber = 1, episodeNumber = 2)

        assertTrue(named.nameMatchesNumbers)
        assertEquals(false, renamed.nameMatchesNumbers)
        assertEquals(false, unnamed.nameMatchesNumbers)
        assertNull(unnamed.toDomain().thumbnailUrl)
    }

    @Test
    fun a_full_url_is_kept_and_a_site_path_gains_the_host() {
        assertEquals("https://cdn.example/a.jpg", absoluteCaveaUrl("https://cdn.example/a.jpg"))
        assertEquals("https://cavea.plus/static/a.jpg", absoluteCaveaUrl("/static/a.jpg"))
        assertEquals("https://cavea.plus/static/a.jpg", absoluteCaveaUrl("static/a.jpg"))
    }
}
