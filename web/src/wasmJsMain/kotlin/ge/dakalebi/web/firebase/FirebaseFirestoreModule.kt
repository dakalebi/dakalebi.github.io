@file:JsModule("firebase/firestore")

package ge.dakalebi.web.firebase

import kotlin.js.Promise

external fun getFirestore(app: FirebaseApp): Firestore

/**
 * Paths are slash-separated, so `collection(db, "users/$uid/progress")` reaches a
 * subcollection.
 *
 * The js(IR) externals took `vararg pathSegments` and let the SDK join them. Wasm interop
 * has no vararg, and the SDK documents the single-string form as equivalent, so callers
 * build the path. That is why every id spliced into one is Firestore's own document id —
 * a uid or an episode number — and never free text.
 */
external fun collection(db: Firestore, path: String): CollectionReference

external fun doc(db: Firestore, path: String): DocumentReference

external fun getDoc(reference: DocumentReference): Promise<DocumentSnapshot>

external fun getDocs(query: Query): Promise<QuerySnapshot>

/** See `setOptions`: wasm resolves one import per declaration, so options are never omitted. */
external fun setDoc(
    reference: DocumentReference,
    data: JsAny,
    options: JsAny,
): Promise<JsAny?>

external fun deleteDoc(reference: DocumentReference): Promise<JsAny?>

/** Firestore caps a batch at 500 writes; callers must chunk. */
external fun writeBatch(db: Firestore): WriteBatch

/**
 * Live document listener, returning JS's own unsubscribe function.
 *
 * Typed as [JsAny] rather than `() -> Unit` because a JS function cannot cross into wasm as
 * a Kotlin function type. It is called through `callUnsubscribe`, and it genuinely has to
 * be: a listener left running after sign-out keeps retrying a read the account is no longer
 * allowed to make.
 */
external fun onSnapshot(
    reference: DocumentReference,
    onNext: (DocumentSnapshot) -> Unit,
    onError: (JsAny) -> Unit,
): JsAny
