package ge.dakalebi.web.di

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.model.Catalog
import ge.dakalebi.domain.model.CatalogMeta
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.model.SettingsLoad
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.AccountRepository
import ge.dakalebi.domain.repository.AdminRepository
import ge.dakalebi.domain.repository.CatalogCache
import ge.dakalebi.domain.repository.CatalogRepository
import ge.dakalebi.domain.repository.ProgressRepository
import ge.dakalebi.domain.repository.SettingsRepository
import ge.dakalebi.web.device.WasmClock

/**
 * The app with fixtures instead of Firebase, reached with `?demo` in the URL.
 *
 * It exists because every screen except sign-in is behind an account, so without it the signed-in
 * half of the app cannot be looked at — not in a browser, not in a screenshot, not by anyone who
 * has not been given a login. The DOM app grew the same seam for its TV UI and for the same reason.
 *
 * Not a test double standing in for a missing test: the data layer has its own contract, and this
 * only exercises the screens. It is wired here rather than in a test source set precisely so it
 * ships and can be opened.
 */
internal fun demoGraph(): WebGraph {
    val episodes = demoEpisodes()
    val progress = demoProgress(episodes)
    return WebGraph(
        clock = WasmClock,
        catalogCache = NoCache,
        catalogRepository = DemoCatalogRepository(episodes),
        progressRepository = DemoProgressRepository(progress),
        settingsRepository = DemoSettingsRepository,
        accountRepository = DemoAccountRepository,
        adminRepository = DemoAdminRepository,
    )
}

/** Five seasons of a dozen, which is enough to show a season strip that has to scroll. */
private fun demoEpisodes(): List<Episode> = (1..5).flatMap { season ->
    (1..12).map { number ->
        val id = season * 100 + number
        Episode(
            id = id.toString(),
            formulaEpisodeId = id,
            formulaSeasonId = season,
            seasonNumber = season,
            episodeNumber = number,
            title = null,
            // One real still, on every other episode: enough to see the image layer working and
            // the gradient stand-in beside it, which is what the grid looks like in practice.
            thumbnailUrl = if (number % 2 == 0) DEMO_STILL else null,
            videoUrl = DEMO_VIDEO,
            sources = mapOf("720p" to DEMO_VIDEO, "480p" to DEMO_VIDEO),
            durationSeconds = 1_500 + number * 7,
            episodePageUrl = "https://formulacreative.ge/episode/$id",
            lastResolvedAtMillis = null,
            updatedAtMillis = null,
        )
    }
}

/**
 * A real, small MP4 served with CORS and byte ranges.
 *
 * The player has to be pointed at something that actually decodes, or the one thing this fixture
 * exists to show — a `<video>` under the canvas, with Compose chrome over it — cannot be seen at
 * all.
 */
private const val DEMO_VIDEO =
    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"

/**
 * A real episode still from the provider's CDN.
 *
 * Deliberately the genuine article rather than a placeholder service: this is the one asset whose
 * delivery has a constraint worth exercising — no CORS headers — and the whole point of the image
 * layer is that it copes with exactly this URL.
 */
private const val DEMO_STILL = "https://cdn.formula.ge/tvseries/series_1/season_01/episode_1/" +
    "10c81689-1a2f-4320-aa00-2110ebae3a3a_KasqBSlHyyc.jpg"

/** Season one finished, season two part-watched: enough to fill continue-watching and the stats. */
private fun demoProgress(episodes: List<Episode>): MutableMap<String, WatchProgress> {
    val entries = mutableMapOf<String, WatchProgress>()
    episodes.filter { it.seasonNumber == 1 }.forEach {
        entries[it.id] = WatchProgress(
            episodeId = it.id,
            progressSeconds = it.durationSeconds ?: 0,
            durationSeconds = it.durationSeconds,
            isWatched = true,
            lastWatchedAtMillis = 1_700_000_000_000.0,
        )
    }
    episodes.filter { it.seasonNumber == 2 && it.episodeNumber <= 3 }.forEach {
        entries[it.id] = WatchProgress(
            episodeId = it.id,
            progressSeconds = (it.durationSeconds ?: 0) / 3,
            durationSeconds = it.durationSeconds,
            isWatched = false,
            lastWatchedAtMillis = 1_700_000_100_000.0 + it.episodeNumber,
        )
    }
    return entries
}

private class DemoCatalogRepository(private val episodes: List<Episode>) : CatalogRepository {
    override suspend fun load() = Catalog(episodes, getMeta())

    override suspend fun listEpisodes() = episodes

    override suspend fun getMeta() = CatalogMeta(
        lastRefreshAtMillis = 1_700_000_000_000.0,
        seasonCount = episodes.map { it.seasonNumber }.distinct().size,
        episodeCount = episodes.size,
    )

    override suspend fun refresh(
        nowMillis: Double,
        onProgress: (done: Int, total: Int) -> Unit,
    ): RefreshResult {
        onProgress(1, 1)
        return RefreshResult(1, episodes.size, 0, 0, episodes)
    }

    override suspend fun resolveVideo(episode: Episode) = episode

    override suspend fun recordDuration(episodeId: String, durationSeconds: Int) = Unit
}

private class DemoProgressRepository(
    private val entries: MutableMap<String, WatchProgress>,
) : ProgressRepository {
    override suspend fun list(uid: String) = entries.values.toList()

    override suspend fun save(uid: String, progress: WatchProgress): Boolean {
        entries[progress.episodeId] = progress
        return progress.isWatched
    }

    override suspend fun delete(uid: String, episodeId: String) {
        entries.remove(episodeId)
    }

    override suspend fun markWatched(uid: String, episodes: List<Episode>, nowMillis: Double) {
        episodes.forEach {
            entries[it.id] = WatchProgress(it.id, it.durationSeconds ?: 0, it.durationSeconds, true, nowMillis)
        }
    }

    override suspend fun deleteMany(uid: String, episodeIds: List<String>) {
        episodeIds.forEach { entries.remove(it) }
    }

    override suspend fun deleteAll(uid: String) = entries.clear()
}

private object DemoSettingsRepository : SettingsRepository {
    override suspend fun load(uid: String) = SettingsLoad.Found(UserSettings("ka", true))

    override suspend fun save(uid: String, settings: UserSettings) = true

    override fun observe(uid: String, onChange: (UserSettings) -> Unit): () -> Unit = {}
}

private object DemoAccountRepository : AccountRepository {
    override fun observe(onChange: (Account?) -> Unit) {
        onChange(Account(uid = "demo", email = "demo@dakalebi.ge", emailVerified = true))
    }

    override suspend fun signIn(email: String, password: String) = Unit
    override suspend fun signUp(email: String, password: String) = Unit
    override suspend fun signInWithGoogle() = Unit
    override suspend fun sendPasswordReset(email: String) = Unit

    /** Signs nothing out: there is no session here, and reloading is what resets the fixture. */
    override suspend fun signOut() = Unit
}

/** Admin, so the refresh action is on screen where it can be looked at. */
private object DemoAdminRepository : AdminRepository {
    override suspend fun isAdmin(uid: String) = true
}

/** Caching a fixture would only let a stale copy outlive an edit to it. */
private object NoCache : CatalogCache {
    override fun read(stamp: Double?): List<Episode>? = null
    override fun write(stamp: Double?, episodes: List<Episode>) = Unit
    override fun clear() = Unit
}
