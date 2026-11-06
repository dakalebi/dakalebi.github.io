package ge.dakalebi.data.firebase

import ge.dakalebi.data.firebase.externals.Auth
import ge.dakalebi.data.firebase.externals.FirebaseApp
import ge.dakalebi.data.firebase.externals.Firestore
import ge.dakalebi.data.firebase.externals.firebaseOptions
import ge.dakalebi.data.firebase.externals.getAuth
import ge.dakalebi.data.firebase.externals.getFirestore
import ge.dakalebi.data.firebase.externals.initializeApp

/** Lazily-initialised Firebase singletons. */
object Firebase {
    val app: FirebaseApp by lazy {
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

    val db: Firestore by lazy { getFirestore(app) }
}
