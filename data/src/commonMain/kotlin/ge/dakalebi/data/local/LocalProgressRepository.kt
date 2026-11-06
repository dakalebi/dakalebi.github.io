package ge.dakalebi.data.local

import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.ProgressRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Watch progress on this device and nowhere else. This is where a guest's goes.
 *
 * Every change reads what is stored, applies itself and writes the result back,
 * rather than overwriting from a copy held in memory. Two tabs open as a guest
 * then each add their own episodes instead of the last one to save erasing the
 * other's.
 *
 * Storage failing is not allowed to fail the viewer. A store that cannot be read
 * or written falls back to the copy this page holds, so the session behaves the
 * same and only the persistence is lost, which is the trade [LocalCatalogCache]
 * makes too.
 */
class LocalProgressRepository(private val store: KeyValueStore) : ProgressRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredProgress.serializer())

    /** This page's copy per owner: the fallback whenever the store refuses. */
    private val memory = mutableMapOf<String, Map<String, WatchProgress>>()

    /** Owners whose latest change only [memory] holds, because the write was refused. */
    private val unsaved = mutableSetOf<String>()

    override suspend fun list(uid: String): List<WatchProgress> = read(uid).values.toList()

    override suspend fun save(uid: String, progress: WatchProgress): Boolean {
        update(uid) { it + (progress.episodeId to progress) }
        return progress.isWatched
    }

    override suspend fun delete(uid: String, episodeId: String) = update(uid) { it - episodeId }

    override suspend fun markWatched(uid: String, episodes: List<Episode>, nowMillis: Double) =
        update(uid) { current ->
            current + episodes.associate {
                it.id to WatchProgress(
                    episodeId = it.id,
                    progressSeconds = it.durationSeconds ?: 0,
                    durationSeconds = it.durationSeconds,
                    isWatched = true,
                    lastWatchedAtMillis = nowMillis,
                )
            }
        }

    override suspend fun deleteMany(uid: String, episodeIds: List<String>) =
        update(uid) { it - episodeIds.toSet() }

    override suspend fun deleteAll(uid: String) = update(uid) { emptyMap() }

    private fun update(uid: String, change: (Map<String, WatchProgress>) -> Map<String, WatchProgress>) {
        val next = change(read(uid))
        memory[uid] = next
        write(uid, next)
    }

    private fun read(uid: String): Map<String, WatchProgress> {
        if (uid in unsaved) return memory[uid].orEmpty()
        val raw = runCatching { store.get(keyOf(uid)) }
            .onFailure { Log.w("progress", "device storage unreadable", it) }
            .getOrElse { return memory[uid].orEmpty() }
            ?: return memory[uid].orEmpty()

        return runCatching { json.decodeFromString(serializer, raw) }
            .map { stored -> stored.map { it.toDomain() }.associateBy { it.episodeId } }
            .onFailure { Log.w("progress", "discarding unreadable device progress", it) }
            .getOrElse { memory[uid].orEmpty() }
    }

    private fun write(uid: String, progress: Map<String, WatchProgress>) {
        val payload = json.encodeToString(serializer, progress.values.map { StoredProgress.of(it) })
        runCatching { store.set(keyOf(uid), payload) }
            .onSuccess { unsaved -= uid }
            .onFailure {
                unsaved += uid
                Log.w("progress", "could not store progress on this device", it)
            }
    }

    private companion object {
        /** Bump the version when [StoredProgress]'s shape changes. */
        fun keyOf(uid: String) = "progress_v1:$uid"
    }
}

/** The stored shape, kept apart from [WatchProgress] for the reason [CachedEpisode] is. */
@Serializable
internal data class StoredProgress(
    val episodeId: String,
    val progressSeconds: Int,
    val durationSeconds: Int? = null,
    val isWatched: Boolean,
    val lastWatchedAtMillis: Double,
) {
    fun toDomain() = WatchProgress(episodeId, progressSeconds, durationSeconds, isWatched, lastWatchedAtMillis)

    companion object {
        fun of(progress: WatchProgress) = StoredProgress(
            episodeId = progress.episodeId,
            progressSeconds = progress.progressSeconds,
            durationSeconds = progress.durationSeconds,
            isWatched = progress.isWatched,
            lastWatchedAtMillis = progress.lastWatchedAtMillis,
        )
    }
}
