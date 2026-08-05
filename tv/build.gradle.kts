/*
 * The Android TV / Google TV shell.
 *
 * A deliberately tiny app: one Activity wrapping a WebView pointed at the live
 * `https://dakalebi.github.io/tv/`. The web app does all the work, so this module
 * depends on nothing but the Android framework — no AndroidX, no `:shared`, no
 * Compose. That keeps the sideloaded APK small and removes every version-alignment
 * question a dependency would add.
 *
 * Only `com.android.application` is applied: AGP 9 has built-in Kotlin support, so the
 * separate `kotlin("android")` plugin is neither needed nor allowed.
 *
 * The module only exists on a machine that has an Android SDK: `settings.gradle.kts`
 * guards the `include(":tv")` on SDK presence, so the GitHub Pages CI (which builds
 * only the Kotlin/JS bundle and has no SDK) never configures it and never loads AGP.
 */
plugins {
    id("com.android.application")
}

android {
    namespace = "ge.dakalebi.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "ge.dakalebi.tv"
        // Not the OS floor but the WebView's: the Firebase-JS/Kotlin bundle needs a
        // modern Chromium, and every Android TV / Google TV device in the field
        // (Shield, Chromecast with Google TV, the Google TV Streamer, recent
        // Sony/TCL/Hisense sets) clears API 26 comfortably.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // Both types minify + shrink resources. The default optimize config is what
        // keeps the one thing R8 would otherwise strip: the `@JavascriptInterface`
        // method the page calls as `window.AndroidTvHost.exit()`. It looks unused to R8
        // because nothing on the Kotlin side calls it, so without a keep rule Back at
        // the top level would silently fail to close the app. `proguard-rules.pro` adds
        // an explicit belt-and-braces keep for it.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
