package ge.dakalebi.core

import kotlinx.browser.window

/**
 * Every failure in the app goes through here.
 *
 * There is no backend and no error reporting service, so the browser console is
 * the only place a problem can ever surface. That makes silent `runCatching`
 * blocks genuinely dangerous: a catalog refresh once failed on a single
 * mistyped field and the only visible symptom was a three-second Georgian toast
 * that said "it didn't work". Anything caught should say what it was and why.
 *
 * Severity is graded so the console stays readable:
 *  - [e] something the user cares about broke,
 *  - [w] a fallback took over and the app carried on,
 *  - [d] expected-in-some-browsers noise, kept for tracing.
 */
object Log {
    private const val PREFIX = "dakalebi"

    fun d(tag: String, message: String, error: Throwable? = null) {
        console.log("$PREFIX/$tag", message, *extras(error))
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        console.warn("$PREFIX/$tag", message, *extras(error))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        console.error("$PREFIX/$tag", message, *extras(error))
    }

    /**
     * Firebase and Firestore put the only actionable identifier in `code`
     * (`permission-denied`, `auth/popup-blocked`, ...) and a prose sentence in
     * `message`. Kotlin's [Throwable] exposes neither usefully, so dig it out.
     */
    fun codeOf(error: Throwable?): String? {
        if (error == null) return null
        return runCatching {
            val dyn: dynamic = error
            (dyn.code as? String) ?: (dyn.cause?.code as? String)
        }.getOrNull()
    }

    /**
     * Catches what `try` cannot: exceptions thrown from event handlers, `ref`
     * callbacks and rejected promises nobody awaited. A composition that dies
     * this way leaves a blank page and an empty console otherwise.
     */
    fun installGlobalHandlers() {
        window.addEventListener("error", { event ->
            val dyn = event.asDynamic()
            val where = listOfNotNull(
                dyn.filename as? String,
                (dyn.lineno as? Int)?.toString(),
            ).joinToString(":")
            console.error(
                "$PREFIX/uncaught",
                (dyn.message as? String) ?: "unknown error",
                if (where.isBlank()) "" else "at $where",
                dyn.error,
            )
        })

        window.addEventListener("unhandledrejection", { event ->
            val reason = event.asDynamic().reason
            console.error(
                "$PREFIX/unhandled-rejection",
                (runCatching { reason?.code as? String }.getOrNull()) ?: "",
                (runCatching { reason?.message as? String }.getOrNull()) ?: reason,
                reason,
            )
        })
    }

    /** Trailing console arguments: the code when there is one, then the raw error. */
    private fun extras(error: Throwable?): Array<Any?> = when {
        error == null -> emptyArray()
        else -> arrayOf(codeOf(error) ?: "", error)
    }
}
