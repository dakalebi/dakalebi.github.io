package ge.dakalebi.domain

import ge.dakalebi.data.local.LocalSettingsCache
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.usecase.LoadUserSettings
import ge.dakalebi.domain.usecase.ObserveUserSettings
import ge.dakalebi.domain.usecase.SaveUserSettings
import ge.dakalebi.i18n.I18n
import ge.dakalebi.presentation.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store is where the device and the account meet, so these walk the cases
 * that cost a setting in real use: the first sign-in after an upgrade, a change
 * made offline, a read that fails, and two accounts on one device.
 *
 * Each "device" is a [FakeKeyValueStore]; building a second store over the same
 * one is a page reload.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {

    private val off = { error("a sync failure was reported that should not have been") }

    @AfterTest
    fun restoreLanguage() = I18n.use("ka")

    private fun store(device: FakeKeyValueStore, account: FakeSettingsRepository) = SettingsStore(
        loadUserSettings = LoadUserSettings(account),
        observeUserSettings = ObserveUserSettings(account),
        saveUserSettings = SaveUserSettings(account),
        cache = LocalSettingsCache(device),
    )

    private fun TestScope.signIn(store: SettingsStore, uid: String = "u") {
        store.start(this, uid)
        advanceUntilIdle()
    }

    @Test
    fun the_first_frame_uses_this_devices_copy_and_defaults_the_rest() {
        val device = FakeKeyValueStore().apply { entries["autoplay_next_episode"] = "1" }

        val settings = store(device, FakeSettingsRepository())

        assertTrue(settings[Setting.AutoplayNext])
        assertTrue(settings[Setting.UseNativePlayer], "System player defaults to on")
    }

    /** The upgrade path for System player, which was per device until now. */
    @Test
    fun the_first_sign_in_uploads_a_choice_made_before_it_synced() = runTest {
        val device = FakeKeyValueStore().apply { entries["use_native_player"] = "0" }
        val account = FakeSettingsRepository(stored = UserSettings().with(Setting.AutoplayNext, true))
        val settings = store(device, account)

        signIn(settings)

        assertEquals(false, account.stored?.get(Setting.UseNativePlayer))
        assertFalse(settings[Setting.UseNativePlayer], "the device keeps its own choice")
        assertTrue(settings[Setting.AutoplayNext], "and takes the account's for the rest")
    }

    /** The same upgrade path for preferred quality, and the empty "no choice" default. */
    @Test
    fun a_quality_picked_before_it_synced_reaches_the_account() = runTest {
        val device = FakeKeyValueStore().apply { entries["preferred_quality"] = "720p" }
        val account = FakeSettingsRepository(stored = UserSettings())
        val settings = store(device, account)
        assertEquals("", store(FakeKeyValueStore(), account)[Setting.PreferredQuality])

        signIn(settings)

        assertEquals("720p", account.stored?.get(Setting.PreferredQuality))
        assertEquals("720p", settings[Setting.PreferredQuality])
    }

    @Test
    fun the_account_wins_over_this_devices_copy() = runTest {
        val device = FakeKeyValueStore().apply { entries["autoplay_next_episode"] = "0" }
        val account = FakeSettingsRepository(stored = UserSettings().with(Setting.AutoplayNext, true))
        val settings = store(device, account)

        signIn(settings)

        assertTrue(settings[Setting.AutoplayNext])
        assertEquals("1", device.entries["autoplay_next_episode"], "and the copy follows")
        assertTrue(account.writes.isEmpty(), "nothing to upload")
    }

    @Test
    fun a_change_on_another_device_arrives() = runTest {
        val account = FakeSettingsRepository(stored = UserSettings())
        val settings = store(FakeKeyValueStore(), account)
        signIn(settings)

        account.changeElsewhere(UserSettings().with(Setting.UseNativePlayer, false))

        assertFalse(settings[Setting.UseNativePlayer])
    }

    @Test
    fun a_change_here_reaches_the_account() = runTest {
        val account = FakeSettingsRepository(stored = UserSettings())
        val settings = store(FakeKeyValueStore(), account)
        signIn(settings)

        settings.toggle(Setting.AutoplayNext, off)
        advanceUntilIdle()

        assertEquals(true, account.stored?.get(Setting.AutoplayNext))
    }

    /**
     * The reported symptom: a language picked here, then picked differently on
     * another device, never arrived back. The write ran in the menu's scope, so
     * closing the menu lost the account's answer and left the change marked
     * unconfirmed for good. Writes now run in the scope [SettingsStore.start]
     * got, and `set` takes no scope a screen could pass.
     */
    @Test
    fun after_a_change_here_a_later_change_elsewhere_still_arrives() = runTest {
        val account = FakeSettingsRepository(stored = UserSettings().with(Setting.Language, "ka"))
        val settings = store(FakeKeyValueStore(), account)
        signIn(settings)

        settings.set(Setting.Language, "en", off)
        advanceUntilIdle()
        account.changeElsewhere(UserSettings().with(Setting.Language, "ka"))

        assertEquals("ka", settings[Setting.Language])
        assertEquals("ka", I18n.current.tag)
    }

    /**
     * The rules check the whole document, so one field they do not allow yet
     * refuses the write it is in. With every owed setting in one write, a device
     * holding a quality the rules did not allow yet also lost its marked
     * language, on every start, and kept ignoring the account's language.
     */
    @Test
    fun a_field_the_rules_refuse_does_not_hold_back_the_others() = runTest {
        val device = FakeKeyValueStore().apply { entries["preferred_quality"] = "720p" }
        val account = FakeSettingsRepository(
            stored = UserSettings().with(Setting.Language, "ka"),
            refused = setOf(Setting.PreferredQuality),
        )
        val before = store(device, account)
        signIn(before)
        account.writesSucceed = false
        before.set(Setting.Language, "en") {}
        advanceUntilIdle()
        account.writesSucceed = true

        val after = store(device, account)
        signIn(after)

        assertEquals("en", account.stored?.get(Setting.Language), "the owed language reaches the account")
        assertNull(account.stored?.get(Setting.PreferredQuality), "the refused field stays off it")
        account.changeElsewhere(UserSettings().with(Setting.Language, "ka"))
        assertEquals("ka", after[Setting.Language], "and the device follows the account again")
    }

    /**
     * Firestore here keeps pending writes in memory only. A change made offline,
     * with the page closed before it landed, has to survive the reload and win
     * over the server's older value on the next sync.
     */
    @Test
    fun a_change_that_never_landed_survives_a_reload() = runTest {
        val device = FakeKeyValueStore()
        val account = FakeSettingsRepository(stored = UserSettings().with(Setting.AutoplayNext, false))
        val before = store(device, account)
        signIn(before)
        account.writesSucceed = false
        var reported = false

        before.toggle(Setting.AutoplayNext) { reported = true }
        advanceUntilIdle()
        assertTrue(reported, "the failure is visible")
        assertTrue(before[Setting.AutoplayNext], "and costs the syncing, not the setting")

        account.writesSucceed = true
        val after = store(device, account)
        signIn(after)

        assertTrue(after[Setting.AutoplayNext])
        assertEquals(true, account.stored?.get(Setting.AutoplayNext))
    }

    /** A rejected write is reverted by the listener; the device's change must outlast that. */
    @Test
    fun a_snapshot_does_not_undo_a_change_the_account_has_not_confirmed() = runTest {
        val account = FakeSettingsRepository(stored = UserSettings().with(Setting.AutoplayNext, false))
        val settings = store(FakeKeyValueStore(), account)
        signIn(settings)
        account.writesSucceed = false

        settings.toggle(Setting.AutoplayNext) {}
        advanceUntilIdle()
        account.emit(account.stored!!)

        assertTrue(settings[Setting.AutoplayNext])
    }

    /**
     * A television whose app starts before its Wi-Fi does. Nothing is uploaded
     * over an account that could not be read, and the sync that was owed runs
     * once the listener hears from the server.
     */
    @Test
    fun a_failed_read_uploads_nothing_until_the_server_answers() = runTest {
        val device = FakeKeyValueStore().apply { entries["use_native_player"] = "0" }
        val account = FakeSettingsRepository(
            stored = UserSettings().with(Setting.AutoplayNext, true),
            readsFail = true,
        )
        val settings = store(device, account)

        signIn(settings)
        assertTrue(account.writes.isEmpty())
        assertFalse(settings[Setting.AutoplayNext], "unread, the device copy stands")

        account.readsFail = false
        account.emit(account.stored!!)
        advanceUntilIdle()

        assertEquals(UserSettings().with(Setting.UseNativePlayer, false), account.writes.single())
        assertTrue(settings[Setting.AutoplayNext])
    }

    /** One account's unsynced change must not overrule the next account's real setting. */
    @Test
    fun an_unconfirmed_change_stays_with_its_account() = runTest {
        val device = FakeKeyValueStore()
        val first = FakeSettingsRepository(stored = UserSettings().with(Setting.AutoplayNext, false))
        val firstStore = store(device, first)
        signIn(firstStore, uid = "a")
        first.writesSucceed = false
        firstStore.toggle(Setting.AutoplayNext) {}
        advanceUntilIdle()
        firstStore.stop()

        val second = FakeSettingsRepository(stored = UserSettings().with(Setting.AutoplayNext, false))
        val secondStore = store(device, second)
        signIn(secondStore, uid = "b")

        assertFalse(secondStore[Setting.AutoplayNext])
        assertNull(second.writes.firstOrNull { Setting.AutoplayNext in it })
    }

    @Test
    fun the_language_reaches_the_string_catalogue() = runTest {
        val account = FakeSettingsRepository(stored = UserSettings())
        val settings = store(FakeKeyValueStore(), account)
        signIn(settings)

        settings.set(Setting.Language, "en", off)
        advanceUntilIdle()

        assertEquals("en", I18n.current.tag)
        assertEquals("en", account.stored?.get(Setting.Language))
    }

    @Test
    fun the_cached_language_applies_before_sign_in() {
        val device = FakeKeyValueStore().apply { entries["language"] = "en" }

        store(device, FakeSettingsRepository()).applyCachedLanguage()

        assertEquals("en", I18n.current.tag)
    }
}
