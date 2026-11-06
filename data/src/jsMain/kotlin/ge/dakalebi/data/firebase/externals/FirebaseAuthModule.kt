@file:JsModule("firebase/auth")

package ge.dakalebi.data.firebase.externals

import kotlin.js.Promise

external fun getAuth(app: FirebaseApp): Auth

/**
 * Returns the unsubscribe function, typed as one because the account flow calls
 * it when its collection is cancelled. See `onSnapshot`.
 */
external fun onAuthStateChanged(auth: Auth, next: (FirebaseUser?) -> Unit): () -> Unit

external fun signInWithEmailAndPassword(
    auth: Auth,
    email: String,
    password: String,
): Promise<UserCredential>

external fun createUserWithEmailAndPassword(
    auth: Auth,
    email: String,
    password: String,
): Promise<UserCredential>

external fun signOut(auth: Auth): Promise<Unit>

external fun sendPasswordResetEmail(auth: Auth, email: String): Promise<Unit>
