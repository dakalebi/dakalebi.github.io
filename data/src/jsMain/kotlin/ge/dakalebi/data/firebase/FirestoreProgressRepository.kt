package ge.dakalebi.data.firebase

import ge.dakalebi.data.firebase.externals.collection
import ge.dakalebi.data.firebase.externals.deleteDoc
import ge.dakalebi.data.firebase.externals.doc
import ge.dakalebi.data.firebase.externals.getDocs
import ge.dakalebi.data.firebase.externals.setDoc
import ge.dakalebi.data.firebase.externals.writeBatch
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.ProgressRepository
import kotlinx.coroutines.await

/**
 * Watch progress at `users/{uid}/progress/{episodeId}`.
 *
 * A subcollection rather than a top-level table with a user column: the
 * security rule becomes `request.auth.uid == userId`, and one viewer can never
 * read another's rows even by accident.
 *
 * Storage only. What a position *means* — whether it counts as watched, whether
 * it is allowed to move backwards — is [ge.dakalebi.domain.usecase.SaveProgress].
 */
class FirestoreProgressRepository : ProgressRepository {

    override suspend fun list(uid: String): List<WatchProgress> =
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

    override suspend fun save(uid: String, progress: WatchProgress): Boolean {
        setDoc(ref(uid, progress.episodeId), progress.toJs()).await()
        return progress.isWatched
    }

    override suspend fun delete(uid: String, episodeId: String) {
        deleteDoc(ref(uid, episodeId)).await()
    }

    override suspend fun markWatched(uid: String, episodes: List<Episode>, nowMillis: Double) {
        for (chunk in episodes.chunked(BATCH_LIMIT)) {
            val batch = writeBatch(Firebase.db)
            for (episode in chunk) {
                val entry = WatchProgress(
                    episodeId = episode.id,
                    progressSeconds = episode.durationSeconds ?: 0,
                    durationSeconds = episode.durationSeconds,
                    isWatched = true,
                    lastWatchedAtMillis = nowMillis,
                )
                batch.set(ref(uid, episode.id), entry.toJs())
            }
            batch.commit().await()
        }
    }

    override suspend fun deleteMany(uid: String, episodeIds: List<String>) {
        for (chunk in episodeIds.chunked(BATCH_LIMIT)) {
            val batch = writeBatch(Firebase.db)
            for (id in chunk) batch.delete(ref(uid, id))
            batch.commit().await()
        }
    }

    /** Wipes everything for this account, reading the ids back first. */
    override suspend fun deleteAll(uid: String) {
        val ids = getDocs(collection(Firebase.db, USERS, uid, PROGRESS)).await()
            .docs
            .map { it.id }
        deleteMany(uid, ids)
    }

    private fun ref(uid: String, episodeId: String) =
        doc(Firebase.db, USERS, uid, PROGRESS, episodeId)

    private fun WatchProgress.toJs(): dynamic {
        val payload = jsObject()
        payload.progressSeconds = progressSeconds
        payload.durationSeconds = durationSeconds
        payload.isWatched = isWatched
        payload.lastWatchedAtMillis = lastWatchedAtMillis
        return payload
    }

    private companion object {
        const val USERS = "users"
        const val PROGRESS = "progress"

        /** Firestore caps a batch at 500 writes. */
        const val BATCH_LIMIT = 450
    }
}
