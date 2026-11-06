package ge.dakalebi.presentation

import ge.dakalebi.data.SyncedSettingsRepository
import ge.dakalebi.data.local.LocalSettingsCache
import ge.dakalebi.domain.model.Catalog
import ge.dakalebi.domain.model.CatalogMeta
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.CatalogRepository
import ge.dakalebi.domain.service.MEDIA_ERR_SRC_NOT_SUPPORTED
import ge.dakalebi.domain.usecase.ChangeUserSetting
import ge.dakalebi.domain.usecase.ClearEpisodeProgress
import ge.dakalebi.domain.usecase.LoadCatalog
import ge.dakalebi.domain.usecase.LoadProgress
import ge.dakalebi.domain.usecase.MarkSeasonWatched
import ge.dakalebi.domain.usecase.ObserveUserSettings
import ge.dakalebi.domain.usecase.RecordEpisodeDuration
import ge.dakalebi.domain.usecase.RefreshCatalog
import ge.dakalebi.domain.usecase.ResetAllProgress
import ge.dakalebi.domain.usecase.ResetSeasonProgress
import ge.dakalebi.domain.usecase.ResolveEpisodeVideo
import ge.dakalebi.domain.usecase.SaveProgress
import ge.dakalebi.domain.usecase.SyncUserSettings
import ge.dakalebi.testing.FakeAccountSettings
import ge.dakalebi.testing.FakeClock
import ge.dakalebi.testing.FakeKeyValueStore
import ge.dakalebi.testing.FakeProgressRepository
import ge.dakalebi.testing.episode
import ge.dakalebi.testing.progress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The playback rules the watch screen used to hold inline: which file plays,
 * when a position is saved and what it means, the one retry, the resume target,
 * the next-episode card and leaving for the next episode.
 *
 * The media element is a [FakePlayhead] the test moves by hand. What iOS needs
 * on top (the resume retry loop, the AirPlay clock) is the web screen's and is
 * not here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelTest {

    private val renditions = mapOf(
        "1080p" to "https://example.invalid/e1-1080.mp4",
        "720p" to "https://example.invalid/e1-720.mp4",
        "480p" to "https://example.invalid/e1-480.mp4",
    )
    private val e1 = episode(1, 1, id = "e1").copy(videoUrl = renditions["1080p"], sources = renditions)
    private val e2 = episode(1, 2, id = "e2")

    // ---------------------------------------------------------- which file

    @Test
    fun a_phone_starts_at_720p_and_a_computer_at_the_best() = runTest {
        val computer = rig()
        val phone = rig(maxQuality = 720)

        computer.vm.resolve(e1)
        phone.vm.resolve(e1)

        assertEquals("1080p", computer.vm.state.value.quality)
        assertEquals(renditions["1080p"], computer.vm.state.value.videoUrl)
        assertEquals("720p", phone.vm.state.value.quality)
        assertEquals(renditions["720p"], phone.vm.state.value.videoUrl, "the file follows the label")
    }

    @Test
    fun a_quality_picked_on_any_device_wins() = runTest {
        val rig = rig(preferred = "480p", maxQuality = 720)

        rig.vm.resolve(e1)

        assertEquals("480p", rig.vm.state.value.quality)
        assertEquals(renditions["480p"], rig.vm.state.value.videoUrl)
    }

    @Test
    fun a_picked_quality_this_episode_lacks_falls_back_to_the_default() = runTest {
        val rig = rig(preferred = "360p")

        rig.vm.resolve(e1)

        assertEquals("1080p", rig.vm.state.value.quality)
    }

    @Test
    fun the_fresh_links_reach_the_catalog() = runTest {
        val fresh = renditions.mapValues { (_, url) -> "$url?fresh" }
        val rig = rig(resolve = { it.copy(sources = fresh) })

        rig.vm.resolve(e1)

        assertEquals(fresh, rig.catalog.byId("e1")?.sources)
        assertEquals(fresh, rig.vm.state.value.sources, "the menu reads the same map as the label")
        assertFalse(rig.vm.state.value.resolving)
    }

    @Test
    fun no_link_at_all_is_an_error() = runTest {
        val bare = e1.copy(videoUrl = null, sources = emptyMap())
        val rig = rig(episodes = listOf(bare, e2))

        rig.vm.resolve(bare)

        assertNull(rig.vm.state.value.videoUrl)
        assertEquals(PlaybackError.NoLink, rig.vm.state.value.error)
    }

    @Test
    fun picking_a_quality_switches_the_file_clears_the_error_and_becomes_the_preference() = runTest {
        val rig = rig()
        rig.vm.resolve(e1)
        rig.vm.onMediaError(code = 2)
        rig.vm.onMediaError(code = 2)
        runCurrent()

        rig.vm.selectQuality("480p") { error("the preference should have synced") }
        runCurrent()

        assertEquals("480p", rig.vm.state.value.quality)
        assertEquals(renditions["480p"], rig.vm.state.value.videoUrl)
        assertNull(rig.vm.state.value.error)
        assertEquals("480p", rig.settings[Setting.PreferredQuality])
    }

    // ------------------------------------------- the stored links (TV, Drive)

    @Test
    fun the_stored_links_play_at_once_without_asking_formula() = runTest {
        var asked = 0
        val rig = rig(maxQuality = 720, resolve = { asked++; it })

        rig.vm.startFrom(e1)

        assertEquals("720p", rig.vm.state.value.quality)
        assertEquals(renditions["720p"], rig.vm.state.value.videoUrl)
        assertEquals(renditions, rig.vm.state.value.sources)
        assertEquals(0, asked)
    }

    @Test
    fun the_stored_links_follow_a_picked_quality() = runTest {
        val rig = rig(preferred = "480p")

        rig.vm.startFrom(e1)

        assertEquals("480p", rig.vm.state.value.quality)
        assertEquals(renditions["480p"], rig.vm.state.value.videoUrl)
    }

    @Test
    fun an_episode_without_renditions_plays_its_one_link() = runTest {
        val single = e1.copy(videoUrl = "https://example.invalid/e1.mp4", sources = emptyMap())
        val rig = rig(episodes = listOf(single, e2))

        rig.vm.startFrom(single)

        assertNull(rig.vm.state.value.quality)
        assertEquals("https://example.invalid/e1.mp4", rig.vm.state.value.videoUrl)
    }

    @Test
    fun the_fresh_links_replace_the_stored_ones_in_the_same_quality() = runTest {
        val fresh = renditions.mapValues { (_, url) -> "$url?fresh" }
        val rig = rig(maxQuality = 720, resolve = { it.copy(sources = fresh) })
        rig.vm.startFrom(e1)

        rig.vm.resolve(e1)

        assertEquals("720p", rig.vm.state.value.quality)
        assertEquals("${renditions["720p"]}?fresh", rig.vm.state.value.videoUrl)
    }

    /** A pick synced from another device must not relabel, or restart, the stream playing here. */
    @Test
    fun the_picked_quality_is_read_once_per_episode() = runTest {
        val rig = rig(preferred = "480p")
        rig.vm.startFrom(e1)

        rig.settings.set(Setting.PreferredQuality, "720p") { error("the preference should have synced") }
        runCurrent()
        rig.vm.resolve(e1)

        assertEquals("480p", rig.vm.state.value.quality)
        assertEquals(renditions["480p"], rig.vm.state.value.videoUrl)
    }

    @Test
    fun a_quality_picked_before_the_fresh_links_arrive_is_kept() = runTest {
        val rig = rig()
        rig.vm.startFrom(e1)

        rig.vm.selectQuality("480p") { error("the preference should have synced") }
        rig.vm.resolve(e1)

        assertEquals("480p", rig.vm.state.value.quality)
        assertEquals(renditions["480p"], rig.vm.state.value.videoUrl)
    }

    // --------------------------------------------------------------- retry

    @Test
    fun a_failed_file_is_retried_once_in_the_quality_that_was_playing() = runTest {
        val rig = rig(maxQuality = 720)
        rig.vm.resolve(e1)
        rig.resolver = { it.copy(sources = renditions.mapValues { (_, url) -> "$url?fresh" }) }

        assertTrue(rig.vm.onMediaError(code = 2), "the first failure retries")
        runCurrent()

        assertEquals("${renditions["720p"]}?fresh", rig.vm.state.value.videoUrl, "not the fresh 1080p")
        assertNull(rig.vm.state.value.error)

        assertFalse(rig.vm.onMediaError(code = 2), "the second one does not")
        assertEquals(PlaybackError.LoadFailed, rig.vm.state.value.error)
    }

    /** The same link again starts no new load, so nothing would ever report the failure. */
    @Test
    fun a_retry_with_nothing_new_to_try_fails_at_once() = runTest {
        val rig = rig(appleMobile = true)
        rig.vm.resolve(e1)

        assertTrue(rig.vm.onMediaError(code = MEDIA_ERR_SRC_NOT_SUPPORTED))
        runCurrent()

        assertEquals(PlaybackError.UnsupportedOnAppleMobile, rig.vm.state.value.error)
    }

    // -------------------------------------------------------------- saving

    @Test
    fun a_position_is_saved_every_seven_seconds_of_playback() = runTest {
        val rig = rig()
        rig.play(1.0, 6.9)
        assertEquals(0, rig.progress.saves)

        rig.play(7.0, 10.0)
        assertEquals(1, rig.progress.saves)
        assertEquals(7, rig.progress.stored["e1"]?.progressSeconds)

        rig.play(14.2)
        assertEquals(2, rig.progress.saves)
        assertEquals(14, rig.progress.stored["e1"]?.progressSeconds)
    }

    @Test
    fun under_a_second_in_nothing_is_saved_unless_it_is_a_decision() = runTest {
        val rig = rig()
        rig.head.position = 0.4

        assertNull(rig.vm.save())
        rig.vm.markWatched()
        runCurrent()

        assertEquals(true, rig.progress.stored["e1"]?.isWatched)
    }

    @Test
    fun a_jump_restarts_the_save_clock() = runTest {
        val rig = rig()
        rig.play(7.0)
        assertEquals(1, rig.progress.saves)

        rig.vm.jumpedTo(600.0)
        rig.play(600.5, 606.9)
        assertEquals(1, rig.progress.saves, "a skip forward does not save at once")
        rig.play(607.0)
        assertEquals(2, rig.progress.saves)
        assertEquals(607, rig.progress.stored["e1"]?.progressSeconds)

        rig.vm.jumpedTo(100.0)
        rig.play(107.0)
        assertEquals(3, rig.progress.saves, "a skip back does not wait to pass the old spot")
        assertEquals(107, rig.progress.stored["e1"]?.progressSeconds)
    }

    /** TV and driving mode save this way. Only the web remembers playing or paused. */
    @Test
    fun saving_without_the_media_events_leaves_the_play_intent_alone() = runTest {
        val rig = rig(playIntent = "paused")
        rig.head.position = 300.0

        rig.vm.save()
        rig.vm.markWatched()
        runCurrent()

        assertEquals("paused", rig.prefs.intents["e1"])
        assertEquals(true, rig.progress.stored["e1"]?.isWatched)
    }

    @Test
    fun without_a_viewer_or_an_element_nothing_is_saved() = runTest {
        val guestless = rig(viewer = null)
        guestless.head.position = 60.0
        assertNull(guestless.vm.save())

        val detached = rig()
        detached.head.position = 60.0
        detached.vm.attach(null)
        assertNull(detached.vm.save())
    }

    @Test
    fun pausing_saves_and_remembers_the_episode_was_paused() = runTest {
        val rig = rig()
        rig.vm.onPlay()
        assertEquals("playing", rig.prefs.intents["e1"])

        rig.head.position = 42.0
        rig.vm.onPause()
        runCurrent()

        assertEquals("paused", rig.prefs.intents["e1"])
        assertEquals(42, rig.progress.stored["e1"]?.progressSeconds)
    }

    @Test
    fun playing_to_the_end_is_watched() = runTest {
        val rig = rig()
        rig.head.position = 1_500.0

        rig.vm.onEnded()
        runCurrent()

        assertTrue(rig.vm.state.value.ended)
        assertEquals(true, rig.progress.stored["e1"]?.isWatched)
        assertEquals("paused", rig.prefs.intents["e1"], "so coming back does not start playing")
    }

    @Test
    fun it_starts_playing_unless_it_was_left_paused() = runTest {
        assertTrue(rig().vm.autoplay)
        assertFalse(rig(playIntent = "paused").vm.autoplay)
    }

    @Test
    fun watching_from_the_start_resets_the_saved_spot() = runTest {
        val rig = rig(saved = listOf(progress("e1", 900, watched = true)))
        rig.head.position = 0.0

        rig.vm.watchFromStart()
        runCurrent()

        assertEquals(0, rig.progress.stored["e1"]?.progressSeconds)
        assertEquals(false, rig.progress.stored["e1"]?.isWatched)

        rig.play(7.0)
        assertEquals(7, rig.progress.stored["e1"]?.progressSeconds, "and saving starts again from 0:00")
    }

    @Test
    fun clearing_progress_forgets_the_episode() = runTest {
        val rig = rig(saved = listOf(progress("e1", 900)))
        rig.head.position = 1_500.0
        rig.vm.onEnded()
        runCurrent()

        assertTrue(rig.vm.clearProgress())

        assertNull(rig.progress.stored["e1"])
        assertFalse(rig.vm.state.value.ended)
    }

    /** The screen can go while a save is on its way; the save must still land. */
    @Test
    fun a_save_in_flight_lands_after_the_screen_closes_and_a_retry_does_not() = runTest {
        val rig = rig()
        rig.vm.resolve(e1)
        rig.resolver = { it.copy(sources = renditions.mapValues { (_, url) -> "$url?fresh" }) }
        rig.head.position = 300.0

        rig.vm.save()
        rig.vm.onMediaError(code = 2)
        rig.vm.close()
        runCurrent()

        assertEquals(300, rig.progress.stored["e1"]?.progressSeconds)
        assertEquals(renditions["1080p"], rig.vm.state.value.videoUrl, "the retry was cancelled")
    }

    // -------------------------------------------------------------- resume

    @Test
    fun a_resume_lands_short_of_the_end() = runTest {
        val rig = rig(saved = listOf(progress("e1", 1_499)))

        assertEquals(1_495.0, rig.vm.resumeTarget())
        assertEquals(5.0, rig.vm.state.value.remaining)

        val early = rig(saved = listOf(progress("e1", 300)))
        assertEquals(300.0, early.vm.resumeTarget())
    }

    @Test
    fun there_is_no_resume_near_the_start_or_before_the_catalog_loads() = runTest {
        assertNull(rig(saved = listOf(progress("e1", 1))).vm.resumeTarget())
        assertNull(rig(saved = listOf(progress("e1", 300)), loaded = false).vm.resumeTarget())

        val unknownLength = rig(saved = listOf(progress("e1", 300)))
        unknownLength.head.duration = null
        assertNull(unknownLength.vm.resumeTarget())
    }

    @Test
    fun a_resume_target_does_not_count_as_playback_to_save() = runTest {
        val rig = rig(saved = listOf(progress("e1", 300)))
        rig.vm.resumeTarget()
        rig.vm.resumeLanded()

        rig.play(300.0, 306.0)

        assertEquals(0, rig.progress.saves, "less than seven seconds past the resume")
    }

    /** Before the resume lands the element reports the 0:00 it loaded at. */
    @Test
    fun the_position_waits_for_the_resume_to_land() = runTest {
        val rig = rig(saved = listOf(progress("e1", 300)))
        rig.vm.resumeTarget()

        rig.play(0.0)
        assertNull(rig.vm.state.value.position)

        rig.vm.resumeLanded()
        rig.play(300.6)
        assertEquals(300, rig.vm.state.value.position)
    }

    @Test
    fun with_nothing_to_resume_the_position_moves_at_once() = runTest {
        val rig = rig()
        rig.vm.resumeTarget()

        rig.play(0.5)

        assertEquals(0, rig.vm.state.value.position)
    }

    // ------------------------------------------------------- the next card

    @Test
    fun the_next_card_is_due_in_the_last_three_minutes_and_returns_after_a_seek_back() = runTest {
        val rig = rig()

        rig.play(1_310.0)
        assertFalse(rig.vm.state.value.promptDue, "190 s left")

        rig.play(1_330.0)
        assertTrue(rig.vm.state.value.promptDue, "170 s left")

        rig.vm.dismissPrompt()
        assertFalse(rig.vm.state.value.promptDue)

        rig.play(1_200.0)
        assertFalse(rig.vm.state.value.promptDismissed, "seeking out of the window re-arms it")

        rig.play(1_400.0)
        assertTrue(rig.vm.state.value.promptDue)
    }

    @Test
    fun the_next_card_is_not_due_once_the_episode_ended() = runTest {
        val rig = rig()
        rig.play(1_490.0)

        rig.vm.onEnded()

        assertFalse(rig.vm.state.value.promptDue)
    }

    // ------------------------------------------------------------ next one

    @Test
    fun leaving_for_the_next_episode_near_the_end_counts_as_watched_and_goes_once() = runTest {
        val rig = rig()
        rig.head.position = 1_400.0

        rig.vm.goToNext()
        rig.vm.goToNext()
        runCurrent()

        assertEquals(listOf<Route>(Route.Watch("e2")), rig.router.went)
        assertEquals(true, rig.progress.stored["e1"]?.isWatched)
    }

    @Test
    fun leaving_for_the_next_episode_early_keeps_the_spot() = runTest {
        val rig = rig()
        rig.head.position = 600.0

        rig.vm.goToNext()
        runCurrent()

        assertEquals(600, rig.progress.stored["e1"]?.progressSeconds)
        assertEquals(false, rig.progress.stored["e1"]?.isWatched)
    }

    // ----------------------------------------------------------- the rig

    private class Rig(
        val vm: PlaybackViewModel,
        val catalog: CatalogStore,
        val settings: SettingsStore,
        val progress: FakeProgressRepository,
        val prefs: FakePreferences,
        val router: RecordingRouter,
        val head: FakePlayhead,
        private val catalogSource: ScriptedCatalog,
        private val test: TestScope,
    ) {
        var resolver: (Episode) -> Episode
            get() = catalogSource.resolve
            set(value) { catalogSource.resolve = value }

        /** Moves the element to each time in turn, as `timeupdate` would report it. */
        fun play(vararg times: Double) {
            for (time in times) {
                head.position = time
                vm.onTimeUpdate()
                test.runCurrent()
            }
        }
    }

    private suspend fun TestScope.rig(
        episodes: List<Episode> = listOf(e1, e2),
        saved: List<WatchProgress> = emptyList(),
        resolve: (Episode) -> Episode = { it },
        preferred: String? = null,
        maxQuality: Int? = null,
        appleMobile: Boolean = false,
        viewer: String? = "u",
        playIntent: String? = null,
        loaded: Boolean = true,
    ): Rig {
        val clock = FakeClock()
        val catalogSource = ScriptedCatalog(episodes, resolve)
        val progress = FakeProgressRepository(saved.associateBy { it.episodeId })
        val catalog = CatalogStore(
            loadCatalog = LoadCatalog(catalogSource),
            loadProgress = LoadProgress(progress),
            refreshCatalogUseCase = RefreshCatalog(catalogSource, clock),
            saveProgressUseCase = SaveProgress(progress, clock),
            clearEpisodeProgress = ClearEpisodeProgress(progress),
            markSeasonWatchedUseCase = MarkSeasonWatched(progress, clock),
            resetSeasonProgressUseCase = ResetSeasonProgress(progress),
            resetAllProgressUseCase = ResetAllProgress(progress),
            recordEpisodeDuration = RecordEpisodeDuration(catalogSource),
        )
        if (loaded) catalog.ensureLoaded("u")

        val device = FakeKeyValueStore().apply { if (preferred != null) entries["preferred_quality"] = preferred }
        val repository = SyncedSettingsRepository(FakeAccountSettings(stored = UserSettings()), LocalSettingsCache(device), backgroundScope)
        val settings = SettingsStore(ObserveUserSettings(repository), ChangeUserSetting(repository), backgroundScope)
        SyncUserSettings(repository)("u")
        runCurrent()

        val prefs = FakePreferences().apply { if (playIntent != null) intents["e1"] = playIntent }
        val router = RecordingRouter()
        val head = FakePlayhead()
        val vm = PlaybackViewModel(
            episodeId = "e1",
            viewerId = { viewer },
            catalog = catalog,
            settings = settings,
            preferences = PreferencesStore(prefs, backgroundScope),
            resolveEpisodeVideo = ResolveEpisodeVideo(catalogSource),
            router = router,
            maxQuality = maxQuality,
            appleMobile = appleMobile,
            scope = backgroundScope,
        )
        vm.attach(head)
        return Rig(vm, catalog, settings, progress, prefs, router, head, catalogSource, this)
    }
}

/** A catalog whose live resolve the test can change between calls. */
private class ScriptedCatalog(
    private val episodes: List<Episode>,
    var resolve: (Episode) -> Episode,
) : CatalogRepository {
    override suspend fun load() = Catalog(episodes, CatalogMeta(1.0, 1, episodes.size))
    override suspend fun listEpisodes() = episodes
    override suspend fun getMeta() = CatalogMeta(1.0, 1, episodes.size)
    override suspend fun refresh(nowMillis: Double, onProgress: (Int, Int) -> Unit) =
        RefreshResult(1, episodes.size, 0, 0, episodes)
    override suspend fun resolveVideo(episode: Episode) = resolve(episode)
    override suspend fun recordDuration(episodeId: String, durationSeconds: Int) = Unit
}
