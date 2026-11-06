package ge.dakalebi.data.local

import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.testing.FakeKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The device copy is what the first frame renders, and it is where every value
 * set before the settings synced still lives. These pin the storage format that
 * keeps both true.
 */
class LocalSettingsCacheTest {

    /**
     * The local half of the migration: the keys and encoding the per-device
     * preferences used are read as they are, so nothing set before this change
     * is lost.
     */
    @Test
    fun values_stored_before_settings_synced_are_still_found() {
        val store = FakeKeyValueStore()
        store.entries["language"] = "en"
        store.entries["autoplay_next_episode"] = "1"
        store.entries["use_native_player"] = "0"
        store.entries["preferred_quality"] = "720p"

        val expected = UserSettings()
            .with(Setting.Language, "en")
            .with(Setting.AutoplayNext, true)
            .with(Setting.UseNativePlayer, false)
            .with(Setting.PreferredQuality, "720p")
        assertEquals(expected, LocalSettingsCache(store).read())
    }

    /** An absent key is no opinion, not "off" — the store supplies the default. */
    @Test
    fun an_absent_key_is_no_opinion() {
        assertTrue(LocalSettingsCache(FakeKeyValueStore()).read().isEmpty())
    }

    /**
     * Written back in the old format too, so a bundle from before this change —
     * a rollback, or a tab left open — still reads what this one wrote.
     */
    @Test
    fun values_are_written_where_an_older_bundle_looks() {
        val store = FakeKeyValueStore()
        val values = UserSettings()
            .with(Setting.UseNativePlayer, false)
            .with(Setting.Language, "ka")
            .with(Setting.PreferredQuality, "1080p")

        LocalSettingsCache(store).write(values)

        assertEquals("0", store.entries["use_native_player"])
        assertEquals("ka", store.entries["language"])
        assertEquals("1080p", store.entries["preferred_quality"])
        assertEquals(values, LocalSettingsCache(store).read())
    }

    @Test
    fun an_unconfirmed_change_belongs_to_one_account() {
        val cache = LocalSettingsCache(FakeKeyValueStore())

        cache.markUnsynced("a", Setting.AutoplayNext)
        cache.markUnsynced("a", Setting.Language)

        assertEquals(setOf(Setting.AutoplayNext, Setting.Language), cache.unsynced("a"))
        assertTrue(cache.unsynced("b").isEmpty(), "another account must never upload it")

        cache.markSynced("a", setOf(Setting.AutoplayNext))
        assertEquals(setOf(Setting.Language), cache.unsynced("a"))
    }

    @Test
    fun the_last_confirmation_clears_the_mark_entirely() {
        val store = FakeKeyValueStore()
        val cache = LocalSettingsCache(store)
        cache.markUnsynced("a", Setting.AutoplayNext)

        cache.markSynced("a", setOf(Setting.AutoplayNext))

        assertTrue(store.entries.isEmpty())
    }

    /** Safari private mode throws on storage. That costs the copy, never the app. */
    @Test
    fun blocked_storage_costs_the_copy_and_nothing_else() {
        val blocked = LocalSettingsCache(FakeKeyValueStore(readsFail = true, writesFail = true))

        blocked.write(UserSettings().with(Setting.AutoplayNext, true))
        blocked.markUnsynced("a", Setting.AutoplayNext)

        assertTrue(blocked.read().isEmpty())
        assertTrue(blocked.unsynced("a").isEmpty())
    }
}
