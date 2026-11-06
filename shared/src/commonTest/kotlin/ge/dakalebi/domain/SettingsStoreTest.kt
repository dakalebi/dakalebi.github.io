package ge.dakalebi.domain

import ge.dakalebi.data.SyncedSettingsRepository
import ge.dakalebi.data.local.LocalSettingsCache
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.usecase.ChangeUserSetting
import ge.dakalebi.domain.usecase.ObserveUserSettings
import ge.dakalebi.domain.usecase.SyncUserSettings
import ge.dakalebi.i18n.I18n
import ge.dakalebi.presentation.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the store adds on top of the settings repository: Compose state that is
 * current at once, the language reaching the string catalogue, and a failed sync
 * reported to the screen. The sync rules themselves are in
 * [SyncedSettingsRepositoryTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {

    private val off = { error("a sync failure was reported that should not have been") }

    @AfterTest
    fun restoreLanguage() = I18n.use("ka")

    private fun TestScope.store(device: FakeKeyValueStore, account: FakeAccountSettings): SettingsStore {
        val repository = SyncedSettingsRepository(account, LocalSettingsCache(device), backgroundScope)
        return SettingsStore(
            observeUserSettings = ObserveUserSettings(repository),
            syncUserSettings = SyncUserSettings(repository),
            changeUserSetting = ChangeUserSetting(repository),
            scope = backgroundScope,
        )
    }

    private fun TestScope.signIn(store: SettingsStore, uid: String = "u") {
        store.start(uid)
        runCurrent()
    }

    @Test
    fun the_first_frame_uses_this_devices_copy_and_defaults_the_rest() = runTest {
        val device = FakeKeyValueStore().apply { entries["autoplay_next_episode"] = "1" }

        val settings = store(device, FakeAccountSettings())

        assertTrue(settings[Setting.AutoplayNext])
        assertTrue(settings[Setting.UseNativePlayer], "System player defaults to on")
    }

    /** A switch that waits for the network to move feels broken. */
    @Test
    fun a_change_is_on_screen_before_set_returns() = runTest {
        val account = FakeAccountSettings(stored = UserSettings())
        val settings = store(FakeKeyValueStore(), account)
        signIn(settings)

        settings.toggle(Setting.AutoplayNext, off)

        assertTrue(settings[Setting.AutoplayNext])
        runCurrent()
        assertEquals(true, account.stored?.get(Setting.AutoplayNext), "and it reaches the account")
    }

    @Test
    fun a_change_on_another_device_reaches_the_screen() = runTest {
        val account = FakeAccountSettings(stored = UserSettings())
        val settings = store(FakeKeyValueStore(), account)
        signIn(settings)

        account.changeElsewhere(UserSettings().with(Setting.UseNativePlayer, false))
        runCurrent()

        assertFalse(settings[Setting.UseNativePlayer])
    }

    /**
     * Failing silently here looks exactly like "my other phone never updates",
     * so the screen hears of it. The change itself stays.
     */
    @Test
    fun a_failed_sync_is_reported_and_keeps_the_change() = runTest {
        val account = FakeAccountSettings(stored = UserSettings().with(Setting.AutoplayNext, false))
        val settings = store(FakeKeyValueStore(), account)
        signIn(settings)
        account.writesSucceed = false
        var reported = false

        settings.toggle(Setting.AutoplayNext) { reported = true }
        runCurrent()

        assertTrue(reported)
        assertTrue(settings[Setting.AutoplayNext])
    }

    @Test
    fun the_language_reaches_the_string_catalogue() = runTest {
        val account = FakeAccountSettings(stored = UserSettings())
        val settings = store(FakeKeyValueStore(), account)
        signIn(settings)

        settings.set(Setting.Language, "en", off)
        runCurrent()

        assertEquals("en", I18n.current.tag)
        assertEquals("en", account.stored?.get(Setting.Language))
    }

    @Test
    fun a_language_picked_on_another_device_reaches_the_string_catalogue() = runTest {
        val account = FakeAccountSettings(stored = UserSettings().with(Setting.Language, "ka"))
        val settings = store(FakeKeyValueStore(), account)
        signIn(settings)

        account.changeElsewhere(UserSettings().with(Setting.Language, "en"))
        runCurrent()

        assertEquals("en", settings[Setting.Language])
        assertEquals("en", I18n.current.tag)
    }

    @Test
    fun the_cached_language_applies_before_sign_in() = runTest {
        val device = FakeKeyValueStore().apply { entries["language"] = "en" }

        store(device, FakeAccountSettings()).applyCachedLanguage()

        assertEquals("en", I18n.current.tag)
    }
}
