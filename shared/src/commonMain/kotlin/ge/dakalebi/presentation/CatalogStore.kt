package ge.dakalebi.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.CatalogMeta
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.CatalogCache
import ge.dakalebi.domain.service.CatalogQueries
import ge.dakalebi.domain.service.WatchStats
import ge.dakalebi.domain.usecase.ClearEpisodeProgress
import ge.dakalebi.domain.usecase.LoadCatalog
import ge.dakalebi.domain.usecase.LoadProgress
import ge.dakalebi.domain.usecase.MarkSeasonWatched
import ge.dakalebi.domain.usecase.RecordEpisodeDuration
import ge.dakalebi.domain.usecase.RefreshCatalog
import ge.dakalebi.domain.usecase.ResetAllProgress
import ge.dakalebi.domain.usecase.ResetSeasonProgress
import ge.dakalebi.domain.usecase.SaveProgress

/**
 * The catalog and this viewer's progress, as Compose state.
 *
 * Replaces react-query from the original. The data set is small (932 episodes,
 * one document each) and changes rarely, so it is loaded once per session and
 * mutated locally alongside the write rather than refetched.
 *
 * Every question about the data is answered by
 * [ge.dakalebi.domain.service.CatalogQueries] and every mutation goes through a
 * use case. What is left here is genuinely presentation: what is on screen,
 * what is in flight, and what went wrong.
 */
