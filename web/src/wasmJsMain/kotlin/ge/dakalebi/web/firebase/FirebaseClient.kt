package ge.dakalebi.web.firebase

/** Lazily-initialised Firebase singletons for the wasm app. Mirrors the root's Firebase. */
internal object FirebaseWasm {
    private val app: FirebaseApp by lazy {
        initializeApp(
            firebaseOptions(
                apiKey = FirebaseConfig.API_KEY,
                authDomain = FirebaseConfig.AUTH_DOMAIN,
                projectId = FirebaseConfig.PROJECT_ID,
                storageBucket = FirebaseConfig.STORAGE_BUCKET,
                messagingSenderId = FirebaseConfig.MESSAGING_SENDER_ID,
                appId = FirebaseConfig.APP_ID,
            ),
        )
    }

    val auth: Auth by lazy { getAuth(app) }
}
