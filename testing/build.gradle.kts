// Test doubles the modules' unit tests share: a clock, in-memory storage, the
// account's settings document, progress storage, and builders for episodes and
// progress.
//
// Only test source sets depend on this module, so none of it reaches the app.
// It is a module rather than a test source set because Kotlin Multiplatform has
// no way for one module's tests to use another module's test code.
//
// `:domain`'s and `:data`'s own tests use it too, although it depends on them.
// Gradle resolves that as the module's main output, not as a cycle.
//
// Plugin versions come from the root build file, which resolves them for the
// whole build. Adding a version here would let the two drift.
plugins {
    kotlin("multiplatform")
}

kotlin {
    js(IR) {
        // As in the other modules. Nothing here runs on its own; the tests that
        // use it run on Node.
        browser {
            testTask { enabled = false }
        }
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            // `api`: the doubles implement domain and data interfaces, so a test
            // that uses one needs those types.
            api(project(":domain"))
            api(project(":data"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}