class CatalogStore(
    private val loadCatalog: LoadCatalog,
    private val loadProgress: LoadProgress,
    private val refreshCatalogUseCase: RefreshCatalog,
    private val saveProgressUseCase: SaveProgress,
    private val clearEpisodeProgress: ClearEpisodeProgress,
    private val markSeasonWatchedUseCase: MarkSeasonWatched,
    private val resetSeasonProgressUseCase: ResetSeasonProgress,
    private val resetAllProgressUseCase: ResetAllProgress,
    private val recordEpisodeDuration: RecordEpisodeDuration,
    private val cache: CatalogCache,
) {
    var episodes: List<Episode> by mutableStateOf(emptyList())
        private set

    var progress: Map<String, WatchProgress> by mutableStateOf(emptyMap())
        private set

    var meta: CatalogMeta? by mutableStateOf(null)
        private set

    var loading: Boolean by mutableStateOf(true)
        private set

    var refreshing: Boolean by mutableStateOf(false)
        private set

    /** Live "season 12 of 41" text while a rebuild runs. */
    var refreshNote: String? by mutableStateOf(null)
        private set

    /** Kept as the failure itself; turning it into words is the screen's job. */
    var loadError: Throwable? by mutableStateOf(null)
        private set

    private var loadedFor: String? = null

    // -------------------------------------------------------------- loading

    suspend fun ensureLoaded(uid: String) {
        // `loadError != null` deliberately re-enters. Without it a single
        // failed read latched permanently: the guard matched on every later
        // call, so the retry button — and any navigation — was a no-op.
        if (loadedFor == uid && !loading && loadError == null) return
        loadedFor = uid
        loading = true
        loadError = null
        try {
            val catalog = loadCatalog()
            episodes = catalog.episodes
            meta = catalog.meta
            progress = loadProgress(uid)
        } catch (e: Throwable) {
            Log.e("catalog", "load failed for uid=$uid", e)
            loadError = e
        } finally {
            loading = false
        }
    }

    fun reset() {
        loadedFor = null
        episodes = emptyList()
        progress = emptyMap()
        meta = null
        loading = true
        loadError = null
    }

    // ------------------------------------------------------------ mutations

    /**
     * Rebuilds the catalog from the provider.
     *
     * Returns the outcome rather than announcing it. A store that raises its
     * own toasts decides what the user reads, which is the screen's call — and
     * it was how Georgian strings ended up inside the data layer.
     */
    suspend fun refreshCatalog(progressLabel: (done: Int, total: Int) -> String): Result<RefreshResult> {
        if (refreshing) return Result.failure(IllegalStateException("already refreshing"))
        refreshing = true
        refreshNote = progressLabel(0, 0)
        return try {
            val result = refreshCatalogUseCase { done, total -> refreshNote = progressLabel(done, total) }
            // Straight from the rebuild rather than a re-read: asking Firestore
            // to tell us what we just wrote costs another 932 reads.
            episodes = result.catalog
            meta = loadCatalog().meta
            Result.success(result)
        } catch (e: Throwable) {
            // Surface the raw failure: a mapped message hides which stage broke.
            Log.e("catalog", "refresh failed", e)
            Result.failure(e)
        } finally {
            refreshing = false
            refreshNote = null
        }
    }

    suspend fun saveProgress(
        uid: String,
        episodeId: String,
        progressSeconds: Int,
        durationSeconds: Int?,
        isWatched: Boolean? = null,
        allowReset: Boolean = false,
    ) {
        val entry = saveProgressUseCase(
            uid = uid,
            episodeId = episodeId,
            progressSeconds = progressSeconds,
            durationSeconds = durationSeconds,
            isWatched = isWatched,
            allowReset = allowReset,
            existing = progress[episodeId],
        )
        progress = progress + (entry.episodeId to entry)

        if (durationSeconds != null) rememberDuration(episodeId, durationSeconds)
    }

    suspend fun clearProgress(uid: String, episodeId: String) {
        clearEpisodeProgress(uid, episodeId)
        progress = progress - episodeId
    }

    suspend fun markSeasonWatched(uid: String, season: Int) {
        val entries = markSeasonWatchedUseCase(uid, season(season))
        progress = progress + entries.associateBy { it.episodeId }
    }

    suspend fun resetSeason(uid: String, season: Int) {
        progress = progress - resetSeasonProgressUseCase(uid, season(season))
    }

    suspend fun resetAll(uid: String) {
        resetAllProgressUseCase(uid)
        progress = emptyMap()
    }

    /** Applies a freshly resolved video URL without a round-trip. */
    fun putEpisode(episode: Episode) {
        episodes = episodes.map { if (it.id == episode.id) episode else it }
    }

    /** Caches a duration the player measured, and mirrors it if we may write. */
    private suspend fun rememberDuration(episodeId: String, seconds: Int) {
        val episode = byId(episodeId) ?: return
        if (!recordEpisodeDuration(episode, seconds)) return
        episodes = episodes.map {
            if (it.id == episodeId) it.copy(durationSeconds = seconds) else it
        }
        // Durations are learned locally and do not move the catalog's refresh
        // stamp, so without this the cache keeps serving the old value and
        // every reload forgets the runtime it just measured.
        cache.write(meta?.lastRefreshAtMillis, episodes)
    }

    // -------------------------------------------------------------- derived

    val seasons: List<Int> get() = CatalogQueries.seasons(episodes)

    val stats: WatchStats get() = WatchStats.of(episodes, progress)

    val continueWatching: Episode? get() = CatalogQueries.continueWatching(episodes, progress)

    val defaultSeason: Int? get() = CatalogQueries.defaultSeason(episodes, progress)

    fun season(number: Int): List<Episode> = CatalogQueries.season(episodes, number)

    fun byId(id: String): Episode? = CatalogQueries.byId(episodes, id)

    fun next(of: Episode): Episode? = CatalogQueries.next(episodes, of)

    fun previous(of: Episode, count: Int): List<Episode> =
        CatalogQueries.previous(episodes, of, count)

    fun upcoming(of: Episode, count: Int): List<Episode> =
        CatalogQueries.upcoming(episodes, of, count)

    /** Episodes started but not finished, most recent first. */
    fun inProgress(limit: Int): List<Episode> = progress.values
        .filter { it.isStarted }
        .sortedByDescending { it.lastWatchedAtMillis }
        .mapNotNull { byId(it.episodeId) }
        .take(limit)
}
