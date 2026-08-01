package ge.dakalebi.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.app.Log
import ge.dakalebi.app.Toasts
import ge.dakalebi.app.nowMillis
import ge.dakalebi.i18n.S

/**
 * In-memory view of the catalog and the signed-in user's progress.
 *
 * Replaces react-query from the original. The data set is small (932 episodes,
 * one document each) and changes rarely, so it is loaded once per session and
 * mutated locally alongside the Firestore write rather than refetched.
 */
object Library {
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

    var refreshNote: String? by mutableStateOf(null)
        private set

    var loadError: String? by mutableStateOf(null)
        private set

    private var loadedFor: String? = null

    // -------------------------------------------------------------- loading

    suspend fun ensureLoaded(uid: String) {
        // `loadError != null` deliberately re-enters. Without it a single failed
        // read latched permanently: the guard matched on every later call, so
        // the retry button — and any navigation — would have been a no-op.
        if (loadedFor == uid && !loading && loadError == null) return
        loadedFor = uid
        loading = true
        loadError = null
        try {
            episodes = EpisodeRepository.listEpisodes()
            progress = ProgressRepository.list(uid).associateBy { it.episodeId }
            meta = loadMeta()
        } catch (e: Throwable) {
            Log.e("library", "catalog load failed for uid=$uid", e)
            loadError = e.message ?: S.dataLoadFailed
        } finally {
            loading = false
        }
    }

    /**
     * Metadata is decoration — the last-refreshed line in the menu. Losing it
     * must not fail the load, but it should still say so: a permission error
     * here means the rules are wrong for `meta/catalog` too.
     */
    private suspend fun loadMeta(): CatalogMeta? = runCatching {
        EpisodeRepository.getCatalogMeta()
    }.onFailure { Log.w("library", "catalog meta unavailable", it) }.getOrNull()

    fun reset() {
        loadedFor = null
        episodes = emptyList()
        progress = emptyMap()
        meta = null
        loading = true
        loadError = null
    }

    // ------------------------------------------------------------ mutations

