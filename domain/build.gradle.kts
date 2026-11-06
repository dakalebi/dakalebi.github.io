// The domain: models, repository interfaces, a use case per operation, and pure
// queries over them.
//
// One dependency, kotlinx-coroutines, for `Flow` and `StateFlow`: a repository
// that reports changes says so in its type instead of taking a callback. Nothing
// else, and that is the point. "The domain names no framework" used to be a grep
// in docs/ARCHITECTURE.md. Now it is mostly the compile classpath: a Compose,
// Firebase, serialization or `i18n` import does not resolve. The browser APIs are
// the exception. Kotlin/JS ships them with the standard library, so no build file
// can keep them out, and the grep stays for those.
//
// Plugin versions come from the root build file, which resolves them for the
// whole build. Adding a version here would let the two drift.
plugins {
    kotlin("multiplatform")
}

kotlin {
    js(IR) {
        // As in `:presentation`: the browser sub-target exists so the root module's
        // webpack build can consume this klib, and tests run on Node.
        browser {
            testTask { enabled = false }
        }
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            // `api`: the repository interfaces return `Flow` and `StateFlow`, so
            // every implementation and every caller needs the types.
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}
