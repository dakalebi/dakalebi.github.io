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
