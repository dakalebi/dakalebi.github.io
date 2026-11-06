package ge.dakalebi.core

/**
 * Every failure in the app goes through here.
 *
 * There is no backend and no error reporting service, so the console is the only
 * place a problem can ever surface. That makes silent `runCatching` blocks
 * genuinely dangerous: a catalog refresh once failed on a single mistyped field
 * and the only visible symptom was a three-second Georgian toast that said "it
 * didn't work". Anything caught should say what it was and why.
 *
 * Severity is graded so the console stays readable:
 *  - [d] expected-in-some-browsers noise, kept for tracing,
 *  - [w] a fallback took over and the app carried on,
 *  - [e] something the user cares about broke.
 *
 * What each level *does* is [platformLog]'s business. What is left here is the
 * grading policy and the tag shape, which are the parts worth keeping identical
 * everywhere: a log line that reads differently per platform is a log line you
 * cannot grep.
 */
object Log {
    private const val PREFIX = "dakalebi"

    fun d(tag: String, message: String, error: Throwable? = null) {
        platformLog(LogLevel.Debug, "$PREFIX/$tag", message, error)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        platformLog(LogLevel.Warn, "$PREFIX/$tag", message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        platformLog(LogLevel.Error, "$PREFIX/$tag", message, error)
    }

    /**
     * The provider's own error code, dug out of whatever shape it arrives in.
     *
     * Firebase puts the only actionable identifier in a `code`
     * (`permission-denied`, `auth/popup-blocked`, ...) and a prose sentence in
     * the message, and Kotlin's [Throwable] exposes neither usefully.
     *
     * The spelling this returns is a contract, not an implementation detail:
     * [ge.dakalebi.presentation.ErrorMessages] matches on it, so a platform that
     * spells the same failure differently must translate rather than pass its own
     * spelling through.
     */
    fun codeOf(error: Throwable?): String? = platformErrorCode(error)

    /**
     * Catches what `try` cannot: exceptions thrown from event handlers, `ref`
     * callbacks and rejected promises nobody awaited. A composition that dies
     * this way leaves a blank page and an empty console otherwise.
     */
    fun installGlobalHandlers() {
        installPlatformHandlers(PREFIX)
    }
}
