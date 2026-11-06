package ge.dakalebi.domain

import ge.dakalebi.i18n.S
import ge.dakalebi.presentation.ErrorMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The error-code spellings, pinned.
 *
 * `ErrorMessages` recognises a failure by the Firebase **JS** SDK's code
 * (`permission-denied`, `auth/invalid-credential`). Nothing about that is
 * enforced by a type, so a second platform whose SDK says `PERMISSION_DENIED`
 * would compile, run, and quietly answer every question with the generic
 * sentence. That is not a failure anyone would notice from a screenshot, so the
 * list below is the contract such a platform has to satisfy.
 *
 * Assertions come in pairs on purpose: one that a code maps to its own sentence,
 * and one that it does not map to the fallback. Without the second, deleting the
 * whole mapping would still leave a green suite in any language where two
 * sentences happen to match.
 */
class ErrorMessagesTest {

    private val signInCodes = listOf(
        "auth/invalid-credential" to S.errWrongCredentials,
        "auth/wrong-password" to S.errWrongCredentials,
        "auth/user-not-found" to S.errWrongCredentials,
        "auth/invalid-email" to S.errInvalidEmail,
        "auth/email-already-in-use" to S.errEmailInUse,
        "auth/weak-password" to S.errWeakPassword,
        "auth/too-many-requests" to S.errTooManyRequests,
        "auth/unauthorized-domain" to S.errUnauthorizedDomain,
        "auth/network-request-failed" to S.errNetwork,
    )

    @Test
    fun every_actionable_sign_in_code_gets_its_own_sentence() {
        for ((code, expected) in signInCodes) {
            assertEquals(expected, ErrorMessages.signInFor(code), code)
        }
    }

    @Test
    fun no_actionable_sign_in_code_reads_as_the_generic_failure() {
        for ((code, _) in signInCodes) {
            assertNotEquals(S.errSignInFailed, ErrorMessages.signInFor(code), code)
        }
    }

    @Test
    fun an_unrecognised_sign_in_code_reads_as_the_generic_failure() {
        assertEquals(S.errSignInFailed, ErrorMessages.signInFor(""))
        assertEquals(S.errSignInFailed, ErrorMessages.signInFor("auth/internal-error"))
        // The spelling an unnormalised Android SDK would hand over.
        assertEquals(S.errSignInFailed, ErrorMessages.signInFor("ERROR_INVALID_CREDENTIAL"))
    }

    // ------------------------------------------------------------- refresh

    @Test
    fun a_rejected_rebuild_is_recognised_by_code_or_by_prose() {
        assertEquals(
            S.refreshNoPermission,
            ErrorMessages.catalogRefreshFor(message = null, code = "permission-denied"),
        )
        assertEquals(
            S.refreshNoPermission,
            ErrorMessages.catalogRefreshFor("Missing or insufficient permissions.", ""),
        )
    }

    @Test
    fun an_exhausted_quota_is_told_apart_from_a_dead_network() {
        assertEquals(S.refreshQuota, ErrorMessages.catalogRefreshFor(null, "resource-exhausted"))
        assertEquals(S.refreshQuota, ErrorMessages.catalogRefreshFor("Quota exceeded.", ""))
        assertEquals(S.refreshNetwork, ErrorMessages.catalogRefreshFor(null, "unavailable"))
        assertEquals(S.refreshNetwork, ErrorMessages.catalogRefreshFor("Failed to fetch", ""))
    }

    @Test
    fun an_unrecognised_rebuild_failure_reads_as_the_generic_failure() {
        assertEquals(S.refreshFailed, ErrorMessages.catalogRefreshFor(null, ""))
        assertEquals(S.refreshFailed, ErrorMessages.catalogRefreshFor("something odd", "internal"))
    }

    /** The same trap as the sign-in one: uppercase codes must not slip through. */
    @Test
    fun an_unnormalised_platform_code_is_not_mistaken_for_a_known_one() {
        assertEquals(S.refreshFailed, ErrorMessages.catalogRefreshFor(null, "PERMISSION_DENIED"))
        assertEquals(S.refreshFailed, ErrorMessages.catalogRefreshFor(null, "RESOURCE_EXHAUSTED"))
    }
}
