rootProject.name = "dakalebi"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// The root project stays the web app, so the GitHub Pages artifact keeps coming
// out of `build/dist/js/productionExecutable` and the deploy workflow needs no
// path change. `:shared` is everything the web app and a future TV app both
// need: the domain, the string catalogue, and the state holders above them.
include(":shared")

// `:web` is the Version 2.0 Compose Multiplatform app (wasmJs / canvas). It needs no
// Android SDK, so its include is unconditional. It exists only on the `preview` branch
// and deploys to `dakalebi.github.io/preview`; `master` never includes it, so the live
// root deploy is unaffected.
include(":web")

// `:tv` is an Android application module and needs the Android SDK to configure.
// Gradle configures EVERY included project even for a JS-only task, so an
// unconditional include would force the Android Gradle Plugin to load and fail the
// GitHub Pages CI, which runs `jsBrowserDistribution` on a runner with no SDK. So the
// guard lives at the include itself, not inside the module: with no SDK visible, `:tv`
// never enters the build model and AGP is never touched. On a developer machine with
// an SDK it is included and builds normally.
val androidSdkAvailable: Boolean =
    !System.getenv("ANDROID_HOME").isNullOrBlank() ||
        !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
        file("local.properties").takeIf { it.exists() }
            ?.let { java.util.Properties().apply { it.inputStream().use(::load) } }
            ?.getProperty("sdk.dir")?.isNotBlank() == true

if (androidSdkAvailable) {
    include(":tv")
}
