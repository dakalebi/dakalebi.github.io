import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/*
 * Version 2.0: the Compose Multiplatform web app.
 *
 * This is the NEW front end — Compose UI (the `androidx.compose` toolkit) rendered to a
 * canvas via wasmJs — as opposed to the root module's Compose HTML (DOM) app that still
 * ships to the live site. It is a separate module so the two coexist: the root keeps
 * deploying to `dakalebi.github.io`, and this deploys to `dakalebi.github.io/preview`
 * off the `preview` branch until it reaches parity.
 *
 * wasmJs-only for now. The UI lives in `commonMain` so a native `androidTarget()` can be
 * added later and drive Android TV from the same Compose UI. No Android SDK is needed to
 * build this, so unlike `:tv` its include in `settings.gradle.kts` is unconditional.
 *
 * Plugin versions are inherited from the root (the repo keeps versions there), so they
 * are applied here without a version, exactly as `:shared` does.
 */
plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                // Same output name as the root app, so the deploy and the index.html
                // reference `app.js` the same way.
                outputFileName = "app.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            // The Compose UI toolkit (canvas), NOT Compose HTML.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}
