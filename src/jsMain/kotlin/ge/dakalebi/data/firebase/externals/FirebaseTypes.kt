package ge.dakalebi.data.firebase.externals

import kotlin.js.Promise

/**
 * Structural types for the Firebase JS SDK (v12, modular).
 *
 * These are pure shapes — no `@JsModule`, so they generate no imports. The
 * functions that actually have to be imported live in the sibling
 * `Firebase*Module.kt` files.
 */

external interface FirebaseApp

external interface FirebaseOptions {
    var apiKey: String
    var authDomain: String
    var projectId: String
    var storageBucket: String
    var messagingSenderId: String
    var appId: String
}

fun firebaseOptions(
    apiKey: String,
    authDomain: String,
    projectId: String,
    storageBucket: String,
    messagingSenderId: String,
    appId: String,
): FirebaseOptions = js("{}").unsafeCast<FirebaseOptions>().apply {
    this.apiKey = apiKey
    this.authDomain = authDomain
    this.projectId = projectId
    this.storageBucket = storageBucket
    this.messagingSenderId = messagingSenderId
    this.appId = appId
}

// ---------------------------------------------------------------- auth

external interface FirebaseUser {
    val uid: String
    val email: String?
    val displayName: String?
    val photoURL: String?
    val emailVerified: Boolean
}

external interface UserCredential {
    val user: FirebaseUser
}

external interface Auth {
    val currentUser: FirebaseUser?
}


external interface AuthError {
    val code: String
    val message: String
}

/** Unsubscribe handle returned by `onAuthStateChanged`. */
external interface Unsubscribe

// ------------------------------------------------------------ firestore

external interface Firestore

external interface DocumentReference

external interface Query

/** A collection is itself a valid [Query], so `getDocs(collection(...))` works. */
external interface CollectionReference : Query

external interface QueryConstraint

external interface DocumentSnapshot {
    val id: String
    fun exists(): Boolean
    fun data(): dynamic
}

external interface QueryDocumentSnapshot {
    val id: String
    fun data(): dynamic
}

/**
 * `fromCache` is the only way to tell "the server says there is nothing" from
 * "we never reached the server". Firestore resolves an offline read instead of
 * rejecting it, so without this an unreachable backend is indistinguishable
 * from an empty collection.
 */
external interface SnapshotMetadata {
    val fromCache: Boolean
    val hasPendingWrites: Boolean
}

external interface QuerySnapshot {
    val size: Int
    val empty: Boolean
    val docs: Array<QueryDocumentSnapshot>
    val metadata: SnapshotMetadata
}

external interface WriteBatch {
    fun set(ref: DocumentReference, data: dynamic): WriteBatch
    fun set(ref: DocumentReference, data: dynamic, options: dynamic): WriteBatch
    fun update(ref: DocumentReference, data: dynamic): WriteBatch
    fun delete(ref: DocumentReference): WriteBatch
    fun commit(): Promise<Unit>
}
