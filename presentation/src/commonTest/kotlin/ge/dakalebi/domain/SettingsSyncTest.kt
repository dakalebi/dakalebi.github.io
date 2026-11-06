package ge.dakalebi.domain

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.repository.AdminRepository
import ge.dakalebi.domain.service.SettingsSync
import ge.dakalebi.domain.usecase.CheckAdminRights
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeAdminRepository(private val admins: Set<String>) : AdminRepository {
    override suspend fun isAdmin(uid: String): Boolean = uid in admins
}

class SettingsSyncTest {

    // ---------------------------------------------------------- registry

    /** The key is the stored field name, so two settings sharing one would share a value. */
    @Test
    fun every_setting_has_its_own_key() {
        val keys = Setting.all.map { it.key }
        assertEquals(keys.toSet().size, keys.size)
        assertTrue(keys.containsAll(listOf("language", "autoplayNext", "useNativePlayer", "preferredQuality")))
    }

    // -------------------------------------------------------------- plan

    @Test
    fun the_account_wins_when_it_has_a_value() {
        val plan = SettingsSync.plan(
            remote = UserSettings().with(Setting.AutoplayNext, true),
            local = UserSettings().with(Setting.AutoplayNext, false),
            unsynced = emptySet(),
        )

        assertEquals(UserSettings().with(Setting.AutoplayNext, true), plan.adopt)
        assertTrue(plan.upload.isEmpty())
    }

    /**
     * The migration: System player was per device until now, so the account has
     * never had it. A device that has a choice on record hands it over instead
     * of losing it to the default.
     */
    @Test
    fun a_value_the_account_never_had_is_uploaded_from_this_device() {
        val plan = SettingsSync.plan(
            remote = UserSettings().with(Setting.AutoplayNext, true),
            local = UserSettings().with(Setting.AutoplayNext, true).with(Setting.UseNativePlayer, false),
            unsynced = emptySet(),
        )

        assertEquals(UserSettings().with(Setting.UseNativePlayer, false), plan.upload)
        assertTrue(plan.adopt.isEmpty())
    }

    /** A device that never chose must not push its default over a choice made elsewhere. */
    @Test
    fun a_device_with_no_opinion_uploads_nothing() {
        val plan = SettingsSync.plan(remote = UserSettings(), local = UserSettings(), unsynced = emptySet())

        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.upload.isEmpty())
    }

    /**
     * Changed offline, then the page closed before the write landed. Without this
     * the next load would quietly hand the switch back to the server's older value.
     */
    @Test
    fun a_change_the_account_has_not_confirmed_beats_the_account() {
        val plan = SettingsSync.plan(
            remote = UserSettings().with(Setting.AutoplayNext, false).with(Setting.Language, "en"),
            local = UserSettings().with(Setting.AutoplayNext, true).with(Setting.Language, "ka"),
            unsynced = setOf(Setting.AutoplayNext),
        )

        assertEquals(UserSettings().with(Setting.AutoplayNext, true), plan.upload)
        assertEquals(UserSettings().with(Setting.Language, "en"), plan.adopt, "only the unconfirmed setting is held back")
    }

    @Test
    fun agreement_needs_nothing() {
        val both = UserSettings().with(Setting.AutoplayNext, true).with(Setting.Language, "en")
        val plan = SettingsSync.plan(remote = both, local = both, unsynced = emptySet())

        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.upload.isEmpty())
    }

    // ------------------------------------------------------------- admin

    @Test
    fun admin_rights_come_from_the_roster() = runTest {
        val check = CheckAdminRights(FakeAdminRepository(setOf("owner")))
        assertTrue(check(Account("owner", "a@b.c", emailVerified = false)))
        assertFalse(check(Account("someone-else", "x@y.z", emailVerified = true)))
    }

    /** A verified email is no longer a way in — only the roster is. */
    @Test
    fun a_verified_email_alone_grants_nothing() = runTest {
        val check = CheckAdminRights(FakeAdminRepository(emptySet()))
        assertFalse(check(Account("uid", "owner@example.com", emailVerified = true)))
    }

    @Test
    fun signed_out_is_never_an_admin() = runTest {
        assertFalse(CheckAdminRights(FakeAdminRepository(setOf("owner")))(null))
    }
}
