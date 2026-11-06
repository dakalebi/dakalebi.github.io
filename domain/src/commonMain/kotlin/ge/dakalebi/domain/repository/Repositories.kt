package ge.dakalebi.domain.repository

import ge.dakalebi.domain.model.Account
import ge.dakalebi.domain.model.Catalog
import ge.dakalebi.domain.model.CatalogMeta
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.model.WatchProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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

    /**
     * Remembers a duration the player measured: in this device's copy of the
     * catalog always, and on the server when the rules allow it, which is for
     * admins only. Silently skipped on the server for everyone else.
     */
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

/**
 * The account settings, kept in step between this device and the account.
 *
 * How the two meet is the implementation's business, by the rules in
 * [ge.dakalebi.domain.service.SettingsSync]. What a caller sees is one value that
 * starts as this device's copy and follows the account once one is attached.
 */
interface SettingsRepository {
    /**
     * The values someone actually chose, here or on another device. Starts as
     * this device's copy, so the first frame renders the real values instead of
     * flashing defaults while the network answers.
     */
    val settings: StateFlow<UserSettings>

    /**
     * Keeps this device and [uid]'s account in step until [stop], or until a
     * start for another account. Starting the attached account again does nothing.
     */
    fun start(uid: String)

    /** Detaches. This device's changes the account has not confirmed stay marked for it. */
    fun stop()

    /**
     * Changes [setting] on this device, then records it on the attached account.
     *
     * The change is in [settings] before this first suspends. Returns false when
     * the account write was rejected: the rules said no, or the network failed
     * it. The device keeps the change either way, marked unconfirmed, so the next
     * sync uploads it. Returns true when it landed, or when no account is attached.
     *
     * The write runs in the implementation's own long-lived scope, so a caller
     * that stops waiting does not lose the account's confirmation.
     */
    suspend fun <T : Any> set(setting: Setting<T>, value: T): Boolean
}

interface AccountRepository {
    /** The restored session, then every sign-in and sign-out. Null is nobody. */
    val account: Flow<Account?>

    suspend fun signIn(email: String, password: String)

    suspend fun signUp(email: String, password: String)

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
 * Whether this device chose to continue as a guest.
 *
 * Remembered so a reload lands back in the app rather than on the sign-in form.
 * Synchronous for the same reason as [PreferencesRepository]: the first frame
 * needs the answer.
 */
interface GuestModeRepository {
    fun isGuest(): Boolean
    fun setGuest(value: Boolean)
}

/**
 * Per-device preferences: what describes this screen rather than the person
 * watching it. Anything that describes the person is a [Setting] and syncs.
 *
 * Synchronous by design — these are read during composition and a suspending
 * call would make every screen wait on storage.
 */
interface PreferencesRepository {
    /**
     * How large the television interface is drawn, as a percentage.
     *
     * Per device and deliberately not part of the account's settings, which language and
     * autoplay both are. Those describe a person; this describes a screen. A phone and a
     * television signed in to the same account need different answers, and syncing this
     * would mean choosing a size on one and having it arrive on the other.
     */
    fun interfaceScale(): Int
    fun setInterfaceScale(percent: Int)

    /** Whether this episode was left playing or paused, for this session only. */
    fun playIntent(episodeId: String): String?
    fun setPlayIntent(episodeId: String, intent: String)

    /** Emits when another tab writes to storage, so state can be re-read. */
    val externalChanges: Flow<Unit>
}
