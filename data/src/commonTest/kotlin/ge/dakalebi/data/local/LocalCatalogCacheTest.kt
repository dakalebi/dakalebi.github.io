package ge.dakalebi.data.local

import ge.dakalebi.domain.model.Episode
import ge.dakalebi.testing.FakeKeyValueStore
import ge.dakalebi.testing.caveaOnlyEpisode
import ge.dakalebi.testing.episode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cache is what stands between a page load and 932 Firestore reads, and the
 * stamp comparison is the whole of its correctness. These cover that rule and the
 * ways a real device refuses to co-operate.
 */
class LocalCatalogCacheTest {

    private fun cache(store: KeyValueStore) = LocalCatalogCache(store)

    @Test
    fun a_matching_stamp_serves_the_cached_catalog() {
        val store = FakeKeyValueStore()
        val cache = cache(store)
        val episodes = listOf(episode(1, 1), episode(1, 2))

        cache.write(stamp = 100.0, episodes = episodes)

        assertEquals(episodes, cache.read(stamp = 100.0))
    }

    /** An admin refresh bumps the stamp, and that is the only invalidation there is. */
    @Test
    fun a_different_stamp_is_a_miss() {
        val store = FakeKeyValueStore()
        val cache = cache(store)
        cache.write(stamp = 100.0, episodes = listOf(episode(1, 1)))

        assertNull(cache.read(stamp = 101.0))
    }

    /** No `meta/catalog` means nothing can be proven current, so nothing is used. */
    @Test
    fun a_missing_stamp_is_a_miss_on_both_sides() {
        val store = FakeKeyValueStore()
        val cache = cache(store)

        cache.write(stamp = null, episodes = listOf(episode(1, 1)))
        assertTrue(store.entries.isEmpty(), "a stampless catalog must not be stored")

        cache.write(stamp = 100.0, episodes = listOf(episode(1, 1)))
        assertNull(cache.read(stamp = null))
    }

    /**
     * The failure this is really guarding against is a field quietly dropped by
     * the stored shape, which would not fail to compile and would not fail to
     * decode — it would just serve episodes with a missing runtime or no video
     * URL. So every one of the fourteen fields carries a distinct value.
     */
    @Test
    fun every_episode_field_survives_the_round_trip() {
        val store = FakeKeyValueStore()
        val cache = cache(store)
        val original = Episode(
            id = "531",
            formulaEpisodeId = 531,
            formulaSeasonId = 7,
            seasonNumber = 3,
            episodeNumber = 49,
            title = "სერია",
            thumbnailUrl = "https://example.invalid/still.jpg",
            videoUrl = "https://example.invalid/1080.mp4",
            sources = mapOf(
                "1080p" to "https://example.invalid/1080.mp4",
                "360p" to "https://example.invalid/360.mp4",
            ),
            durationSeconds = 1_931,
            episodePageUrl = "https://example.invalid/e/531",
            lastResolvedAtMillis = 1_700_000_000_000.0,
            updatedAtMillis = 1_700_000_001_000.0,
            caveaEpisodeId = "6d5e0a40-3045-11ed-b171-093ad0cab9c3",
        )

        cache.write(stamp = 42.0, episodes = listOf(original))

        assertEquals(listOf(original), cache.read(stamp = 42.0))
    }

    /** Its Formula fields are empty, not zero: zero would make it a Formula episode with id 0. */
    @Test
    fun an_episode_only_cavea_has_survives_the_round_trip() {
        val cache = cache(FakeKeyValueStore())
        val only = caveaOnlyEpisode(19, 4)

        cache.write(stamp = 42.0, episodes = listOf(episode(1, 1), only))

        val read = cache.read(stamp = 42.0)!!.last()
        assertEquals(only, read)
        assertTrue(read.isCaveaOnly)
    }

    @Test
    fun a_corrupt_entry_is_discarded_rather_than_thrown() {
        val store = FakeKeyValueStore()
        store.entries["catalog_cache_v2"] = "{not json"
        val cache = cache(store)

        assertNull(cache.read(stamp = 100.0))
        assertTrue(store.entries.isEmpty(), "an unreadable entry should be cleared, not kept")
    }

    @Test
    fun an_empty_catalog_is_not_cached() {
        val store = FakeKeyValueStore()

        cache(store).write(stamp = 100.0, episodes = emptyList())

        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun storage_that_refuses_to_read_is_a_miss_not_a_crash() {
        assertNull(cache(FakeKeyValueStore(readsFail = true)).read(stamp = 100.0))
    }

    /**
     * A runtime the player measured is patched into the cached copy, so the next
     * load shows it without a Firestore read. The stamp stays: the catalog has
     * not changed, only this device's copy of one episode.
     */
    @Test
    fun a_recorded_duration_patches_the_cached_episode() {
        val store = FakeKeyValueStore()
        val cache = cache(store)
        cache.write(stamp = 100.0, episodes = listOf(episode(1, 1, durationSeconds = null), episode(1, 2)))

        cache.recordDuration(episodeId = "s1e1", durationSeconds = 1_444)

        assertEquals(
            listOf(episode(1, 1, durationSeconds = 1_444), episode(1, 2)),
            cache.read(stamp = 100.0),
        )
    }

    /** Nothing cached, or an episode the copy does not have: nothing to patch, nothing written. */
    @Test
    fun a_duration_for_nothing_cached_writes_nothing() {
        val empty = FakeKeyValueStore()
        cache(empty).recordDuration(episodeId = "s1e1", durationSeconds = 1_444)
        assertTrue(empty.entries.isEmpty())

        val store = FakeKeyValueStore()
        val cache = cache(store)
        cache.write(stamp = 100.0, episodes = listOf(episode(1, 1)))
        val before = store.entries.toMap()

        cache.recordDuration(episodeId = "s9e9", durationSeconds = 1_444)

        assertEquals(before, store.entries)
    }

    /** Private mode and a full quota both land here. It costs reads, not the load. */
    @Test
    fun storage_that_refuses_to_write_costs_only_the_cache() {
        val store = FakeKeyValueStore(writesFail = true)

        cache(store).write(stamp = 100.0, episodes = listOf(episode(1, 1)))

        assertTrue(store.entries.isEmpty())
    }
}
