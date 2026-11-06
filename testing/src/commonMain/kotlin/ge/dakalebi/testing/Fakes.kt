package ge.dakalebi.testing

import ge.dakalebi.data.AccountSettingsSource
import ge.dakalebi.data.local.KeyValueStore
import ge.dakalebi.domain.Clock
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.SettingsLoad
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.domain.repository.ProgressRepository
import ge.dakalebi.domain.service.caveaOnlyId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** A clock that only moves when a test says so. */
class FakeClock(var now: Double = 1_000_000.0) : Clock {
    override fun nowMillis(): Double = now
}

/** In-memory storage, optionally one that refuses, which real devices do. */
class FakeKeyValueStore(
    private val readsFail: Boolean = false,
    private val writesFail: Boolean = false,
) : KeyValueStore {
    val entries = mutableMapOf<String, String>()

    override fun get(key: String): String? {
        if (readsFail) throw IllegalStateException("storage blocked")
        return entries[key]
    }

    override fun set(key: String, value: String) {
        if (writesFail) throw IllegalStateException("quota exceeded")
        entries[key] = value
    }

    override fun remove(key: String) {
        entries.remove(key)
    }
}

/**
 * One account's settings document, with the same merge semantics as Firestore:
 * a write lays the values it holds over what is stored and leaves the rest.
 * `null` is "no document yet".
 */
class FakeAccountSettings(
    var stored: UserSettings? = null,
    var readsFail: Boolean = false,
    var writesSucceed: Boolean = true,
    /**
     * Fields the rules do not allow yet. Like the real rules, which check the
     * whole document, one of them anywhere in a write refuses all of it.
     */
    private val refused: Set<Setting<*>> = emptySet(),
) : AccountSettingsSource {
    /** Every write attempted, accepted or not. */
    val writes = mutableListOf<UserSettings>()

    /**
     * The live listener. Buffered so [emit] never has to suspend; a collector
     * gets the snapshot when the test dispatcher next runs.
     */
    val snapshots = MutableSharedFlow<UserSettings>(extraBufferCapacity = 16)

    /**
     * When set, a write lands at once but its answer waits for this, like a
     * slow network. Firestore sends a write whether or not anyone waits for the
     * answer, so a caller that stops waiting loses only the confirmation.
     */
    var answer: CompletableDeferred<Unit>? = null

    override suspend fun load(uid: String): SettingsLoad = when {
        readsFail -> SettingsLoad.Failed
        else -> stored?.let { SettingsLoad.Found(it) } ?: SettingsLoad.Missing
    }

    override suspend fun save(uid: String, settings: UserSettings): Boolean {
        writes += settings
        if (!writesSucceed || settings.present.any { it in refused }) return false
        stored = (stored ?: UserSettings()) + settings
        answer?.await()
        return true
    }

    override fun observe(uid: String): Flow<UserSettings> = snapshots

    /** A snapshot of the document as it now stands, as the live listener delivers one. */
    fun emit(settings: UserSettings) {
        check(snapshots.tryEmit(settings)) { "snapshot buffer full" }
    }

    /** Another device writing to the account, then the listener hearing of it. */
    fun changeElsewhere(settings: UserSettings) {
        stored = (stored ?: UserSettings()) + settings
        emit(stored!!)
    }
}

/** In-memory progress storage, so the use cases can be exercised without Firestore. */
class FakeProgressRepository(
    initial: Map<String, WatchProgress> = emptyMap(),
) : ProgressRepository {
    val stored = initial.toMutableMap()
    var saves = 0
        private set

    override suspend fun list(uid: String): List<WatchProgress> = stored.values.toList()

    override suspend fun save(uid: String, progress: WatchProgress): Boolean {
        saves++
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

/** Terse episode builder — the tests care about three fields out of fourteen. */
fun episode(
    season: Int,
    number: Int,
    durationSeconds: Int? = 1_500,
    id: String = "s${season}e$number",
    caveaEpisodeId: String? = null,
) = Episode(
    id = id,
    formulaEpisodeId = number,
    formulaSeasonId = season,
    seasonNumber = season,
    episodeNumber = number,
    title = null,
    thumbnailUrl = null,
    videoUrl = "https://example.invalid/$id.mp4",
    sources = mapOf("720p" to "https://example.invalid/$id.mp4"),
    durationSeconds = durationSeconds,
    episodePageUrl = "https://example.invalid/$id",
    lastResolvedAtMillis = null,
    updatedAtMillis = null,
    caveaEpisodeId = caveaEpisodeId,
)

/** An episode only Cavea Plus has, shaped as the catalog merge builds one. */
fun caveaOnlyEpisode(
    season: Int,
    number: Int,
    caveaEpisodeId: String = "c${season}e$number",
) = Episode(
    id = caveaOnlyId(caveaEpisodeId),
    formulaEpisodeId = null,
    formulaSeasonId = null,
    seasonNumber = season,
    episodeNumber = number,
    title = null,
    thumbnailUrl = "https://example.invalid/$caveaEpisodeId.jpg",
    videoUrl = null,
    sources = emptyMap(),
    durationSeconds = null,
    episodePageUrl = null,
    lastResolvedAtMillis = null,
    updatedAtMillis = null,
    caveaEpisodeId = caveaEpisodeId,
)

fun progress(
    episodeId: String,
    seconds: Int,
    duration: Int? = 1_500,
    watched: Boolean = false,
    at: Double = 0.0,
) = WatchProgress(
    episodeId = episodeId,
    progressSeconds = seconds,
    durationSeconds = duration,
    isWatched = watched,
    lastWatchedAtMillis = at,
)
