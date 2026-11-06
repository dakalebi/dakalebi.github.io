package ge.dakalebi.data

import ge.dakalebi.data.local.LocalSettingsCache
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.testing.FakeAccountSettings
import ge.dakalebi.testing.FakeKeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The repository is where the device and the account meet, so these walk the
 * cases that cost a setting in real use: the first sign-in after an upgrade, a
 * change made offline, a read that fails, and two accounts on one device.
 *
 * Each "device" is a [FakeKeyValueStore]; building a second repository over the
 * same one is a page reload. Every repository runs in the test's background
 * scope, which stands in for the app's: the live listener never ends by itself.
 * So the tests move time with `runCurrent`, because `advanceUntilIdle` leaves
 * background work alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncedSettingsRepositoryTest {

    private fun TestScope.repository(device: FakeKeyValueStore, account: FakeAccountSettings) =
        SyncedSettingsRepository(account, LocalSettingsCache(device), backgroundScope)

    private fun TestScope.signIn(repository: SyncedSettingsRepository, uid: String = "u") {
        repository.start(uid)
        runCurrent()
    }

    private operator fun <T : Any> SyncedSettingsRepository.get(setting: Setting<T>): T =
        settings.value[setting] ?: setting.default

    // ---------------------------------------------------------- first sync

    /** The upgrade path for System player, which was per device until now. */
    @Test
    fun the_first_sign_in_uploads_a_choice_made_before_it_synced() = runTest {
        val device = FakeKeyValueStore().apply { entries["use_native_player"] = "0" }
        val account = FakeAccountSettings(stored = UserSettings().with(Setting.AutoplayNext, true))
        val repository = repository(device, account)

        signIn(repository)

        assertEquals(false, account.stored?.get(Setting.UseNativePlayer))
        assertFalse(repository[Setting.UseNativePlayer], "the device keeps its own choice")
        assertTrue(repository[Setting.AutoplayNext], "and takes the account's for the rest")
    }

    /** The same upgrade path for preferred quality, and the empty "no choice" default. */
    @Test
    fun a_quality_picked_before_it_synced_reaches_the_account() = runTest {
        val device = FakeKeyValueStore().apply { entries["preferred_quality"] = "720p" }
        val account = FakeAccountSettings(stored = UserSettings())
        val repository = repository(device, account)
        assertEquals("", repository(FakeKeyValueStore(), account)[Setting.PreferredQuality])

        signIn(repository)

        assertEquals("720p", account.stored?.get(Setting.PreferredQuality))
        assertEquals("720p", repository[Setting.PreferredQuality])
    }

    @Test
    fun the_account_wins_over_this_devices_copy() = runTest {
        val device = FakeKeyValueStore().apply { entries["autoplay_next_episode"] = "0" }
        val account = FakeAccountSettings(stored = UserSettings().with(Setting.AutoplayNext, true))
        val repository = repository(device, account)

        signIn(repository)

        assertTrue(repository[Setting.AutoplayNext])
        assertEquals("1", device.entries["autoplay_next_episode"], "and the copy follows")
        assertTrue(account.writes.isEmpty(), "nothing to upload")
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
        val account = FakeAccountSettings(
            stored = UserSettings().with(Setting.Language, "ka"),
            refused = setOf(Setting.PreferredQuality),
        )
        val before = repository(device, account)
        signIn(before)
        account.writesSucceed = false
        before.set(Setting.Language, "en")
        account.writesSucceed = true
        before.stop()

        val after = repository(device, account)
        signIn(after)

        assertEquals("en", account.stored?.get(Setting.Language), "the owed language reaches the account")
        assertNull(account.stored?.get(Setting.PreferredQuality), "the refused field stays off it")
        account.changeElsewhere(UserSettings().with(Setting.Language, "ka"))
        runCurrent()
        assertEquals("ka", after[Setting.Language], "and the device follows the account again")
    }

    /**
     * A television whose app starts before its Wi-Fi does. Nothing is uploaded
     * over an account that could not be read, and the sync that was owed runs
     * once the listener hears from the server.
     */
    @Test
    fun a_failed_read_uploads_nothing_until_the_server_answers() = runTest {
        val device = FakeKeyValueStore().apply { entries["use_native_player"] = "0" }
        val account = FakeAccountSettings(
            stored = UserSettings().with(Setting.AutoplayNext, true),
            readsFail = true,
        )
        val repository = repository(device, account)

        signIn(repository)
        assertTrue(account.writes.isEmpty())
        assertFalse(repository[Setting.AutoplayNext], "unread, the device copy stands")

        account.readsFail = false
        account.emit(account.stored!!)
        runCurrent()

        assertEquals(UserSettings().with(Setting.UseNativePlayer, false), account.writes.single())
        assertTrue(repository[Setting.AutoplayNext])
    }

    // ------------------------------------------------------------ changes

    @Test
    fun a_change_on_another_device_arrives() = runTest {
        val account = FakeAccountSettings(stored = UserSettings())
        val repository = repository(FakeKeyValueStore(), account)
        signIn(repository)

        account.changeElsewhere(UserSettings().with(Setting.UseNativePlayer, false))
        runCurrent()

        assertFalse(repository[Setting.UseNativePlayer])
    }

    @Test
    fun a_change_here_reaches_the_account() = runTest {
        val account = FakeAccountSettings(stored = UserSettings())
        val repository = repository(FakeKeyValueStore(), account)
        signIn(repository)

        assertTrue(repository.set(Setting.AutoplayNext, true))

        assertEquals(true, account.stored?.get(Setting.AutoplayNext))
    }

    /** The store relies on this to show a change before the account answers. */
    @Test
    fun a_change_is_in_the_settings_before_the_account_answers() = runTest {
        val account = FakeAccountSettings(stored = UserSettings())
        val repository = repository(FakeKeyValueStore(), account)
        signIn(repository)

        launch(start = CoroutineStart.UNDISPATCHED) { repository.set(Setting.AutoplayNext, true) }

        assertTrue(repository[Setting.AutoplayNext])
        assertTrue(account.writes.isEmpty(), "the write has not run yet")
        runCurrent()
        assertEquals(true, account.stored?.get(Setting.AutoplayNext))
    }

    /**
     * The reported symptom: a language picked here, then picked differently on
     * another device, never arrived back. The write ran in the menu's scope, so
     * closing the menu lost the account's answer and left the change marked
     * unconfirmed for good. Writes now run in the repository's own scope.
     */
    @Test
    fun after_a_change_here_a_later_change_elsewhere_still_arrives() = runTest {
        val account = FakeAccountSettings(stored = UserSettings().with(Setting.Language, "ka"))
        val repository = repository(FakeKeyValueStore(), account)
        signIn(repository)

        repository.set(Setting.Language, "en")
        account.changeElsewhere(UserSettings().with(Setting.Language, "ka"))
        runCurrent()

        assertEquals("ka", repository[Setting.Language])
    }

    /** The same symptom from the caller's side: it gives up waiting, the write still settles. */
    @Test
    fun a_caller_that_stops_waiting_does_not_cost_the_confirmation() = runTest {
        val account = FakeAccountSettings(stored = UserSettings().with(Setting.Language, "ka"))
        val repository = repository(FakeKeyValueStore(), account)
        signIn(repository)
        account.answer = CompletableDeferred()

        val menu = launch(start = CoroutineStart.UNDISPATCHED) { repository.set(Setting.Language, "en") }
        runCurrent()
        menu.cancel()
        account.answer?.complete(Unit)
        runCurrent()

        assertEquals("en", account.stored?.get(Setting.Language), "the write still lands")
        account.changeElsewhere(UserSettings().with(Setting.Language, "ka"))
        runCurrent()
        assertEquals("ka", repository[Setting.Language], "and it was confirmed, so the account is followed again")
    }

    /**
     * Firestore here keeps pending writes in memory only. A change made offline,
     * with the page closed before it landed, has to survive the reload and win
     * over the server's older value on the next sync.
     */
    @Test
    fun a_change_that_never_landed_survives_a_reload() = runTest {
        val device = FakeKeyValueStore()
        val account = FakeAccountSettings(stored = UserSettings().with(Setting.AutoplayNext, false))
        val before = repository(device, account)
        signIn(before)
        account.writesSucceed = false

        assertFalse(before.set(Setting.AutoplayNext, true), "the failure is visible")
        assertTrue(before[Setting.AutoplayNext], "and costs the syncing, not the setting")
        before.stop()

        account.writesSucceed = true
        val after = repository(device, account)
        signIn(after)

        assertTrue(after[Setting.AutoplayNext])
        assertEquals(true, account.stored?.get(Setting.AutoplayNext))
    }

    /** A rejected write is reverted by the listener; the device's change must outlast that. */
    @Test
    fun a_snapshot_does_not_undo_a_change_the_account_has_not_confirmed() = runTest {
        val account = FakeAccountSettings(stored = UserSettings().with(Setting.AutoplayNext, false))
        val repository = repository(FakeKeyValueStore(), account)
        signIn(repository)
        account.writesSucceed = false

        repository.set(Setting.AutoplayNext, true)
        account.emit(account.stored!!)
        runCurrent()

        assertTrue(repository[Setting.AutoplayNext])
    }

    // ----------------------------------------------------------- accounts

    /** One account's unsynced change must not overrule the next account's real setting. */
    @Test
    fun an_unconfirmed_change_stays_with_its_account() = runTest {
        val device = FakeKeyValueStore()
        val first = FakeAccountSettings(stored = UserSettings().with(Setting.AutoplayNext, false))
        val firstRepository = repository(device, first)
        signIn(firstRepository, uid = "a")
        first.writesSucceed = false
        firstRepository.set(Setting.AutoplayNext, true)
        firstRepository.stop()

        val second = FakeAccountSettings(stored = UserSettings().with(Setting.AutoplayNext, false))
        val secondRepository = repository(device, second)
        signIn(secondRepository, uid = "b")

        assertFalse(secondRepository[Setting.AutoplayNext])
        assertNull(second.writes.firstOrNull { Setting.AutoplayNext in it })
    }

    /**
     * Left attached, the listener keeps querying a document the signed-out
     * client may no longer read, and would apply what it heard to nobody.
     */
    @Test
    fun signing_out_detaches_the_listener() = runTest {
        val account = FakeAccountSettings(stored = UserSettings())
        val repository = repository(FakeKeyValueStore(), account)
        signIn(repository)
        assertEquals(1, account.snapshots.subscriptionCount.value)

        repository.stop()
        runCurrent()

        assertEquals(0, account.snapshots.subscriptionCount.value)
        account.changeElsewhere(UserSettings().with(Setting.UseNativePlayer, false))
        runCurrent()
        assertTrue(repository[Setting.UseNativePlayer], "nothing heard after sign-out")
    }

    /** A guest, or before sign-in: the change is this device's alone. */
    @Test
    fun with_no_account_a_change_stays_on_this_device() = runTest {
        val device = FakeKeyValueStore()
        val account = FakeAccountSettings(stored = UserSettings())
        val repository = repository(device, account)

        assertTrue(repository.set(Setting.AutoplayNext, true), "nothing to fail")

        assertTrue(repository[Setting.AutoplayNext])
        assertEquals("1", device.entries["autoplay_next_episode"])
        assertTrue(account.writes.isEmpty())
    }

    // ----------------------------------------------------- what a write holds

    /**
     * The reason a write carries only the setting it changes: two devices
     * changing two different settings must not undo each other, and the
     * language picker knows nothing about autoplay.
     */
    @Test
    fun changing_one_setting_leaves_the_others_alone() = runTest {
        val account = FakeAccountSettings(
            stored = UserSettings().with(Setting.Language, "ka").with(Setting.UseNativePlayer, false),
        )
        val repository = repository(FakeKeyValueStore(), account)
        signIn(repository)

        repository.set(Setting.AutoplayNext, true)

        assertEquals(UserSettings().with(Setting.AutoplayNext, true), account.writes.single())
        assertEquals("ka", account.stored?.get(Setting.Language), "autoplay write must not clear the language")
        assertEquals(false, account.stored?.get(Setting.UseNativePlayer))
        assertEquals(true, account.stored?.get(Setting.AutoplayNext))
    }

    /** Absent must stay absent, or a fresh account reads as "autoplay off". */
    @Test
    fun an_untouched_setting_stays_absent() = runTest {
        val account = FakeAccountSettings()
        val repository = repository(FakeKeyValueStore(), account)
        signIn(repository)

        repository.set(Setting.AutoplayNext, true)

        assertNull(account.stored?.get(Setting.Language))
        assertNull(account.stored?.get(Setting.UseNativePlayer))
    }
}
