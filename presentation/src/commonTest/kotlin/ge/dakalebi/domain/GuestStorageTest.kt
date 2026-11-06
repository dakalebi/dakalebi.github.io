package ge.dakalebi.domain

import ge.dakalebi.data.ViewerProgressRepository
import ge.dakalebi.data.local.KeyValueStore
import ge.dakalebi.data.local.LocalGuestMode
import ge.dakalebi.data.local.LocalProgressRepository
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.Viewer
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.ProgressRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where a guest's progress and guest choice live, and that neither reaches an account. */
class GuestStorageTest {

    private val guest = Viewer.Guest.id

    // ViewerProgressRepository

    @Test
    fun nothing_a_guest_does_reaches_the_account_store() = runTest {
        val member = CallLog()
        val device = CallLog()
        val router = ViewerProgressRepository(member = member, guest = device)

        router.exerciseEveryMethod(guest)

        assertTrue(member.calls.isEmpty(), "account store was asked: ${member.calls}")
        assertEquals(EVERY_METHOD.map { "$it:$guest" }, device.calls)
    }

    @Test
    fun nothing_an_account_does_reaches_the_device_store() = runTest {
        val member = CallLog()
        val device = CallLog()
        val router = ViewerProgressRepository(member = member, guest = device)

        router.exerciseEveryMethod("uid-1")

        assertTrue(device.calls.isEmpty(), "device store was asked: ${device.calls}")
        assertEquals(EVERY_METHOD.map { "$it:uid-1" }, member.calls)
    }

    // LocalProgressRepository

    @Test
    fun device_progress_survives_a_reload() = runTest {
        val store = TestKeyValueStore()
        LocalProgressRepository(store).save(guest, progress("e1", seconds = 600, duration = null, at = 5.0))

        val reloaded = LocalProgressRepository(store).list(guest)

        assertEquals(listOf(progress("e1", seconds = 600, duration = null, at = 5.0)), reloaded)
    }

    @Test
    fun save_reports_the_watched_flag_it_stored() = runTest {
        val device = LocalProgressRepository(TestKeyValueStore())

        assertTrue(device.save(guest, progress("e1", seconds = 1_500, watched = true)))
        assertFalse(device.save(guest, progress("e2", seconds = 10)))
    }

    @Test
    fun owners_are_kept_apart() = runTest {
        val device = LocalProgressRepository(TestKeyValueStore())
        device.save(guest, progress("e1", seconds = 600))
        device.save("other", progress("e2", seconds = 700))

        assertEquals(listOf("e1"), device.list(guest).map { it.episodeId })
        assertEquals(listOf("e2"), device.list("other").map { it.episodeId })
    }

    @Test
    fun removals_and_mark_watched_change_only_what_they_name() = runTest {
        val store = TestKeyValueStore()
        val device = LocalProgressRepository(store)
        listOf("e1", "e2", "e3", "e4").forEach { device.save(guest, progress(it, seconds = 100)) }

        device.delete(guest, "e1")
        device.deleteMany(guest, listOf("e2", "e3"))
        device.markWatched(guest, listOf(episode(1, 5, durationSeconds = 1_200, id = "e5")), nowMillis = 9.0)

        assertEquals(
            setOf(
                progress("e4", seconds = 100),
                WatchProgress("e5", 1_200, 1_200, isWatched = true, lastWatchedAtMillis = 9.0),
            ),
            device.list(guest).toSet(),
        )

        device.deleteAll(guest)
        assertTrue(device.list(guest).isEmpty())
        assertTrue(LocalProgressRepository(store).list(guest).isEmpty(), "the reset must persist")
    }

    @Test
    fun two_tabs_add_to_each_other_instead_of_overwriting() = runTest {
        val store = TestKeyValueStore()
        val first = LocalProgressRepository(store)
        val second = LocalProgressRepository(store)
        first.list(guest)
        second.list(guest)

        first.save(guest, progress("e1", seconds = 600))
        second.save(guest, progress("e2", seconds = 700))

        assertEquals(setOf("e1", "e2"), LocalProgressRepository(store).list(guest).map { it.episodeId }.toSet())
    }

