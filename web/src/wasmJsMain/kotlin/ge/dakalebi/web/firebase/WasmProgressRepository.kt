package ge.dakalebi.web.firebase

import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.ProgressRepository
import kotlinx.coroutines.await
import kotlin.js.get

/**
 * Watch progress at `users/{uid}/progress/{episodeId}`.
 *
 * A subcollection rather than a top-level table with a user column: the security rule
 * becomes `request.auth.uid == userId`, and one viewer can never read another's rows even by
 * accident.
 *
 * Storage only. What a position *means* — whether it counts as watched, whether it is
 * allowed to move backwards — is [ge.dakalebi.domain.usecase.SaveProgress].
 */
class WasmProgressRepository : ProgressRepository {

    override suspend fun list(uid: String): List<WatchProgress> {
        val docs = getDocs(collection(FirebaseWasm.db, progressPath(uid))).await().docs
        val entries = ArrayList<WatchProgress>(docs.length)
        for (index in 0 until docs.length) {
            val document = docs[index] ?: continue
            val data = document.data()
            entries += WatchProgress(
                episodeId = document.id,
                progressSeconds = readInt(data, "progressSeconds") ?: 0,
                durationSeconds = readInt(data, "durationSeconds"),
                isWatched = readBool(data, "isWatched"),
                lastWatchedAtMillis = readDouble(data, "lastWatchedAtMillis") ?: 0.0,
            )
        }
        return entries
    }

    override suspend fun save(uid: String, progress: WatchProgress): Boolean {
        setDoc(ref(uid, progress.episodeId), progress.toJs(), setOptions(merge = false)).await()
        return progress.isWatched
    }

    override suspend fun delete(uid: String, episodeId: String) {
        deleteDoc(ref(uid, episodeId)).await()
    }

    override suspend fun markWatched(uid: String, episodes: List<Episode>, nowMillis: Double) {
        for (chunk in episodes.chunked(BATCH_LIMIT)) {
            val batch = writeBatch(FirebaseWasm.db)
            for (episode in chunk) {
                val entry = WatchProgress(
                    episodeId = episode.id,
                    progressSeconds = episode.durationSeconds ?: 0,
                    durationSeconds = episode.durationSeconds,
                    isWatched = true,
                    lastWatchedAtMillis = nowMillis,
                )
                batch.set(ref(uid, episode.id), entry.toJs(), setOptions(merge = false))
            }
            batch.commit().await()
        }
    }

    override suspend fun deleteMany(uid: String, episodeIds: List<String>) {
        for (chunk in episodeIds.chunked(BATCH_LIMIT)) {
            val batch = writeBatch(FirebaseWasm.db)
            for (id in chunk) batch.delete(ref(uid, id))
            batch.commit().await()
        }
    }

    /** Wipes everything for this account, reading the ids back first. */
    override suspend fun deleteAll(uid: String) {
        val docs = getDocs(collection(FirebaseWasm.db, progressPath(uid))).await().docs
        val ids = ArrayList<String>(docs.length)
        for (index in 0 until docs.length) {
            ids += docs[index]?.id ?: continue
        }
        deleteMany(uid, ids)
    }

    private fun progressPath(uid: String) = "$USERS/$uid/$PROGRESS"

    private fun ref(uid: String, episodeId: String) =
        doc(FirebaseWasm.db, "${progressPath(uid)}/$episodeId")

    private fun WatchProgress.toJs(): JsAny {
        val payload = newObject()
        putInt(payload, "progressSeconds", progressSeconds)
        putIntOrNull(payload, "durationSeconds", durationSeconds)
        putBool(payload, "isWatched", isWatched)
        putDouble(payload, "lastWatchedAtMillis", lastWatchedAtMillis)
        return payload
    }

    private companion object {
        const val USERS = "users"
        const val PROGRESS = "progress"

        /** Firestore caps a batch at 500 writes. */
        const val BATCH_LIMIT = 450
    }
}
