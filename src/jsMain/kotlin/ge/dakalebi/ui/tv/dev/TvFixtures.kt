package ge.dakalebi.ui.tv.dev

import ge.dakalebi.data.local.BrowserPreferencesRepository
import ge.dakalebi.di.AppGraph
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
import kotlinx.browser.window
import org.w3c.dom.url.URLSearchParams

/**
 * A whole app graph with no Firebase behind it.
 *
 * There is no Firebase session in an automated browser and signing in there is not
 * possible, so without this every TV screen past the sign-in form could only ever
 * be looked at on a real television. That is not a standard worth accepting for a
 * UI whose entire interaction model is new.
 *
 * The seam is already in the design: [AppGraph] takes every repository as a
 * constructor parameter with a default, so this replaces the outside world and
 * nothing else. The stores, the use cases and the screens are the real ones.
 *
 * Reachable at `/tv/?ui=tv-demo`. It ships in the bundle — a few hundred lines of
 * fake data inside 1.9MB is a fair price for a TV UI that is otherwise unverifiable.
 */
internal fun tvFixtureGraph(): AppGraph {
    val episodes = fixtureEpisodes()
    return AppGraph(
        catalogCache = NoCatalogCache,
        catalogRepository = FixtureCatalogRepository(episodes),
        progressRepository = FixtureProgressRepository(fixtureProgress(episodes)),
        settingsRepository = FixtureSettingsRepository(),
        // `?ui=tv-demo&signedout` starts with no account, so the sign-in screen —
        // otherwise unreachable without a real recomposition to swap it in — renders
        // on the first paint and can be driven like every other TV screen.
        accountRepository = FixtureAccountRepository(startSignedOut = fixtureSignedOut()),
        adminRepository = FixtureAdminRepository,
        // The real one: per-device preferences are `localStorage`, which the
        // fixture has as much right to as the app does, and it makes the
        // autoplay switch behave like the real thing across a reload.
        preferencesRepository = BrowserPreferencesRepository(),
    )
}

/**
 * Three seasons of eight, which is enough to exercise every shape: a rail that
 * overflows, a grid that wraps, and season chips that scroll.
 *
 * Thumbnails are deliberately null so the deterministic gradient stands in, which
 * keeps the fixture from depending on the network.
 */
private fun fixtureEpisodes(): List<Episode> = buildList {
    for (season in 1..3) {
        for (number in 1..8) {
            val id = "${season}0$number"
            add(
                Episode(
                    id = id,
                    formulaEpisodeId = id.toInt(),
                    formulaSeasonId = season,
                    seasonNumber = season,
                    episodeNumber = number,
                    title = null,
                    thumbnailUrl = null,
                    videoUrl = "https://example.invalid/$id.mp4",
                    sources = mapOf(
                        "1080p" to "https://example.invalid/$id-1080.mp4",
                        "720p" to "https://example.invalid/$id-720.mp4",
                    ),
                    durationSeconds = 1_500 + number * 37,
                    episodePageUrl = "https://example.invalid/e/$id",
                    lastResolvedAtMillis = null,
                    updatedAtMillis = null,
                ),
            )
        }
    }
}

/**
 * Enough history for the hero and the continue rail to have something to show:
 * one part-watched episode recently, two finished before it, and one barely
 * started — which is the row the five-second floor is supposed to ignore.
 */
private fun fixtureProgress(episodes: List<Episode>): Map<String, WatchProgress> {
    fun at(season: Int, number: Int) = episodes.first { it.seasonNumber == season && it.episodeNumber == number }
    val second = 1_000.0
    return listOf(
        WatchProgress(at(2, 4).id, 620, at(2, 4).durationSeconds, false, 1_700_000_000 * second),
        WatchProgress(at(2, 3).id, 1_500, at(2, 3).durationSeconds, true, 1_699_000_000 * second),
        WatchProgress(at(2, 2).id, 1_500, at(2, 2).durationSeconds, true, 1_698_000_000 * second),
        WatchProgress(at(3, 1).id, 3, at(3, 1).durationSeconds, false, 1_700_500_000 * second),
    ).associateBy { it.episodeId }
}

private class FixtureCatalogRepository(private val episodes: List<Episode>) : CatalogRepository {
    override suspend fun load() = Catalog(episodes, CatalogMeta(1_700_000_000_000.0, 3, episodes.size))
    override suspend fun listEpisodes() = episodes
    override suspend fun getMeta() = CatalogMeta(1_700_000_000_000.0, 3, episodes.size)

    override suspend fun refresh(nowMillis: Double, onProgress: (Int, Int) -> Unit) =
        RefreshResult(3, episodes.size, 0, 0, episodes)

    override suspend fun resolveVideo(episode: Episode) = episode
    override suspend fun recordDuration(episodeId: String, durationSeconds: Int) = Unit
}

private class FixtureProgressRepository(initial: Map<String, WatchProgress>) : ProgressRepository {
    private val stored = initial.toMutableMap()

    override suspend fun list(uid: String) = stored.values.toList()

    override suspend fun save(uid: String, progress: WatchProgress): Boolean {
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

/** In memory, so flipping a switch sticks for the session and syncs nowhere. */
private class FixtureSettingsRepository : SettingsRepository {
    private var stored = UserSettings(language = null, autoplayNext = true)

    override suspend fun load(uid: String): SettingsLoad = SettingsLoad.Found(stored)

    override suspend fun save(uid: String, settings: UserSettings): Boolean {
        stored = UserSettings(
            language = settings.language ?: stored.language,
            autoplayNext = settings.autoplayNext ?: stored.autoplayNext,
        )
        return true
    }

    override fun observe(uid: String, onChange: (UserSettings) -> Unit): () -> Unit = {}
}

/**
 * Reports a signed-in account immediately, and sign-out really signs out — so the
 * auth gate and the sign-in screen are both reachable in the fixture rather than
 * being the one thing it cannot show.
 */
/** Whether `?ui=tv-demo&signedout` asked the fixture to start with no account. */
private fun fixtureSignedOut(): Boolean =
    runCatching { URLSearchParams(window.location.search).has("signedout") }.getOrDefault(false)

private class FixtureAccountRepository(startSignedOut: Boolean) : AccountRepository {
    private var listener: ((Account?) -> Unit)? = null
    private var account: Account? = if (startSignedOut) null else DEMO

    override fun observe(onChange: (Account?) -> Unit) {
        listener = onChange
        onChange(account)
    }

    override suspend fun signIn(email: String, password: String) {
        account = DEMO
        listener?.invoke(account)
    }

    override suspend fun signUp(email: String, password: String) = signIn(email, password)
    override suspend fun signInWithGoogle() = signIn("", "")
    override suspend fun sendPasswordReset(email: String) = Unit

    override suspend fun signOut() {
        account = null
        listener?.invoke(null)
    }

    private companion object {
        val DEMO = Account(uid = "demo", email = "demo@example.invalid", emailVerified = true)
    }
}

/** Not an admin: the TV UI has no admin surface, and this proves none appears. */
private object FixtureAdminRepository : AdminRepository {
    override suspend fun isAdmin(uid: String) = false
}

/** Caching a fake catalog would only let a stale fixture outlive a code change. */
private object NoCatalogCache : CatalogCache {
    override fun read(stamp: Double?): List<Episode>? = null
    override fun write(stamp: Double?, episodes: List<Episode>) = Unit
    override fun clear() = Unit
}
