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

// The pull request the `preview` build opens: `-PpreviewPr=42` points it at
// `https://dakalebi.github.io/pr/42/tv/`. Read at configuration, but required only
// when a preview build actually runs (see the end of this file), so debug, release
// and an IDE sync never need it.
val previewPr: Provider<String> = providers.gradleProperty("previewPr")

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

        // What the WebView opens. The `preview` build type below points it at one
        // pull request's preview instead.
        buildConfigField("String", "HOME_URL", "\"https://dakalebi.github.io/tv/\"")
    }

    buildFeatures {
        buildConfig = true
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
        // The same shell on a pull request's `https://dakalebi.github.io/pr/<n>/tv/`,
        // so a change can be tried on the television before it reaches `main` - the
        // app has no copy of the site, so a broken production deploy is a broken TV
        // app. A separate application id, so it installs beside the real one, and a
        // debug build underneath, so it signs with the debug key and can be inspected
        // from chrome://inspect. `src/preview/res` gives it its own name, banner and
        // icon.
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            buildConfigField(
                "String",
                "HOME_URL",
                "\"https://dakalebi.github.io/pr/${previewPr.getOrElse("unset")}/tv/\"",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// A preview build without a pull request would install an app that opens a 404, so
// it stops before writing BuildConfig. Captures only the provider, which keeps the
// configuration cache usable.
tasks.matching { it.name == "generatePreviewBuildConfig" }.configureEach {
    val pr = previewPr
    doFirst {
        val number = pr.orNull
        if (number == null || !number.matches(Regex("[1-9][0-9]*"))) {
            throw GradleException(
                "The preview build opens one pull request's preview. Say which: " +
                    "./gradlew :tv:assemblePreview -PpreviewPr=<number>",
            )
        }
    }
}
