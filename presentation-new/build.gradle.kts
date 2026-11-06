// The redesign's UI ("Presentation-New"): its design system and its screens for the
// web, TV and driving mode. It is drawn on its own pages, `/next/`, `/next/tv/` and
// `/next/drive/`, next to the old UI until the switch, and the old UI and keel go then.
//
// UI only. The screen state, the routes and the strings are `:presentation`'s, and
// this module reuses them as they are, so nothing here holds app state or rules.
//
// It also holds the engines that have no keel in them: which shell a page is, TV focus
// and input, the TV and driving-mode players' state, and the media helpers. The old UI
// imports them from here until the switch, so there is one copy of each.
//
// No keel, on purpose: this module has its own tokens, components and icons, and
// keel stays the old UI's. docs/ARCHITECTURE.md has the grep that checks it.
//
// Deliberately not an application, like `:presentation`: it produces klibs, and the
// root module links them into the one bundle every page loads.
//
// Plugin versions come from the root build file, which resolves them for the whole
// build. Adding a version here would let the two drift.
plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
}

kotlin {
    js(IR) {
        // The browser sub-target exists so the root module's webpack build can
        // consume this klib. Nothing here has tests yet: the screens are checked in
        // a browser, and the state they draw is tested in `:presentation`.
        browser {
            testTask { enabled = false }
        }
    }

    sourceSets {
        // jsMain only: Compose HTML is Kotlin/JS only and has no multiplatform variant.
        getByName("jsMain").dependencies {
            // `api` would leak the ViewModels' types to nobody: the root module names
            // `:presentation` itself. So `implementation`.
            implementation(project(":presentation"))
            implementation(project(":core"))
            implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
            implementation("org.jetbrains.compose.html:html-core:1.11.1")
        }
    }
}

/*
 * The pages and stylesheets in `src/jsMain/resources/next/` are not copied into the
 * app's distribution by this module. A Kotlin Multiplatform library's resources never
 * reach a consumer's distribution on their own, so the root build file copies them in
 * `jsProcessResources`, the same way it takes keel's.
 */
