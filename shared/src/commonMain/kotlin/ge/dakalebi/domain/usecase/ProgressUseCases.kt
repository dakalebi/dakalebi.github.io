package ge.dakalebi.domain.usecase

import ge.dakalebi.domain.Clock
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.ProgressRepository

class LoadProgress(private val progress: ProgressRepository) {
    suspend operator fun invoke(uid: String): Map<String, WatchProgress> =
        progress.list(uid).associateBy { it.episodeId }
}

/**
 * Decides what a new position means, then stores it.
 *
 * The two rules here used to live inside the Firestore class, where they could
 * only be exercised by playing a video:
 *
 *  - **An episode past [WATCHED_AT] of its runtime counts as watched.** The
 *    threshold matches the original app so nobody's history shifts.
 *  - **A finished episode never moves backwards** unless the caller explicitly
 *    allows it. Without that guard a stray `timeupdate` firing at 0 while the
 *    element reloads silently un-watches a completed episode — which happened,
 *    and looked like the app losing history at random.
 */
class SaveProgress(
    private val progress: ProgressRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        uid: String,
        episodeId: String,
        progressSeconds: Int,
        durationSeconds: Int?,
        isWatched: Boolean? = null,
        allowReset: Boolean = false,
        existing: WatchProgress? = null,
    ): WatchProgress {
        val goingBackwards = existing != null &&
            existing.isWatched &&
            isWatched != true &&
            progressSeconds < existing.progressSeconds

        if (!allowReset && goingBackwards) return existing!!

        val watched = when {
            allowReset -> false
            isWatched != null -> isWatched

            // Watched is sticky. Only an explicit decision above — the
            // "watch from the start" reset, or a caller passing false — may
            // take it back; an inferred one never may.
            //
            // Without this, marking an episode watched half way through was
            // undone by the next autosave seven seconds later: the position
            // had moved *forwards*, so the rewind guard did not fire, and the
            // ratio said 27%. The episode reappeared on the dashboard with
            // almost the position it had before. The same hole opened whenever
            // the duration was unknown — during teardown, and mid-AirPlay
            // handover — because then the ratio cannot be computed at all.
            existing?.isWatched == true -> true

            durationSeconds != null && durationSeconds > 0 ->
                progressSeconds.toDouble() / durationSeconds >= WATCHED_AT

            else -> false
        }

        val entry = WatchProgress(
            episodeId = episodeId,
            progressSeconds = if (allowReset) 0 else progressSeconds,
            durationSeconds = durationSeconds ?: existing?.durationSeconds,
            isWatched = watched,
            lastWatchedAtMillis = clock.nowMillis(),
        )
        progress.save(uid, entry)
        return entry
    }

    companion object {
        /** Fraction of an episode that counts as watched. */
        const val WATCHED_AT = 0.9
    }
}

class ClearEpisodeProgress(private val progress: ProgressRepository) {
    suspend operator fun invoke(uid: String, episodeId: String) {
        progress.delete(uid, episodeId)
    }
}

/** Returns the entries to apply locally, so the UI need not re-read them. */
class MarkSeasonWatched(
    private val progress: ProgressRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(uid: String, episodes: List<Episode>): List<WatchProgress> {
        val now = clock.nowMillis()
        progress.markWatched(uid, episodes, now)
        return episodes.map {
            WatchProgress(
                episodeId = it.id,
                progressSeconds = it.durationSeconds ?: 0,
                durationSeconds = it.durationSeconds,
                isWatched = true,
                lastWatchedAtMillis = now,
            )
        }
    }
}

class ResetSeasonProgress(private val progress: ProgressRepository) {
    suspend operator fun invoke(uid: String, episodes: List<Episode>): Set<String> {
        val ids = episodes.map { it.id }
        progress.deleteMany(uid, ids)
        return ids.toSet()
    }
}

class ResetAllProgress(private val progress: ProgressRepository) {
    suspend operator fun invoke(uid: String) {
        progress.deleteAll(uid)
    }
}
