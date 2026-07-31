package ge.dakalebi.data

import ge.dakalebi.app.nowMillis
import ge.dakalebi.firebase.Firebase
import ge.dakalebi.firebase.externals.collection
import ge.dakalebi.firebase.externals.deleteDoc
import ge.dakalebi.firebase.externals.doc
import ge.dakalebi.firebase.externals.getDocs
import ge.dakalebi.firebase.externals.setDoc
import ge.dakalebi.firebase.externals.writeBatch
import kotlinx.coroutines.await

/**
 * Per-user watch progress at `users/{uid}/progress/{episodeId}`.
 *
 * A subcollection rather than a top-level table with a user column: the
 * security rule becomes `request.auth.uid == userId`, and a user can never
 * read another user's rows even by accident.
 */
object ProgressRepository {
    private const val USERS = "users"
    private const val PROGRESS = "progress"
    private const val BATCH_LIMIT = 450

    /** Fraction of an episode that counts as watched, matching the original. */
    private const val WATCHED_AT = 0.9

    suspend fun list(uid: String): List<WatchProgress> =
        getDocs(collection(Firebase.db, USERS, uid, PROGRESS)).await()
            .docs
            .map { snapshot ->
                val data = snapshot.data()
                WatchProgress(
                    episodeId = snapshot.id,
                    progressSeconds = dynInt(data.progressSeconds) ?: 0,
                    durationSeconds = dynInt(data.durationSeconds),
                    isWatched = dynBool(data.isWatched),
                    lastWatchedAtMillis = dynDouble(data.lastWatchedAtMillis) ?: 0.0,
                )
            }

    /**
     * Saves a position.
     *
     * [allowReset] is required to move a finished episode backwards. Without
     * that guard a stray low timestamp — a `timeupdate` that fires at 0 while
     * the element reloads, say — silently un-watches a completed episode.
     */
    suspend fun save(
        uid: String,
        episodeId: String,
        progressSeconds: Int,
        durationSeconds: Int?,
        isWatched: Boolean? = null,
        allowReset: Boolean = false,
        existing: WatchProgress? = null,
    ): Boolean {
        if (!allowReset && existing != null &&
            existing.isWatched && isWatched != true &&
            progressSeconds < existing.progressSeconds
        ) {
            return existing.isWatched
        }

        val watched = when {
            allowReset -> false
            isWatched != null -> isWatched
            durationSeconds != null && durationSeconds > 0 ->
                progressSeconds.toDouble() / durationSeconds >= WATCHED_AT
            else -> false
        }

        val payload = jsObject()
        payload.progressSeconds = progressSeconds
        payload.durationSeconds = durationSeconds
        payload.isWatched = watched
        payload.lastWatchedAtMillis = nowMillis()

        setDoc(doc(Firebase.db, USERS, uid, PROGRESS, episodeId), payload).await()
        return watched
    }

    suspend fun delete(uid: String, episodeId: String) {
        deleteDoc(doc(Firebase.db, USERS, uid, PROGRESS, episodeId)).await()
    }

    /** Marks every episode of a season watched, in chunks under the batch limit. */
    suspend fun markSeasonWatched(uid: String, episodes: List<Episode>): Int {
        val now = nowMillis()
        for (chunk in episodes.chunked(BATCH_LIMIT)) {
            val batch = writeBatch(Firebase.db)
            for (episode in chunk) {
                val payload = jsObject()
                payload.progressSeconds = episode.durationSeconds ?: 0
                payload.durationSeconds = episode.durationSeconds
                payload.isWatched = true
                payload.lastWatchedAtMillis = now
                batch.set(doc(Firebase.db, USERS, uid, PROGRESS, episode.id), payload)
            }
            batch.commit().await()
        }
        return episodes.size
    }

    suspend fun deleteMany(uid: String, episodeIds: List<String>): Int {
        for (chunk in episodeIds.chunked(BATCH_LIMIT)) {
            val batch = writeBatch(Firebase.db)
            for (id in chunk) {
                batch.delete(doc(Firebase.db, USERS, uid, PROGRESS, id))
            }
            batch.commit().await()
        }
        return episodeIds.size
    }

    /** Wipes everything for this user, reading the ids back first. */
    suspend fun deleteAll(uid: String): Int {
        val ids = getDocs(collection(Firebase.db, USERS, uid, PROGRESS)).await()
            .docs
            .map { it.id }
        return deleteMany(uid, ids)
    }
}
