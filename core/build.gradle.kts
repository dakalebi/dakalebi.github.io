// The innermost ring beside `:domain`: logging and formatting policy, and the two
// platform hooks they need, the console and the clock (`jsMain`).
//
// No dependencies, not even on `:domain`. Every other module may depend on this
// one, so anything added here is something they all inherit.
//
// Plugin versions come from the root build file, which resolves them for the
// whole build. Adding a version here would let the two drift.
plugins {
    kotlin("multiplatform")
}

kotlin {
    js(IR) {
        // As in `:shared`: the browser sub-target exists so the root module's
        // webpack build can consume this klib, and tests run on Node.
        browser {
            testTask { enabled = false }
        }
        nodejs()
    }
}
