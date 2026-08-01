package ge.dakalebi.domain.repository

import ge.dakalebi.domain.model.Account
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

    /** False when the write was rejected — offline, or the rules say no. */
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

    /** Whether this account may rebuild the catalog. The rules still enforce it. */
    fun isAdmin(account: Account): Boolean
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
