package ge.dakalebi.presentation

import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.CatalogUnavailableException
import ge.dakalebi.i18n.S

/**
 * Failures turned into words.
 *
 * This is the only place that knows both what can go wrong and what language
 * the reader speaks. It lives in presentation because that is exactly the
 * boundary: repositories throw, screens read. Previously each repository built
 * its own Georgian sentence, which is why the data layer imported `i18n` at
 * all — and why a "translate the app" change had to touch Firestore code.
 */
object ErrorMessages {

    /** Why the catalog would not load. */
    fun catalogLoad(error: Throwable?): String = when {
        error == null -> ""
        error is CatalogUnavailableException -> S.catalogUnavailable
        else -> error.message ?: S.dataLoadFailed
    }

    /** Why a rebuild failed. Mapped by code first; the prose varies by SDK build. */
    fun catalogRefresh(error: Throwable): String =
        catalogRefreshFor(error.message, Log.codeOf(error).orEmpty())

    /**
     * Why a sign-in attempt failed.
     *
     * Firebase puts the only actionable identifier in `code`
     * (`auth/popup-blocked`, ...) and a prose sentence in `message`. The raw
     * codes are useless to a reader, so map the ones a person can act on.
     */
    fun signIn(error: Throwable): String = signInFor(Log.codeOf(error).orEmpty())

    /**
     * The rebuild mapping, over the two values it actually reads.
     *
     * Split from [catalogRefresh] so the spellings below can be pinned by a test
     * without building a Firebase error. Those spellings are the Firebase **JS**
     * SDK's: any other platform's [Log.codeOf] has to translate to them, and if
     * it does not, every branch here silently falls through to
     * [ge.dakalebi.i18n.Strings.refreshFailed].
     */
    internal fun catalogRefreshFor(message: String?, code: String): String {
        val text = (message ?: "").lowercase()
        return when {
            code == "permission-denied" || "permission" in text || "insufficient" in text ->
                S.refreshNoPermission

            "quota" in text || "resource-exhausted" in code -> S.refreshQuota
            "failed to fetch" in text || "network" in text || "unavailable" in code ->
                S.refreshNetwork

            else -> S.refreshFailed
        }
    }

    /** The sign-in mapping, over the one value it reads. See [catalogRefreshFor]. */
    internal fun signInFor(code: String): String =
        when {
            code.contains("invalid-credential") || code.contains("wrong-password") ||
                code.contains("user-not-found") -> S.errWrongCredentials

            code.contains("invalid-email") -> S.errInvalidEmail
            code.contains("email-already-in-use") -> S.errEmailInUse
            code.contains("weak-password") -> S.errWeakPassword
            code.contains("too-many-requests") -> S.errTooManyRequests
            code.contains("popup-closed-by-user") || code.contains("cancelled-popup-request") ->
                S.errPopupClosed

            code.contains("popup-blocked") -> S.errPopupBlocked
            code.contains("unauthorized-domain") -> S.errUnauthorizedDomain
            code.contains("network-request-failed") -> S.errNetwork
            else -> S.errSignInFailed
        }
}
