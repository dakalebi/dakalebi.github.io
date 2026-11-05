package ge.dakalebi.data

import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.Viewer
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.ProgressRepository

/**
 * Files a guest's progress on the device and everyone else's with their account.
 *
 * Routed by the owner each call names rather than by whoever is signed in right
 * now. A save fired while leaving the watch screen can land after the viewer has
 * changed, and it still belongs to the viewer who was watching.
 *
 * This is the one place that knows the two stores exist, so the guarantee that
 * nothing of a guest's reaches the account backend is a property of this class
 * and is tested as one.
 */
class ViewerProgressRepository(
    private val member: ProgressRepository,
    private val guest: ProgressRepository,
) : ProgressRepository {

    private fun storeFor(uid: String): ProgressRepository = if (uid == Viewer.Guest.id) guest else member

    override suspend fun list(uid: String): List<WatchProgress> = storeFor(uid).list(uid)

    override suspend fun save(uid: String, progress: WatchProgress): Boolean = storeFor(uid).save(uid, progress)

    override suspend fun delete(uid: String, episodeId: String) = storeFor(uid).delete(uid, episodeId)

    override suspend fun markWatched(uid: String, episodes: List<Episode>, nowMillis: Double) =
        storeFor(uid).markWatched(uid, episodes, nowMillis)

    override suspend fun deleteMany(uid: String, episodeIds: List<String>) =
        storeFor(uid).deleteMany(uid, episodeIds)

    override suspend fun deleteAll(uid: String) = storeFor(uid).deleteAll(uid)
}
