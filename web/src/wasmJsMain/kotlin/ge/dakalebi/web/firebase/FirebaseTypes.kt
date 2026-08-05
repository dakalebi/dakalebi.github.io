package ge.dakalebi.web.firebase

import kotlin.js.Promise

/**
 * Typed structural shapes for the Firebase JS SDK (v12, modular) on wasmJs.
 *
 * The one hard difference from the root app's js(IR) externals: Kotlin/Wasm has no
 * `dynamic`, so everything crossing the JS boundary is a typed `external interface`
 * extending `JsAny`. No `.data(): dynamic`, no `js("{}").unsafeCast()`. The `@JsModule`
 * imports that must be resolved from the npm package live in the sibling `*Module.kt`.
 */

external interface FirebaseApp : JsAny

external interface FirebaseOptions : JsAny

external interface FirebaseUser : JsAny {
    val uid: String
    val email: String?
    val emailVerified: Boolean
}

external interface UserCredential : JsAny {
    val user: FirebaseUser
}

external interface Auth : JsAny {
    val currentUser: FirebaseUser?
}

// ------------------------------------------------------------------------ firestore

external interface Firestore : JsAny

external interface DocumentReference : JsAny

external interface Query : JsAny

/** A collection is itself a valid [Query], so `getDocs(collection(...))` works. */
external interface CollectionReference : Query

external interface DocumentSnapshot : JsAny {
    val id: String
    fun exists(): Boolean

    /** Undefined for a document that does not exist, hence nullable. */
    fun data(): JsAny?
}

external interface QueryDocumentSnapshot : JsAny {
    val id: String
    fun data(): JsAny
}

/**
 * `fromCache` is the only way to tell "the server says there is nothing" from "we never
 * reached the server". Firestore resolves an offline read instead of rejecting it, so
 * without this an unreachable backend is indistinguishable from an empty collection.
 */
external interface SnapshotMetadata : JsAny {
    val fromCache: Boolean
    val hasPendingWrites: Boolean
}

external interface QuerySnapshot : JsAny {
    val size: Int
    val empty: Boolean

    /** `JsArray`, not `Array`: a JS array crosses the wasm boundary as itself. */
    val docs: JsArray<QueryDocumentSnapshot>
    val metadata: SnapshotMetadata
}

external interface WriteBatch : JsAny {
    fun set(ref: DocumentReference, data: JsAny, options: JsAny): WriteBatch
    fun delete(ref: DocumentReference): WriteBatch
    fun commit(): Promise<JsAny?>
}

/**
 * Builds the options object as a JS object literal.
 *
 * The js(IR) actual did `js("{}").unsafeCast<FirebaseOptions>().apply { ... }`; wasm's
 * `js(...)` must be a single constant expression, so the whole literal is built in one
 * shot with the parameters spliced in by name.
 */
internal fun firebaseOptions(
    apiKey: String,
    authDomain: String,
    projectId: String,
    storageBucket: String,
    messagingSenderId: String,
    appId: String,
): FirebaseOptions = js("({ apiKey: apiKey, authDomain: authDomain, projectId: projectId, storageBucket: storageBucket, messagingSenderId: messagingSenderId, appId: appId })")
