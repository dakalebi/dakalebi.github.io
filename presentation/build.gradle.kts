// The presentation layer: the state holders and ViewModels, the routes, the
// error wording, the string catalogue and the composition locals.
//
// Compose runtime only: no Compose HTML and no component library. A front end
// with a different look reuses everything here as it is, so nothing here may
// know how a screen is drawn. docs/ARCHITECTURE.md has the grep that checks it,
// and it reads this file too.
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
            // `api`, not `implementation`: `CompositionLocal`, `CoroutineScope`,
            // `StateFlow` and the state holders' types all appear in signatures
            // the root module reads, so consumers need them on their own compile
            // classpath.
            api("org.jetbrains.compose.runtime:runtime:1.11.1")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }

        commonTest.dependencies {
            implementation(project(":testing"))
            // The playback and settings-store tests run the real settings sync
            // (`SyncedSettingsRepository`) over fakes. Test code only: the main
            // classpath has no `:data`.
            implementation(project(":data"))
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}
