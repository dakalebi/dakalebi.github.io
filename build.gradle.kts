import org.jetbrains.kotlin.gradle.dsl.JsModuleKind

plugins {
    kotlin("multiplatform") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    kotlin("plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.11.1"
    // For the Android TV shell in `:tv`. Declared here (the repo keeps plugin
    // versions in the root) but `apply false`, so it is only resolved, never applied
    // to this project — which means the JS-only CI, with no Android SDK, downloads the
    // jar and does nothing with it. AGP is applied only in `:tv`, and `:tv` is excluded
    // from the build entirely when no SDK is present (see settings.gradle.kts). AGP 9
    // brings its own Kotlin support, so no separate Kotlin-Android plugin is declared.
    id("com.android.application") version "9.0.0" apply false
}

group = "ge.dakalebi"
version = "1.0.0"

kotlin {
    js(IR) {
        // CommonJS rather than the UMD default: the Firebase SDK is only
        // reachable as a module, so `@JsModule` externals would otherwise need a
        // `@JsNonModule` global fallback that does not exist.
        compilerOptions {
            moduleKind.set(JsModuleKind.MODULE_COMMONJS)
        }
        browser {
            commonWebpackConfig {
                outputFileName = "app.js"
            }
            // Nothing in this module is testable without a browser and a
            // signed-in session, and there is no fake for either. Everything
            // that can be tested lives in `:shared` and runs on Node in about a
            // second, which is why there is no Karma here.
            testTask { enabled = false }
        }

        binaries.executable()
    }

    sourceSets {
        getByName("jsMain") {
            dependencies {
                // Carries the generated `BuildInfo` too: it is produced into
                // `:shared`'s commonMain so both front ends print one stamp.
                implementation(project(":shared"))
                implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
                // Compose HTML is Kotlin/JS only and has no multiplatform
                // variant, so it cannot move into `:shared`. Neither can the
                // Firebase npm package: npm dependencies are only legal in a
                // Kotlin/JS source set, and the `@JsModule` externals that use
                // it are browser-only anyway.
                implementation("org.jetbrains.compose.html:html-core:1.11.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation(npm("firebase", "12.17.0"))
            }
        }

        // No `jsTest`: the tests are in `:shared`, where the code they exercise
        // now lives. `./gradlew jsNodeTest` still runs them.
    }
}