    suspend fun refreshCatalog() {
        if (refreshing) return
        refreshing = true
        refreshNote = S.refreshingEpisodes
        try {
            val result = EpisodeRepository.refreshCatalog(nowMillis()) { done, total ->
                refreshNote = S.refreshSeasonProgress(done, total)
            }
            episodes = EpisodeRepository.listEpisodes()
            meta = loadMeta()
            Toasts.ok(S.refreshed(result.episodes, result.written, result.withoutVideo))
        } catch (e: Throwable) {
            // Surface the raw failure: the mapped Georgian text hides which
            // stage broke, and this runs only in the browser.
            Log.e("library", "catalog refresh failed", e)
            Toasts.error(refreshErrorMessage(e))
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
        val existing = progress[episodeId]
        val watched = ProgressRepository.save(
            uid = uid,
            episodeId = episodeId,
            progressSeconds = progressSeconds,
            durationSeconds = durationSeconds,
            isWatched = isWatched,
            allowReset = allowReset,
            existing = existing,
        )
        putLocal(
            WatchProgress(
                episodeId = episodeId,
                progressSeconds = progressSeconds,
                durationSeconds = durationSeconds ?: existing?.durationSeconds,
                isWatched = watched,
                lastWatchedAtMillis = nowMillis(),
            ),
        )
        // Formula never reports duration, so the player is the only source.
        if (durationSeconds != null && durationSeconds > 0) {
            rememberDuration(episodeId, durationSeconds)
        }
    }

    suspend fun clearProgress(uid: String, episodeId: String) {
        ProgressRepository.delete(uid, episodeId)
        progress = progress - episodeId
    }

    suspend fun markSeasonWatched(uid: String, season: Int) {
        val inSeason = episodes.filter { it.seasonNumber == season }
        ProgressRepository.markSeasonWatched(uid, inSeason)
        val now = nowMillis()
        val updated = progress.toMutableMap()
        for (episode in inSeason) {
            updated[episode.id] = WatchProgress(
                episodeId = episode.id,
                progressSeconds = episode.durationSeconds ?: 0,
                durationSeconds = episode.durationSeconds,
                isWatched = true,
                lastWatchedAtMillis = now,
            )
        }
        progress = updated
    }

    suspend fun resetSeason(uid: String, season: Int) {
        val ids = episodes.filter { it.seasonNumber == season }.map { it.id }
        ProgressRepository.deleteMany(uid, ids)
        progress = progress - ids.toSet()
    }

    suspend fun resetAll(uid: String) {
        ProgressRepository.deleteAll(uid)
        progress = emptyMap()
    }

    /** Caches a duration learned from the player, and mirrors it if we may write. */
    private suspend fun rememberDuration(episodeId: String, seconds: Int) {
        val episode = episodes.firstOrNull { it.id == episodeId } ?: return
        if (episode.durationSeconds == seconds) return
        episodes = episodes.map { if (it.id == episodeId) it.copy(durationSeconds = seconds) else it }
        EpisodeRepository.recordDuration(episodeId, seconds)
    }

    fun putLocal(entry: WatchProgress) {
        progress = progress + (entry.episodeId to entry)
    }

    /** Applies a freshly resolved video URL without a round-trip. */
    fun putEpisode(episode: Episode) {
        episodes = episodes.map { if (it.id == episode.id) episode else it }
    }

    // ------------------------------------------------------------- derived

    val seasons: List<Int> get() = episodes.map { it.seasonNumber }.distinct().sorted()

    fun season(number: Int): List<Episode> =
        episodes.filter { it.seasonNumber == number }.sortedBy { it.episodeNumber }

    fun byId(id: String): Episode? = episodes.firstOrNull { it.id == id }

    /** The episode after this one, crossing into the next season when needed. */
    fun next(of: Episode): Episode? =
        episodes.filter { it.ordinal > of.ordinal }.minByOrNull { it.ordinal }

    fun previous(of: Episode, count: Int): List<Episode> =
        episodes.filter { it.ordinal < of.ordinal }.sortedByDescending { it.ordinal }
            .take(count).reversed()

    fun upcoming(of: Episode, count: Int): List<Episode> =
        episodes.filter { it.ordinal > of.ordinal }.sortedBy { it.ordinal }.take(count)

    /**
     * What the hero offers: the most recent episode left unfinished, falling
     * back to the most recent activity of any kind, then to the very first
     * episode for a brand-new account.
     */
    val continueWatching: Episode?
        get() {
            val recent = progress.values.sortedByDescending { it.lastWatchedAtMillis }
            val unfinished = recent.firstOrNull { !it.isWatched && it.progressSeconds > 5 }
            val chosen = unfinished ?: recent.firstOrNull()
            return chosen?.let { byId(it.episodeId) }
                ?: episodes.minByOrNull { it.ordinal }
        }

    val watchedCount: Int get() = progress.values.count { it.isWatched }
    val startedCount: Int get() = progress.values.count { it.isStarted }

    val percentWatched: Int
        get() = if (episodes.isEmpty()) 0 else (watchedCount * 100) / episodes.size

    /** Season the dashboard should open on: where the user last was. */
    val defaultSeason: Int?
        get() {
            val recent = progress.values.sortedByDescending { it.lastWatchedAtMillis }
            for (entry in recent) {
                val episode = byId(entry.episodeId)
                if (episode != null) return episode.seasonNumber
            }
            return seasons.lastOrNull()
        }
}

private fun refreshErrorMessage(error: Throwable): String {
    val text = (error.message ?: "").lowercase()
    val code = Log.codeOf(error).orEmpty()
    return when {
        // The allowlist is keyed on the verified email, not a UID — the old
        // wording sent anyone hitting this to look in the wrong place.
        code == "permission-denied" || "permission" in text || "insufficient" in text ->
            S.refreshNoPermission
        "quota" in text || "resource-exhausted" in code -> S.refreshQuota
        "failed to fetch" in text || "network" in text || "unavailable" in code ->
            S.refreshNetwork
        else -> S.refreshFailed
    }
}
