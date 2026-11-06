package ge.dakalebi.domain.usecase

import ge.dakalebi.domain.Clock
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.ProgressRepository
import ge.dakalebi.domain.service.CAVEA_ONLY_ID_PREFIX

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

/**
 * Moves progress saved on a Cavea-only episode to the Formula episode it became.
 *
 * An episode only Cavea Plus has is filed under `cavea-<Cavea's id>`. When Formula
 * publishes it later (season 19 may well arrive that way), the next refresh joins
 * the two and the episode is filed under Formula's id instead, so the viewer's
 * "watched" would be left behind on an id nothing shows any more.
 *
 * So, for every such entry whose Cavea id is now a Formula episode's: the entry is
 * saved under the Formula id, unless that id already has one at least as recent,
 * and the old entry is deleted either way. Returns [current] as it is after the
 * move, for the caller to show. Does nothing, and costs nothing, when no entry is
 * left behind, which is every load but the first after such a refresh.
 */
class CarryOverCaveaProgress(private val progress: ProgressRepository) {
    suspend operator fun invoke(
        uid: String,
        episodes: List<Episode>,
        current: Map<String, WatchProgress>,
    ): Map<String, WatchProgress> {
        val stranded = current.values.filter { it.episodeId.startsWith(CAVEA_ONLY_ID_PREFIX) }
        if (stranded.isEmpty()) return current

        val formulaByCaveaId = episodes
            .filter { !it.isCaveaOnly && it.caveaEpisodeId != null }
            .associateBy { it.caveaEpisodeId!! }

        var result = current
        for (entry in stranded) {
            val target = formulaByCaveaId[entry.episodeId.removePrefix(CAVEA_ONLY_ID_PREFIX)] ?: continue
            val existing = result[target.id]
            if (existing == null || existing.lastWatchedAtMillis < entry.lastWatchedAtMillis) {
                val moved = entry.copy(episodeId = target.id)
                progress.save(uid, moved)
                result = result + (target.id to moved)
            }
            progress.delete(uid, entry.episodeId)
            result = result - entry.episodeId
        }
        return result
    }
}
