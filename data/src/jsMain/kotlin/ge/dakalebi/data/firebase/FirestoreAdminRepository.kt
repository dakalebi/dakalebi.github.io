package ge.dakalebi.data.firebase

import ge.dakalebi.core.Log
import ge.dakalebi.data.firebase.externals.doc
import ge.dakalebi.data.firebase.externals.getDoc
import ge.dakalebi.domain.repository.AdminRepository
import kotlinx.coroutines.await

/**
 * Membership is the existence of `admins/{uid}`.
 *
 * That collection is readable only by the account it names and **writable by
 * nobody** — entries are created by hand in the Firebase console. That
 * write-deny is the whole design. The obvious alternative, a flag on
 * `users/{uid}`, cannot work: that document has to be writable by its owner
 * for their settings, so an `isAdmin` field on it would be a field the user
 * could set on themselves.
 *
 * The rules re-check this with `exists()` on every write — which runs
 * server-side and bypasses the rules, so it can see a document the requesting
 * client cannot read. A forged `true` on this side buys nothing.
 *
 * A custom auth claim is the more conventional answer, but setting one needs
 * the Admin SDK: a service account and a server this app deliberately has not
 * got.
 */
class FirestoreAdminRepository : AdminRepository {

    override suspend fun isAdmin(uid: String): Boolean = runCatching {
        getDoc(doc(Firebase.db, ADMINS, uid)).await().exists()
    }.onFailure {
        // A denied read means the rules and this path disagree; without the log
        // the only symptom is a refresh button that quietly went away.
        Log.w("admin", "could not check admin status for $uid", it)
    }.getOrDefault(false)

    private companion object {
        const val ADMINS = "admins"
    }
}
