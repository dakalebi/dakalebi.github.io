// Everything the web app and a future Android TV app both need.
//
// Deliberately not an application: no `binaries.executable()`, no webpack, and no
// `moduleKind`. This produces klibs, and the module that links them into a bundle
// is the one that gets to decide the output shape. `moduleKind = COMMONJS` stays
// in the root build file with the `@JsModule` externals that force it.
//
// Plugin versions come from the root build file, which resolves them for the
// whole build. Adding a version here would let the two drift.
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
}

kotlin {
    js(IR) {
        // The browser sub-target exists so the root module's webpack build can
        // consume this klib. Nothing here is testable in a browser, and the
        // domain is testable without one, so tests run on Node.
        browser {
            testTask { enabled = false }
        }
        nodejs()
    }

    // The Version 2.0 `:web` app is Compose Multiplatform on wasmJs, so it needs this
    // module's klib compiled for wasm too. Additive: the root app still consumes the
    // `js(IR)` klib. Only `commonMain`'s three `expect` functions need wasm `actual`s.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: `CompositionLocal`, `CoroutineScope` and
            // the state holders' types all appear in signatures the root module
            // reads, so consumers need them on their own compile classpath.
            api("org.jetbrains.compose.runtime:runtime:1.11.1")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            // Private: only the Formula DTOs and the catalog cache serialise.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}
