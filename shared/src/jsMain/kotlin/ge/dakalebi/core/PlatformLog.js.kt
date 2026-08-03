package ge.dakalebi.core

import kotlinx.browser.window

actual fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?) {
    val extras = extras(error)
    when (level) {
        LogLevel.Debug -> console.log(tag, message, *extras)
        LogLevel.Warn -> console.warn(tag, message, *extras)
        LogLevel.Error -> console.error(tag, message, *extras)
    }
}

/** The Firebase JS SDK puts its code on a plain property, so read it as one. */
actual fun platformErrorCode(error: Throwable?): String? {
    if (error == null) return null
    return runCatching {
        val dyn: dynamic = error
        (dyn.code as? String) ?: (dyn.cause?.code as? String)
    }.getOrNull()
}

/** `window.onerror` and `unhandledrejection`, which is all a browser offers. */
actual fun installPlatformHandlers(prefix: String) {
    window.addEventListener("error", { event ->
        val dyn = event.asDynamic()
        val where = listOfNotNull(
            dyn.filename as? String,
            (dyn.lineno as? Int)?.toString(),
        ).joinToString(":")
        console.error(
            "$prefix/uncaught",
            (dyn.message as? String) ?: "unknown error",
            if (where.isBlank()) "" else "at $where",
            dyn.error,
        )
    })

    window.addEventListener("unhandledrejection", { event ->
        val reason = event.asDynamic().reason
        console.error(
            "$prefix/unhandled-rejection",
            (runCatching { reason?.code as? String }.getOrNull()) ?: "",
            (runCatching { reason?.message as? String }.getOrNull()) ?: reason,
            reason,
        )
    })
}

/** Trailing console arguments: the code when there is one, then the raw error. */
private fun extras(error: Throwable?): Array<Any?> = when {
    error == null -> emptyArray()
    else -> arrayOf(platformErrorCode(error) ?: "", error)
}
