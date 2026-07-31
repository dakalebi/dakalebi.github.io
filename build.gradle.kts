import org.jetbrains.kotlin.gradle.dsl.JsModuleKind

plugins {
    kotlin("multiplatform") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    kotlin("plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.11.1"
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
            testTask { enabled = false }
        }
        binaries.executable()
    }

    sourceSets {
        getByName("jsMain") {
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
                implementation("org.jetbrains.compose.html:html-core:1.11.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation(npm("firebase", "12.17.0"))
            }
        }
    }
}
