package ge.dakalebi.core

// Console access through typed JS interop. Kotlin/Wasm has no `dynamic`, so unlike the
// js(IR) actual these are single-expression `js(...)` externals with typed parameters.
private fun consoleLog(tag: String, message: String): Unit = js("console.log(tag, message)")
private fun consoleWarn(tag: String, message: String): Unit = js("console.warn(tag, message)")
private fun consoleError(tag: String, message: String): Unit = js("console.error(tag, message)")

actual fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?) {
    val code = platformErrorCode(error)?.let { " [$it]" } ?: ""
    val cause = error?.message?.let { " ($it)" } ?: ""
    val line = "$message$code$cause"
    when (level) {
        LogLevel.Debug -> consoleLog(tag, line)
        LogLevel.Warn -> consoleWarn(tag, line)
        LogLevel.Error -> consoleError(tag, line)
    }
}

/**
 * TODO(2.0 data phase): read the provider's `.code` field on wasmJs.
 *
 * The js(IR) actual cast the `Throwable` to `dynamic` and read `error.code` — the
 * Firebase JS SDK's error code, whose exact spellings [ge.dakalebi.presentation.ErrorMessages]
 * matches on. Kotlin/Wasm has no `dynamic`, and the Firebase data layer is not ported to
 * wasm yet, so no code is available here and error messages fall back to the generic one.
 * This becomes load-bearing only once sign-in and catalog reads run on the wasm app; it is
 * implemented with typed JS interop as part of that data-layer port.
 */
actual fun platformErrorCode(error: Throwable?): String? = null

/**
 * TODO(2.0): `window.onerror` / `unhandledrejection` on wasmJs.
 *
 * Not load-bearing for rendering; wired when the wasm app starts running real flows that
 * can reject promises. A no-op keeps startup honest until then.
 */
actual fun installPlatformHandlers(prefix: String) {
    // Intentionally empty until the wasm app runs real async flows. See KDoc.
}
