import org.jetbrains.kotlin.gradle.dsl.JsModuleKind

// The data layer: every implementation of a `:domain` repository interface.
//
//   commonMain  the Formula API and its DTOs, the storage that is plain Kotlin
//               over a `KeyValueStore` (the catalog cache, the settings device
//               copy, guest progress, the guest choice), `ViewerProgressRepository`,
//               and `SyncedSettingsRepository`, where the device copy and the
//               account's settings meet
//   jsMain      Firestore and Auth through the Firebase JS SDK, `localStorage`,
//               the system clock, and the bare GET behind the Formula API
//
// It knows nothing above the domain: no presentation, no `i18n`, no UI. Turning a
// failure into words is `presentation/ErrorMessages`.
//
// Plugin versions come from the root build file, which resolves them for the
// whole build. Adding a version here would let the two drift.
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    js(IR) {
        // CommonJS, as in the root module, because of the `@JsModule` Firebase
        // externals here: the SDK is only reachable as a module, so the UMD default
        // would need a `@JsNonModule` global fallback that does not exist.
        compilerOptions {
            moduleKind.set(JsModuleKind.MODULE_COMMONJS)
        }
        // As in the other modules: the browser sub-target exists so the root
        // module's webpack build can consume this klib, and tests run on Node.
        browser {
            testTask { enabled = false }
        }
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            // `api`: the repositories implement domain interfaces and hand out
            // domain models, so a consumer needs the domain types to use them.
            api(project(":domain"))
            implementation(project(":core"))
            // Private: only the Formula DTOs and the catalog cache serialise.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            // The settings sync's scope and flows, and in `jsMain` `await()` on the
            // SDK's and `fetch`'s promises. `:domain` exposes it too; named here
            // because this module uses it directly.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }

        jsMain.dependencies {
            // npm dependencies are only legal in a Kotlin/JS source set, and the
            // `@JsModule` externals that use it are browser-only anyway.
            implementation(npm("firebase", "12.17.0"))
        }

        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}
