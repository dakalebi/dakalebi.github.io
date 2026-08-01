package ge.dakalebi.domain

import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.ProgressRepository

/** A clock that only moves when a test says so. */
class FakeClock(var now: Double = 1_000_000.0) : Clock {
    override fun nowMillis(): Double = now
}

/** In-memory progress storage, so the use cases can be exercised without Firestore. */
class FakeProgressRepository(
    initial: Map<String, WatchProgress> = emptyMap(),
) : ProgressRepository {
    val stored = initial.toMutableMap()
    var saves = 0
        private set

    override suspend fun list(uid: String): List<WatchProgress> = stored.values.toList()

    override suspend fun save(uid: String, progress: WatchProgress): Boolean {
        saves++
        stored[progress.episodeId] = progress
        return progress.isWatched
    }

    override suspend fun delete(uid: String, episodeId: String) {
        stored.remove(episodeId)
    }

    override suspend fun markWatched(uid: String, episodes: List<Episode>, nowMillis: Double) {
        for (episode in episodes) {
            stored[episode.id] = WatchProgress(
                episodeId = episode.id,
                progressSeconds = episode.durationSeconds ?: 0,
                durationSeconds = episode.durationSeconds,
                isWatched = true,
                lastWatchedAtMillis = nowMillis,
            )
        }
    }

    override suspend fun deleteMany(uid: String, episodeIds: List<String>) {
        episodeIds.forEach { stored.remove(it) }
    }

    override suspend fun deleteAll(uid: String) = stored.clear()
}

/** Terse episode builder — the tests care about three fields out of thirteen. */
fun episode(
    season: Int,
    number: Int,
    durationSeconds: Int? = 1_500,
    id: String = "s${season}e$number",
) = Episode(
    id = id,
    formulaEpisodeId = number,
    formulaSeasonId = season,
    seasonNumber = season,
    episodeNumber = number,
    title = null,
    thumbnailUrl = null,
    videoUrl = "https://example.invalid/$id.mp4",
    sources = mapOf("720p" to "https://example.invalid/$id.mp4"),
    durationSeconds = durationSeconds,
    episodePageUrl = "https://example.invalid/$id",
    lastResolvedAtMillis = null,
    updatedAtMillis = null,
)

fun progress(
    episodeId: String,
    seconds: Int,
    duration: Int? = 1_500,
    watched: Boolean = false,
    at: Double = 0.0,
) = WatchProgress(
    episodeId = episodeId,
    progressSeconds = seconds,
    durationSeconds = duration,
    isWatched = watched,
    lastWatchedAtMillis = at,
)
