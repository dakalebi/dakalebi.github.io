@file:JsModule("firebase/firestore")

package ge.dakalebi.data.firebase.externals

import kotlin.js.Promise

external fun getFirestore(app: FirebaseApp): Firestore

external fun collection(
    db: Firestore,
    path: String,
    vararg pathSegments: String,
): CollectionReference

external fun doc(
    db: Firestore,
    path: String,
    vararg pathSegments: String,
): DocumentReference

external fun getDoc(reference: DocumentReference): Promise<DocumentSnapshot>

/**
 * Live document listener. Returns the unsubscribe function — typed as a plain
 * `() -> Unit` rather than an opaque marker, because this one actually has to
 * be called when the user signs out.
 */
external fun onSnapshot(
    reference: DocumentReference,
    onNext: (DocumentSnapshot) -> Unit,
    onError: (dynamic) -> Unit = definedExternally,
): () -> Unit

external fun getDocs(query: Query): Promise<QuerySnapshot>

external fun setDoc(
    reference: DocumentReference,
    data: dynamic,
    options: dynamic = definedExternally,
): Promise<Unit>

external fun updateDoc(reference: DocumentReference, data: dynamic): Promise<Unit>

external fun deleteDoc(reference: DocumentReference): Promise<Unit>

/** Firestore caps a batch at 500 writes; callers must chunk. */
external fun writeBatch(db: Firestore): WriteBatch

external fun query(base: Query, vararg constraints: QueryConstraint): Query

external fun where(fieldPath: String, opStr: String, value: Any?): QueryConstraint

external fun orderBy(
    fieldPath: String,
    directionStr: String = definedExternally,
): QueryConstraint

external fun limit(count: Int): QueryConstraint

external fun serverTimestamp(): dynamic
