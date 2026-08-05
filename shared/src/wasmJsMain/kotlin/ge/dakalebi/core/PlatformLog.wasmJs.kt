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

/** `code`, or a wrapped cause's, on whatever JS value was thrown. */
private fun readCode(thrown: JsAny): String? = js(
    "(typeof thrown.code === 'string' ? thrown.code :" +
        " (thrown.cause && typeof thrown.cause.code === 'string' ? thrown.cause.code : null))",
)

/**
 * The Firebase JS SDK's error code, dug out of the JS error the wasm runtime wrapped.
 *
 * A rejected promise crossing into wasm arrives as a [JsException] holding the original
 * JS value, so the code lives one hop further away than it did under js(IR) — where the
 * `Throwable` *was* the `FirebaseError` and `error.code` read straight off it.
 *
 * The spellings are the contract [ge.dakalebi.presentation.ErrorMessages] matches on. This
 * platform's SDK is the reference implementation of them, so it passes them through
 * untranslated; a native Firebase SDK would have to map its own.
 */
actual fun platformErrorCode(error: Throwable?): String? {
    val thrown = (error as? JsException)?.thrownValue ?: return null
    return runCatching { readCode(thrown) }.getOrNull()
}

private fun installErrorHandlers(prefix: String) {
    js(
        """{
        window.addEventListener('error', function (event) {
            var where = [event.filename, event.lineno].filter(Boolean).join(':');
            console.error(prefix + '/uncaught', event.message || 'unknown error',
                where ? 'at ' + where : '', event.error);
        });
        window.addEventListener('unhandledrejection', function (event) {
            var reason = event.reason || {};
            console.error(prefix + '/unhandled-rejection', reason.code || '',
                reason.message || reason, reason);
        });
    }""",
    )
}

/**
 * `window.onerror` and `unhandledrejection`, which is all a browser offers.
 *
 * Written as one JS block rather than two Kotlin listeners: a wasm callback that itself
 * throws would be caught by the very handler being installed, and the point of these is to
 * be the last thing standing when a page has otherwise gone blank.
 */
actual fun installPlatformHandlers(prefix: String) {
    installErrorHandlers(prefix)
}
