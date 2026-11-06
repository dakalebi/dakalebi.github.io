// The string catalogue, the composition locals, and the state holders: what is
// left of the old shared module, until `:presentation` takes it over.
//
// Deliberately not an application: no `binaries.executable()`, no webpack, and no
// `moduleKind`. This produces klibs, and the module that links them into a bundle
// is the one that gets to decide the output shape.
//
// Plugin versions come from the root build file, which resolves them for the
// whole build. Adding a version here would let the two drift.
plugins {
    kotlin("multiplatform")
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

    sourceSets {
        commonMain.dependencies {
            // `api`: the state holders take use cases and hand out domain models,
            // so a consumer needs the domain types to call them.
            api(project(":domain"))
            implementation(project(":core"))
            // `api`, not `implementation`: `CompositionLocal`, `CoroutineScope` and
            // the state holders' types all appear in signatures the root module
            // reads, so consumers need them on their own compile classpath.
            api("org.jetbrains.compose.runtime:runtime:1.11.1")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }

        commonTest.dependencies {
            // The tests still live here, including the ones for `:data`. They move
            // to their modules before this one is deleted.
            implementation(project(":data"))
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}
