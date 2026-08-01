package ge.dakalebi.data

import ge.dakalebi.app.Log
import ge.dakalebi.firebase.Firebase
import ge.dakalebi.firebase.externals.doc
import ge.dakalebi.firebase.externals.getDoc
import kotlinx.coroutines.await

/**
 * Who may rebuild the episode catalog.
 *
 * Membership is the existence of `admins/{uid}`. That collection is readable
 * only by the account it names and **writable by nobody** — documents are
 * created by hand in the Firebase console. That is the entire point: a flag a
 * user can write is a flag a user can grant themselves, so it cannot live on
 * `users/{uid}` next to their own settings.
 *
 * This client-side lookup only decides whether to draw the refresh control.
 * The rules re-check it independently with `exists()` on every write, so a
 * forged `true` here buys nothing.
 *
 * The alternative is a custom auth claim, which is the more conventional
 * answer, but setting one needs the Admin SDK — a service account and a server
 * this app deliberately does not have.
 */
object AdminRepository {
    private const val ADMINS = "admins"

    suspend fun isAdmin(uid: String): Boolean = runCatching {
        getDoc(doc(Firebase.db, ADMINS, uid)).await().exists()
    }.onFailure {
        // A denied read here means the rules and this path disagree; without
        // the log the only symptom is a refresh button that quietly went away.
        Log.w("admin", "could not check admin status for $uid", it)
    }.getOrDefault(false)
}
