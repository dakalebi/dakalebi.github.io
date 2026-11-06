package ge.dakalebi.core

/** How loud a line is. [Log] decides which one a call site gets. */
enum class LogLevel { Debug, Warn, Error }

/**
 * Writes one already-tagged line wherever this platform's logs go.
 *
 * [tag] arrives composed, so the prefix cannot drift between platforms.
 */
expect fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?)

/**
 * The provider's error code, which lives on a field Kotlin does not model.
 *
 * **The spellings are a contract.** The reference is the Firebase JS SDK's:
 * `permission-denied`, `resource-exhausted`, `auth/invalid-credential`. An
 * implementation whose SDK says `PERMISSION_DENIED` or `ERROR_INVALID_CREDENTIAL`
 * must translate, because [ge.dakalebi.presentation.ErrorMessages] recognises a
 * failure only by these strings and would otherwise answer every question with
 * the same generic sentence — silently, and identically to a working app.
 * `ErrorMessagesTest` is where that contract is written down.
 */
expect fun platformErrorCode(error: Throwable?): String?

/**
 * Attaches whatever catches the failures `try` cannot: exceptions from callbacks
 * and rejected promises nobody awaited. Called once, at startup.
 */
expect fun installPlatformHandlers(prefix: String)
