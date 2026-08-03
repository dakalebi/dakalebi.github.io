package ge.dakalebi.domain

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.model.SettingsLoad
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.repository.AdminRepository
import ge.dakalebi.domain.repository.SettingsRepository
import ge.dakalebi.domain.usecase.ChangeAutoplay
import ge.dakalebi.domain.usecase.CheckAdminRights
import ge.dakalebi.domain.usecase.SeedSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In-memory settings document with the same merge semantics as Firestore:
 * a null field means "no opinion", so it leaves what is already stored.
 */
private class FakeSettingsRepository(
    var state: SettingsLoad = SettingsLoad.Missing,
    private val writesSucceed: Boolean = true,
) : SettingsRepository {
    var stored = UserSettings()
        private set

    override suspend fun load(uid: String): SettingsLoad = state

    override suspend fun save(uid: String, settings: UserSettings): Boolean {
        if (!writesSucceed) return false
        stored = UserSettings(
            language = settings.language ?: stored.language,
            autoplayNext = settings.autoplayNext ?: stored.autoplayNext,
        )
        return true
    }

    override fun observe(uid: String, onChange: (UserSettings) -> Unit): () -> Unit = {}
}

private class FakeAdminRepository(private val admins: Set<String>) : AdminRepository {
    override suspend fun isAdmin(uid: String): Boolean = uid in admins
}

class SettingsSyncTest {

    /**
     * The reason [SettingsRepository.save] takes a whole [UserSettings] with
     * nullable fields rather than one value: two devices changing two
     * different settings must not undo each other, and the language picker
     * knows nothing about autoplay.
     */
    @Test
    fun changing_one_setting_leaves_the_other_alone() = runTest {
        val repo = FakeSettingsRepository()
        SeedSettings(repo)("u", UserSettings(language = "ka", autoplayNext = true))

        ChangeAutoplay(repo)("u", false)

        assertEquals("ka", repo.stored.language, "autoplay write must not clear the language")
        assertEquals(false, repo.stored.autoplayNext)
    }

    @Test
    fun seeding_records_what_this_device_is_already_using() = runTest {
        val repo = FakeSettingsRepository()
        SeedSettings(repo)("u", UserSettings(language = "en", autoplayNext = true))

        assertEquals("en", repo.stored.language)
        assertEquals(true, repo.stored.autoplayNext)
    }

    @Test
    fun a_rejected_write_is_reported_rather_than_swallowed() = runTest {
        val repo = FakeSettingsRepository(writesSucceed = false)
        assertFalse(ChangeAutoplay(repo)("u", true))
    }

    /** Absent must stay absent, or a fresh account reads as "autoplay off". */
    @Test
    fun an_untouched_setting_stays_null() = runTest {
        val repo = FakeSettingsRepository()
        ChangeAutoplay(repo)("u", true)
        assertNull(repo.stored.language)
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
