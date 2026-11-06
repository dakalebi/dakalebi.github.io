package ge.dakalebi.domain.service

import ge.dakalebi.domain.model.CaveaEpisode
import ge.dakalebi.testing.episode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How Cavea Plus's list joins Formula's catalog.
 *
 * The numbers are the only identity the two share, so the rules worth pinning are
 * what a match keeps, what an episode only one side has becomes, and that the
 * result never depends on more than the order Cavea answered in.
 */
class CatalogMergeTest {

    private fun cavea(season: Int, number: Int, id: String = "c${season}e$number") =
        CaveaEpisode(id = id, seasonNumber = season, episodeNumber = number, thumbnailUrl = "https://cavea.plus/static/images/$id.jpg")

    @Test
    fun a_match_keeps_formulas_episode_and_gains_caveas_id() {
        val formula = episode(1, 1)

        val merged = mergeCavea(listOf(formula), listOf(cavea(1, 1)))

        assertEquals(listOf(formula.copy(caveaEpisodeId = "c1e1")), merged)
        assertFalse(merged.single().isCaveaOnly)
        assertTrue(merged.single().onCavea)
    }

    @Test
    fun an_episode_only_formula_has_is_unchanged() {
        val formula = episode(1, 2)

        val merged = mergeCavea(listOf(formula), listOf(cavea(1, 1)))

        assertEquals(formula, merged.first { it.id == formula.id })
        assertNull(merged.first { it.id == formula.id }.caveaEpisodeId)
    }

    @Test
    fun an_episode_only_cavea_has_becomes_a_cavea_only_episode() {
        val merged = mergeCavea(listOf(episode(1, 1)), listOf(cavea(19, 4, id = "abc")))

        val only = merged.single { it.isCaveaOnly }
        assertEquals("cavea-abc", only.id)
        assertEquals(19, only.seasonNumber)
        assertEquals(4, only.episodeNumber)
        assertEquals("abc", only.caveaEpisodeId)
        assertEquals("https://cavea.plus/static/images/abc.jpg", only.thumbnailUrl)
        assertFalse(only.hasVideo)
        assertNull(only.formulaEpisodeId)
        assertNull(only.episodePageUrl)
        assertTrue(only.sources.isEmpty())
    }

    @Test
    fun the_result_is_in_series_order_across_both_sources() {
        val merged = mergeCavea(
            formula = listOf(episode(2, 1), episode(1, 2)),
            cavea = listOf(cavea(1, 3), cavea(1, 1), cavea(2, 1)),
        )

        assertEquals(
            listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1),
            merged.map { it.seasonNumber to it.episodeNumber },
        )
    }

    @Test
    fun when_cavea_lists_the_same_numbers_twice_the_first_wins() {
        val merged = mergeCavea(
            formula = listOf(episode(1, 1)),
            cavea = listOf(cavea(1, 1, id = "first"), cavea(1, 1, id = "second"), cavea(1, 2, id = "a"), cavea(1, 2, id = "b")),
        )

        assertEquals(listOf("first", "a"), merged.map { it.caveaEpisodeId })
        assertEquals(2, merged.size)
    }

    @Test
    fun without_caveas_list_the_catalog_is_formulas_alone_in_order() {
        val formula = listOf(episode(1, 2), episode(1, 1))

        assertEquals(formula.reversed(), mergeCavea(formula, emptyList()))
    }

    @Test
    fun cavea_only_ids_carry_the_prefix_progress_is_moved_by() {
        assertEquals(CAVEA_ONLY_ID_PREFIX + "x", caveaOnlyId("x"))
    }
}
