package ge.dakalebi.domain

import ge.dakalebi.domain.service.CatalogQueries
import ge.dakalebi.domain.service.WatchStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogQueriesTest {

    private val catalog = listOf(
        episode(1, 1), episode(1, 2), episode(1, 3),
        episode(2, 1), episode(2, 2),
        episode(3, 1),
    )

    @Test
    fun next_crosses_a_season_boundary() {
        val last = episode(1, 3)
        assertEquals("s2e1", CatalogQueries.next(catalog, last)?.id)
    }

    @Test
    fun next_is_null_at_the_very_end() {
        assertNull(CatalogQueries.next(catalog, episode(3, 1)))
    }

    @Test
    fun previous_comes_back_oldest_first() {
        val ids = CatalogQueries.previous(catalog, episode(2, 2), count = 3).map { it.id }
        assertEquals(listOf("s1e3", "s2e1"), ids.takeLast(2))
        assertEquals(listOf("s1e2", "s1e3", "s2e1"), ids)
    }

    @Test
    fun previous_is_empty_for_the_first_episode() {
        assertEquals(emptyList(), CatalogQueries.previous(catalog, episode(1, 1), count = 4))
    }

    @Test
    fun upcoming_is_capped_and_in_order() {
        val ids = CatalogQueries.upcoming(catalog, episode(1, 1), count = 3).map { it.id }
        assertEquals(listOf("s1e2", "s1e3", "s2e1"), ids)
    }

    // ------------------------------------------------------ continue watching

    @Test
    fun continue_watching_prefers_the_most_recent_unfinished_episode() {
        val progress = mapOf(
            "s1e1" to progress("s1e1", seconds = 1_500, watched = true, at = 300.0),
            "s2e1" to progress("s2e1", seconds = 400, at = 200.0),
            "s1e2" to progress("s1e2", seconds = 700, at = 100.0),
        )
        // s1e1 is newer but finished, so the unfinished s2e1 wins.
        assertEquals("s2e1", CatalogQueries.continueWatching(catalog, progress)?.id)
    }

    /**
     * The five-second floor. Without it, an episode opened by accident and
     * closed two seconds later outranks the one actually being watched.
     */
    @Test
    fun continue_watching_ignores_a_two_second_false_start() {
        val progress = mapOf(
            "s3e1" to progress("s3e1", seconds = 2, at = 900.0),
            "s1e2" to progress("s1e2", seconds = 700, at = 100.0),
        )
        assertEquals("s1e2", CatalogQueries.continueWatching(catalog, progress)?.id)
    }

    @Test
    fun continue_watching_falls_back_to_the_latest_activity_when_all_are_finished() {
        val progress = mapOf(
            "s1e1" to progress("s1e1", seconds = 1_500, watched = true, at = 100.0),
            "s2e2" to progress("s2e2", seconds = 1_500, watched = true, at = 500.0),
        )
        assertEquals("s2e2", CatalogQueries.continueWatching(catalog, progress)?.id)
    }

    @Test
    fun continue_watching_starts_a_new_account_at_the_beginning() {
        assertEquals("s1e1", CatalogQueries.continueWatching(catalog, emptyMap())?.id)
    }

    @Test
    fun continue_watching_is_null_when_there_is_no_catalog() {
        assertNull(CatalogQueries.continueWatching(emptyList(), emptyMap()))
    }

    // ------------------------------------------------------------- season

    @Test
    fun default_season_follows_the_last_thing_watched() {
        val progress = mapOf("s2e2" to progress("s2e2", seconds = 100, at = 50.0))
        assertEquals(2, CatalogQueries.defaultSeason(catalog, progress))
    }

    /** A refresh that drops an episode must not send someone back to season 1. */
    @Test
    fun default_season_skips_progress_for_episodes_no_longer_in_the_catalog() {
        val progress = mapOf(
            "gone" to progress("gone", seconds = 100, at = 900.0),
            "s3e1" to progress("s3e1", seconds = 100, at = 100.0),
        )
        assertEquals(3, CatalogQueries.defaultSeason(catalog, progress))
    }

    @Test
    fun default_season_is_the_newest_for_a_fresh_account() {
        assertEquals(3, CatalogQueries.defaultSeason(catalog, emptyMap()))
    }

    // -------------------------------------------------------------- stats

    @Test
    fun stats_count_watched_and_started_separately() {
        val progress = mapOf(
            "s1e1" to progress("s1e1", seconds = 1_500, watched = true),
            "s1e2" to progress("s1e2", seconds = 700),
            "s1e3" to progress("s1e3", seconds = 0),
        )
        val stats = WatchStats.of(catalog, progress)
        assertEquals(1, stats.watched)
        assertEquals(1, stats.started, "zero seconds is neither watched nor started")
        assertEquals(6, stats.total)
        assertEquals(16, stats.percent)
    }

    @Test
    fun percent_is_zero_rather_than_a_division_by_zero() {
        assertEquals(0, WatchStats.of(emptyList(), emptyMap()).percent)
    }
}