    @Test
    fun unreadable_storage_still_keeps_this_pages_progress() = runTest {
        val device = LocalProgressRepository(TestKeyValueStore(readsFail = true))

        device.save(guest, progress("e1", seconds = 600))

        assertEquals(listOf("e1"), device.list(guest).map { it.episodeId })
    }

    @Test
    fun refused_writes_keep_this_pages_progress_but_nothing_persists() = runTest {
        val store = TestKeyValueStore(writesFail = true)
        val device = LocalProgressRepository(store)

        device.save(guest, progress("e1", seconds = 600))
        device.save(guest, progress("e2", seconds = 700))

        assertEquals(setOf("e1", "e2"), device.list(guest).map { it.episodeId }.toSet())
        assertTrue(LocalProgressRepository(store).list(guest).isEmpty())
    }

    @Test
    fun corrupt_device_progress_is_discarded_and_replaced() = runTest {
        val store = TestKeyValueStore()
        LocalProgressRepository(store).save(guest, progress("e1", seconds = 600))
        store.corruptEverything()

        val device = LocalProgressRepository(store)
        assertTrue(device.list(guest).isEmpty())

        device.save(guest, progress("e2", seconds = 700))
        assertEquals(listOf("e2"), LocalProgressRepository(store).list(guest).map { it.episodeId })
    }

    // LocalGuestMode

    @Test
    fun a_device_is_not_a_guest_until_it_chooses_to_be() {
        assertFalse(LocalGuestMode(TestKeyValueStore()).isGuest())
    }

    @Test
    fun the_guest_choice_survives_a_reload_and_can_be_taken_back() {
        val store = TestKeyValueStore()

        LocalGuestMode(store).setGuest(true)
        assertTrue(LocalGuestMode(store).isGuest())

        LocalGuestMode(store).setGuest(false)
        assertFalse(LocalGuestMode(store).isGuest())
    }

    @Test
    fun storage_that_fails_reads_as_not_a_guest_and_never_throws() {
        assertFalse(LocalGuestMode(TestKeyValueStore(readsFail = true)).isGuest())

        val refusing = LocalGuestMode(TestKeyValueStore(writesFail = true))
        refusing.setGuest(true)
        refusing.setGuest(false)
    }

    private suspend fun ProgressRepository.exerciseEveryMethod(uid: String) {
        list(uid)
        save(uid, progress("e1", seconds = 600))
        delete(uid, "e1")
        markWatched(uid, listOf(episode(1, 1)), nowMillis = 1.0)
        deleteMany(uid, listOf("e1"))
        deleteAll(uid)
    }

    private companion object {
        val EVERY_METHOD = listOf("list", "save", "delete", "markWatched", "deleteMany", "deleteAll")
    }
}

/** Records which methods were called, and for whom. */
private class CallLog : ProgressRepository {
    val calls = mutableListOf<String>()

    override suspend fun list(uid: String): List<WatchProgress> {
        calls += "list:$uid"
        return emptyList()
    }

    override suspend fun save(uid: String, progress: WatchProgress): Boolean {
        calls += "save:$uid"
        return progress.isWatched
    }

    override suspend fun delete(uid: String, episodeId: String) {
        calls += "delete:$uid"
    }

    override suspend fun markWatched(uid: String, episodes: List<Episode>, nowMillis: Double) {
        calls += "markWatched:$uid"
    }

    override suspend fun deleteMany(uid: String, episodeIds: List<String>) {
        calls += "deleteMany:$uid"
    }

    override suspend fun deleteAll(uid: String) {
        calls += "deleteAll:$uid"
    }
}

/** Device storage that can be told to refuse, or to hold garbage. */
private class TestKeyValueStore(
    private val readsFail: Boolean = false,
    private val writesFail: Boolean = false,
) : KeyValueStore {
    private val entries = mutableMapOf<String, String>()

    override fun get(key: String): String? {
        if (readsFail) throw IllegalStateException("storage unreadable")
        return entries[key]
    }

    override fun set(key: String, value: String) {
        if (writesFail) throw IllegalStateException("quota exceeded")
        entries[key] = value
    }

    override fun remove(key: String) {
        if (writesFail) throw IllegalStateException("quota exceeded")
        entries.remove(key)
    }

    fun corruptEverything() {
        entries.keys.forEach { entries[it] = "{not json" }
    }
}
