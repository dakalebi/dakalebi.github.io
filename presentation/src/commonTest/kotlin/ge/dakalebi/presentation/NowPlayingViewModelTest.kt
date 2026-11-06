package ge.dakalebi.presentation

import ge.dakalebi.data.SyncedSettingsRepository
import ge.dakalebi.data.local.LocalSettingsCache
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.usecase.ChangeUserSetting
import ge.dakalebi.domain.usecase.ObserveUserSettings
import ge.dakalebi.domain.usecase.ResolveEpisodeVideo
import ge.dakalebi.testing.FakeAccountSettings
import ge.dakalebi.testing.FakeKeyValueStore
import ge.dakalebi.testing.FakeProgressRepository
import ge.dakalebi.testing.episode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The web app's one player across screens: which episode is loaded, moving
 * between episodes without losing the spot in the last one, the queue, and the
 * address following the episode while the full player is open.
 *
 * Every playback is attached to one [FakePlayhead], as every episode plays in the
 * page's one media element.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelTest {

    private val e1 = episode(1, 1, id = "e1")
    private val e2 = episode(1, 2, id = "e2")
    private val e3 = episode(2, 1, id = "e3")

    // ----------------------------------------------------------------- play

    @Test
    fun nothing_plays_until_an_episode_is_picked() = runTest {
        val rig = rig()

        assertNull(rig.holder.state.value.episodeId)
        assertNull(rig.holder.state.value.playback)
        assertTrue(rig.holder.queue(rig.holder.state.value).isEmpty())
    }

    @Test
    fun playing_an_episode_loads_it_and_its_playback() = runTest {
        val rig = rig()

        rig.holder.play("e1")

        val state = rig.holder.state.value
        assertEquals("e1", state.episodeId)
        assertNotNull(state.playback)
        assertEquals(e1, rig.holder.episode(state))
        assertEquals(listOf("e1"), rig.created)
    }

    @Test
    fun the_same_episode_again_keeps_its_playback() = runTest {
        val rig = rig()
        rig.holder.play("e1")
        val first = rig.holder.state.value.playback

        rig.holder.play("e1")

        assertSame(first, rig.holder.state.value.playback)
        assertEquals(listOf("e1"), rig.created)
    }

    @Test
    fun another_episode_saves_the_spot_in_the_last_one() = runTest {
        val rig = rig()
        rig.holder.play("e1")
        val first = rig.holder.state.value.playback
        rig.head.position = 600.0

        rig.holder.play("e3")
        runCurrent()

        assertEquals("e3", rig.holder.state.value.episodeId)
        assertNotSame(first, rig.holder.state.value.playback)
        assertEquals(600, rig.progress.stored["e1"]?.progressSeconds)
        assertEquals(false, rig.progress.stored["e1"]?.isWatched)
    }

    @Test
    fun the_last_episode_lets_go_of_the_element() = runTest {
        val rig = rig()
        rig.holder.play("e1")
        val first = rig.holder.state.value.playback!!
        rig.holder.play("e2")
        runCurrent()
        val saves = rig.progress.saves

        // A late `timeupdate` from the element, which now plays e2.
        rig.head.position = 900.0
        first.onTimeUpdate()
        first.onPause()
        runCurrent()

        assertEquals(saves, rig.progress.saves)
        assertNull(rig.progress.stored["e1"], "e2's time is not saved under e1")
    }

    // ---------------------------------------------------------------- route

    @Test
    fun with_the_player_open_the_address_follows_the_episode() = runTest {
        val rig = rig(start = Route.Watch("e1"))
        rig.holder.play("e1")

        rig.holder.play("e2")

        assertEquals(listOf<Route>(Route.Watch("e2")), rig.router.replaced)
        assertTrue(rig.router.went.isEmpty(), "replaced, so Back closes the player")
    }

    @Test
    fun with_the_player_closed_the_address_stays() = runTest {
        val rig = rig(start = Route.Season(1))
        rig.holder.play("e1")

        rig.holder.play("e2")

        assertTrue(rig.router.replaced.isEmpty())
        assertEquals(Route.Season(1), rig.router.current)
    }

    // ----------------------------------------------------------- next, back

    @Test
    fun next_near_the_end_counts_as_watched_and_crosses_seasons() = runTest {
        val rig = rig(start = Route.Watch("e2"))
        rig.holder.play("e2")
        rig.head.position = 1_400.0

        assertTrue(rig.holder.skipToNext())
        runCurrent()

        assertEquals("e3", rig.holder.state.value.episodeId)
        assertEquals(true, rig.progress.stored["e2"]?.isWatched)
        assertEquals(listOf<Route>(Route.Watch("e3")), rig.router.replaced)
        assertEquals(1, rig.progress.saves, "saved once, as watched")
    }

    @Test
    fun next_early_keeps_the_spot() = runTest {
        val rig = rig()
        rig.holder.play("e1")
        rig.head.position = 300.0

        rig.holder.skipToNext()
        runCurrent()

        assertEquals("e2", rig.holder.state.value.episodeId)
        assertEquals(300, rig.progress.stored["e1"]?.progressSeconds)
        assertEquals(false, rig.progress.stored["e1"]?.isWatched)
    }

    @Test
    fun next_on_the_last_episode_stays() = runTest {
        val rig = rig()
        rig.holder.play("e3")

        assertFalse(rig.holder.skipToNext())
        assertEquals("e3", rig.holder.state.value.episodeId)
    }

    @Test
    fun next_with_nothing_playing_does_nothing() = runTest {
        val rig = rig()

        assertFalse(rig.holder.skipToNext())
        assertNull(rig.holder.state.value.episodeId)
    }

    @Test
    fun previous_goes_back_across_seasons_and_keeps_the_spot() = runTest {
        val rig = rig()
        rig.holder.play("e3")
        rig.head.position = 120.0

        assertTrue(rig.holder.skipToPrevious())
        runCurrent()

        assertEquals("e2", rig.holder.state.value.episodeId)
        assertEquals(120, rig.progress.stored["e3"]?.progressSeconds)
    }

    @Test
    fun previous_on_the_first_episode_stays() = runTest {
        val rig = rig()
        rig.holder.play("e1")

        assertFalse(rig.holder.skipToPrevious())
        assertEquals("e1", rig.holder.state.value.episodeId)
    }

    // ---------------------------------------------------------------- queue

    @Test
    fun the_queue_is_what_follows_in_order_across_seasons() = runTest {
        val rig = rig(episodes = listOf(e3, e1, e2))
        rig.holder.play("e1")

        assertEquals(listOf(e2, e3), rig.holder.queue(rig.holder.state.value))
    }

    @Test
    fun the_queue_is_at_most_its_length() = runTest {
        val many = (1..QUEUE_LENGTH + 5).map { episode(1, it, id = "m$it") }
        val rig = rig(episodes = many)
        rig.holder.play("m1")

        val queue = rig.holder.queue(rig.holder.state.value)

        assertEquals(QUEUE_LENGTH, queue.size)
        assertEquals("m2", queue.first().id)
    }

    // ----------------------------------------------------------- stop, close

    @Test
    fun stop_saves_the_spot_and_leaves_the_open_player() = runTest {
        val rig = rig(start = Route.Watch("e1"))
        rig.holder.play("e1")
        rig.head.position = 450.0

        rig.holder.stop()
        runCurrent()

        assertNull(rig.holder.state.value.episodeId)
        assertNull(rig.holder.state.value.playback)
        assertEquals(450, rig.progress.stored["e1"]?.progressSeconds)
        assertEquals(Route.Dashboard, rig.router.current)
    }

    @Test
    fun stop_from_the_mini_player_keeps_the_address() = runTest {
        val rig = rig(start = Route.Seasons)
        rig.holder.play("e1")

        rig.holder.stop()

        assertEquals(Route.Seasons, rig.router.current)
        assertTrue(rig.router.replaced.isEmpty())
    }

    @Test
    fun close_lets_go_without_saving() = runTest {
        val rig = rig(start = Route.Watch("e1"))
        rig.holder.play("e1")
        rig.head.position = 450.0

        rig.holder.close()
        runCurrent()

        assertNull(rig.holder.state.value.episodeId)
        assertEquals(0, rig.progress.saves)
        assertEquals(Route.Watch("e1"), rig.router.current, "the next viewer's holder reads it")
    }

    // ------------------------------------------------------------------ rig

    private class Rig(
        val holder: NowPlayingViewModel,
        val router: RecordingRouter,
        val progress: FakeProgressRepository,
        val head: FakePlayhead,
        val created: List<String>,
    )

    private suspend fun TestScope.rig(
        episodes: List<Episode> = listOf(e1, e2, e3),
        start: Route = Route.Dashboard,
    ): Rig {
        val progress = FakeProgressRepository()
        val source = StubCatalogRepository(episodes)
        val catalog = catalogStore(source, progress)
        catalog.ensureLoaded("u")

        val repository = SyncedSettingsRepository(
            FakeAccountSettings(stored = UserSettings()),
            LocalSettingsCache(FakeKeyValueStore()),
            backgroundScope,
        )
        val settings = SettingsStore(ObserveUserSettings(repository), ChangeUserSetting(repository), backgroundScope)
        val preferences = PreferencesStore(FakePreferences(), backgroundScope)
        val router = RecordingRouter(start)
        val head = FakePlayhead()
        val created = mutableListOf<String>()

        val holder = NowPlayingViewModel(catalog, router) { episodeId ->
            created += episodeId
            PlaybackViewModel(
                episodeId = episodeId,
                viewerId = { "u" },
                catalog = catalog,
                settings = settings,
                preferences = preferences,
                resolveEpisodeVideo = ResolveEpisodeVideo(source),
                router = router,
                maxQuality = null,
                appleMobile = false,
                scope = backgroundScope,
            ).also { it.attach(head) }
        }
        return Rig(holder, router, progress, head, created)
    }
}
