package ge.dakalebi.domain.repository

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.model.Catalog
import ge.dakalebi.domain.model.CatalogMeta
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.model.SettingsLoad
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.model.WatchProgress

/**
 * Everything the app needs from the outside world, stated as the domain sees
 * it. Firestore, the Formula API and `localStorage` all live behind these; no
 * file in `domain` or `presentation` names any of them.
 *
 * They are declared together in one file deliberately. Five interfaces of a
 * dozen lines each, read as a set, are the contract for the entire application
 * — spread across five files that is five times the navigation for the same
 * information.
 */

interface CatalogRepository {
    /**
     * Episodes and metadata together.
     *
     * One call rather than two because the order matters: the metadata's
     * refresh stamp is what says whether a cached catalog is still good, so it
     * has to be read first. One document read then stands in for 932.
     */
    suspend fun load(): Catalog

    /**
     * The whole catalog, ordered.
     *
     * Throws [ge.dakalebi.domain.model.CatalogUnavailableException] when the
     * backend could not be reached, which must not be confused with an empty
     * catalog — one is a network problem, the other is a screen telling the
     * viewer to wait for an admin.
     */
    suspend fun listEpisodes(): List<Episode>

    suspend fun getMeta(): CatalogMeta?

    /**
     * Rebuilds the catalog from the provider and writes back only what changed.
     * [onProgress] reports (done, total) seasons so the UI can count.
     */
    suspend fun refresh(
        nowMillis: Double,
        onProgress: (done: Int, total: Int) -> Unit,
    ): RefreshResult

    /** Re-reads one episode's video URLs, falling back to the stored copy. */
    suspend fun resolveVideo(episode: Episode): Episode

    /** Persists a duration the player discovered. Silently ignored for non-admins. */
    suspend fun recordDuration(episodeId: String, durationSeconds: Int)
}

interface ProgressRepository {
    suspend fun list(uid: String): List<WatchProgress>

    /** Returns the watched flag as stored, which may differ from what was asked. */
    suspend fun save(uid: String, progress: WatchProgress): Boolean

    suspend fun delete(uid: String, episodeId: String)

    suspend fun markWatched(uid: String, episodes: List<Episode>, nowMillis: Double)

    suspend fun deleteMany(uid: String, episodeIds: List<String>)

    suspend fun deleteAll(uid: String)
}

interface SettingsRepository {
    suspend fun load(uid: String): SettingsLoad

    /**
     * Writes the fields that are set and leaves the rest alone.
     *
     * A null field means "no opinion", not "clear it" — two devices changing
     * two different settings must not overwrite each other, and the language
     * picker knows nothing about autoplay. Returns false when the write was
     * rejected: offline, or the rules said no.
     */
    suspend fun save(uid: String, settings: UserSettings): Boolean

    /** Live updates from other devices. Returns the unsubscribe function. */
    fun observe(uid: String, onChange: (UserSettings) -> Unit): () -> Unit
}

interface AccountRepository {
    /** Fires with the restored session, then on every sign-in and sign-out. */
    fun observe(onChange: (Account?) -> Unit)

    suspend fun signIn(email: String, password: String)

    suspend fun signUp(email: String, password: String)

    suspend fun signInWithGoogle()

    suspend fun sendPasswordReset(email: String)

    suspend fun signOut()
}

/**
 * Who may rebuild the catalog.
 *
 * Separate from [AccountRepository] because it is stored separately, and that
 * separation is the security property: an admin flag on the account's own
 * settings document would be a flag the account can set on itself.
 */
interface AdminRepository {
    suspend fun isAdmin(uid: String): Boolean
}

/**
 * Local copy of the catalog, keyed by the server's last-refresh stamp.
 *
 * A port rather than an implementation detail of the repository so the caching
 * decision — which is about cost, not correctness — is visible in the
 * composition root and can be switched off there.
 */
interface CatalogCache {
    /** The cached catalog, but only when [stamp] proves it is still current. */
    fun read(stamp: Double?): List<Episode>?

    fun write(stamp: Double?, episodes: List<Episode>)

    fun clear()
}

/**
 * Per-device preferences. Synchronous by design — these are read during
 * composition and a suspending call would make every screen wait on storage.
 */
interface PreferencesRepository {
    fun autoplayNext(): Boolean
    fun setAutoplayNext(value: Boolean)

    fun useNativePlayer(): Boolean
    fun setUseNativePlayer(value: Boolean)

    fun preferredQuality(): String?
    fun setPreferredQuality(label: String?)

    fun language(): String?
    fun setLanguage(tag: String)

    /** Whether this episode was left playing or paused, for this session only. */
    fun playIntent(episodeId: String): String?
    fun setPlayIntent(episodeId: String, intent: String)

    /** Called when another tab writes to storage, so state can be re-read. */
    fun onExternalChange(listener: () -> Unit)
}
