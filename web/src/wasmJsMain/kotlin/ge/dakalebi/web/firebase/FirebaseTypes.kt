package ge.dakalebi.web.firebase

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
