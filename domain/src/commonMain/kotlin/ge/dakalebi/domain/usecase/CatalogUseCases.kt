package ge.dakalebi.domain.usecase

import ge.dakalebi.domain.Clock
import ge.dakalebi.domain.model.Catalog
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.repository.CatalogRepository

/**
 * Loads the catalog and its metadata.
 *
 * The episodes are not optional: if they cannot be read the caller has to
 * know, because the alternative is a screen telling the viewer the database is
 * empty when it is merely unreachable.
 */
class LoadCatalog(private val catalog: CatalogRepository) {
    suspend operator fun invoke(): Catalog = catalog.load()
}

class RefreshCatalog(
    private val catalog: CatalogRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RefreshResult = catalog.refresh(clock.nowMillis(), onProgress)
}

class ResolveEpisodeVideo(private val catalog: CatalogRepository) {
    suspend operator fun invoke(episode: Episode): Episode = catalog.resolveVideo(episode)
}

/**
 * Records a duration the player measured.
 *
 * Formula never reports durations, so the player is the only source of them,
 * and writing one back is best-effort: only admins may touch the episodes
 * collection, so for everyone else this is expected to do nothing.
 */
class RecordEpisodeDuration(private val catalog: CatalogRepository) {
    suspend operator fun invoke(episode: Episode, durationSeconds: Int): Boolean {
        if (durationSeconds <= 0 || episode.durationSeconds == durationSeconds) return false
        catalog.recordDuration(episode.id, durationSeconds)
        return true
    }
}
