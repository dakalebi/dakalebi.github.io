@file:JsModule("firebase/auth")

package ge.dakalebi.firebase.externals

import kotlin.js.Promise

external fun getAuth(app: FirebaseApp): Auth

external fun onAuthStateChanged(auth: Auth, next: (FirebaseUser?) -> Unit): Unsubscribe

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

/**
 * Popup — not redirect. `signInWithRedirect` breaks when the auth domain differs
 * from the app's origin (our case: `*.firebaseapp.com` vs `*.github.io`) because
 * browsers now block the third-party storage access the redirect flow relies on.
 */
external fun signInWithPopup(auth: Auth, provider: AuthProvider): Promise<UserCredential>

external fun signOut(auth: Auth): Promise<Unit>

external fun sendPasswordResetEmail(auth: Auth, email: String): Promise<Unit>

external class GoogleAuthProvider : AuthProvider {
    fun setCustomParameters(params: dynamic)
}
