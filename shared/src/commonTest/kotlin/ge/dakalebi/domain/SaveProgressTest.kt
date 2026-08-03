package ge.dakalebi.domain

import ge.dakalebi.domain.usecase.SaveProgress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules that decide what a playback position means.
 *
 * These lived inside the Firestore class until the restructure, where the only
 * way to check them was to play a video and watch what happened. The
 * un-watching case in particular was a real bug, reported as "the app forgets
 * episodes at random".
 */
class SaveProgressTest {

    private val clock = FakeClock(now = 5_000.0)

    private fun useCase(repo: FakeProgressRepository) = SaveProgress(repo, clock)

    @Test
    fun marks_watched_past_ninety_percent() = runTest {
        val repo = FakeProgressRepository()
        val saved = useCase(repo)(
            uid = "u",
            episodeId = "e",
            progressSeconds = 1_350, // exactly 90% of 1500
            durationSeconds = 1_500,
        )
        assertTrue(saved.isWatched)
    }

    @Test
    fun leaves_unwatched_just_below_the_threshold() = runTest {
        val repo = FakeProgressRepository()
        val saved = useCase(repo)(
            uid = "u",
            episodeId = "e",
            progressSeconds = 1_349,
            durationSeconds = 1_500,
        )
        assertFalse(saved.isWatched)
    }

    @Test
    fun an_unknown_duration_never_counts_as_watched() = runTest {
        val repo = FakeProgressRepository()
        val saved = useCase(repo)(
            uid = "u",
            episodeId = "e",
            progressSeconds = 99_999,
            durationSeconds = null,
        )
        assertFalse(saved.isWatched)
    }

    /**
     * The regression this guard exists for: a `timeupdate` firing at 0 while
     * the element reloads used to un-watch a finished episode.
     */
    @Test
    fun a_finished_episode_does_not_move_backwards() = runTest {
        val repo = FakeProgressRepository()
        val existing = progress("e", seconds = 1_500, watched = true, at = 1.0)

        val saved = useCase(repo)(
            uid = "u",
            episodeId = "e",
            progressSeconds = 0,
            durationSeconds = 1_500,
            existing = existing,
        )

        assertEquals(existing, saved)
        assertEquals(0, repo.saves, "a rejected rewind must not reach storage")
    }

    @Test
    fun an_explicit_reset_may_move_it_backwards() = runTest {
        val repo = FakeProgressRepository()
        val saved = useCase(repo)(
            uid = "u",
            episodeId = "e",
            progressSeconds = 900,
            durationSeconds = 1_500,
            allowReset = true,
            existing = progress("e", seconds = 1_500, watched = true),
        )

        assertEquals(0, saved.progressSeconds, "a reset rewinds to the start")
        assertFalse(saved.isWatched)
        assertEquals(1, repo.saves)
    }

    @Test
    fun marking_watched_by_hand_beats_the_threshold() = runTest {
        val repo = FakeProgressRepository()
        val saved = useCase(repo)(
            uid = "u",
            episodeId = "e",
            progressSeconds = 10,
            durationSeconds = 1_500,
            isWatched = true,
        )
        assertTrue(saved.isWatched)
    }

    /** Formula never reports durations, so an existing one must survive. */
    @Test
    fun keeps_a_known_duration_when_the_new_one_is_missing() = runTest {
        val repo = FakeProgressRepository()
        val saved = useCase(repo)(
            uid = "u",
            episodeId = "e",
            progressSeconds = 30,
            durationSeconds = null,
            existing = progress("e", seconds = 10, duration = 1_500),
        )
        assertEquals(1_500, saved.durationSeconds)
    }

    @Test
    fun stamps_the_save_from_the_clock() = runTest {
        val repo = FakeProgressRepository()
        clock.now = 1_234_567.0
        val saved = useCase(repo)(
            uid = "u",
            episodeId = "e",
            progressSeconds = 30,
            durationSeconds = 1_500,
        )
        assertEquals(1_234_567.0, saved.lastWatchedAtMillis)
    }

    // ------------------------------------------------- staying watched

    /**
     * Reported: "I marked it watched, it hid from the dashboard, and after a
     * while it came back with the same progress."
     *
     * Marking watched stores the *current* position, not the duration. The
     * autosave seven seconds later carries no opinion about watchedness, so
     * the flag was recomputed from the ratio — 407 of 1500 is 27%, well under
     * the threshold — and the episode silently un-watched itself. The
     * never-rewind guard does not catch it: the position went forwards.
     */
    @Test
    fun the_next_autosave_does_not_undo_marking_watched() = runTest {
        val repo = FakeProgressRepository()
        val marked = useCase(repo)(
            uid = "u",
            episodeId = "e",
            progressSeconds = 400,
            durationSeconds = 1_500,
            isWatched = true,
        )
        assertTrue(marked.isWatched)

        val autosaved = useCase(repo)(
            uid = "u",
            episodeId = "e",
            progressSeconds = 407,
            durationSeconds = 1_500,
            existing = marked,
        )

        assertTrue(autosaved.isWatched, "an autosave must not un-watch an episode")
        assertEquals(407, autosaved.progressSeconds, "but the position should still advance")
    }

    /**
     * The same hole reached a different way: with no duration the ratio branch
     * cannot run, so the flag fell through to false. `effectiveDuration`
     * returns null while the element is being torn down and whenever AirPlay
     * is mid-handover, and the unmount save runs exactly then.
     */
    @Test
    fun a_save_with_no_duration_does_not_un_watch() = runTest {
        val repo = FakeProgressRepository()
        val existing = progress("e", seconds = 1_500, duration = 1_500, watched = true)

        val saved = useCase(repo)(
            uid = "u",
            episodeId = "e",
            progressSeconds = 1_500,
            durationSeconds = null,
            existing = existing,
        )

        assertTrue(saved.isWatched)
    }

    /** Only an explicit decision may take it back. */
    @Test
    fun an_explicit_false_still_un_watches() = runTest {
        val repo = FakeProgressRepository()
        val saved = useCase(repo)(
            uid = "u",
            episodeId = "e",
            progressSeconds = 1_400,
            durationSeconds = 1_500,
            isWatched = false,
            existing = progress("e", seconds = 1_400, watched = true),
        )
        assertFalse(saved.isWatched)
    }
}
