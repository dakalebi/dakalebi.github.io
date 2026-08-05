@file:JsModule("firebase/auth")

package ge.dakalebi.web.firebase

import kotlin.js.Promise

external fun getAuth(app: FirebaseApp): Auth

/** Returns an unsubscribe function (typed loosely as JsAny; the app never calls it). */
external fun onAuthStateChanged(auth: Auth, next: (FirebaseUser?) -> Unit): JsAny

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

// void-returning promises: represented as Promise<JsAny?> since Unit is not a JS type.
external fun signOut(auth: Auth): Promise<JsAny?>

external fun sendPasswordResetEmail(auth: Auth, email: String): Promise<JsAny?>
