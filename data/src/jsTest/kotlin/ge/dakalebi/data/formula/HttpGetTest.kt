package ge.dakalebi.data.formula

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The transport against a real local server, because what is under test is the
 * network behaviour itself: an answer, a refusal, and a server that never answers.
 */
class HttpGetTest {

    @Test
    fun a_server_that_never_answers_times_out() = runTest {
        withServer(respond = false) { url ->
            val error = assertFailsWith<FormulaApiException> { httpGetText(url, timeoutMillis = 200) }
            assertTrue("timed out" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun an_answer_inside_the_timeout_is_returned() = runTest {
        withServer(respond = true) { url ->
            assertEquals("ok", httpGetText(url, timeoutMillis = 1_000))
        }
    }

    @Test
    fun a_refusal_is_still_reported_as_a_failure_not_a_timeout() = runTest {
        withServer(respond = true, status = 404) { url ->
            val error = assertFailsWith<FormulaApiException> { httpGetText(url, timeoutMillis = 1_000) }
            assertTrue("404" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun without_a_timeout_an_answer_is_returned() = runTest {
        withServer(respond = true) { url ->
            assertEquals("ok", httpGetText(url))
        }
    }

    private suspend fun withServer(respond: Boolean, status: Int = 200, block: suspend (String) -> Unit) {
        val server = http.createServer { _: dynamic, res: dynamic ->
            if (respond) {
                res.statusCode = status
                res.end("ok")
            }
        }
        suspendCoroutine { cont -> server.listen(0, "127.0.0.1") { cont.resume(Unit) } }
        val port = server.address().port as Int
        try {
            block("http://127.0.0.1:$port/")
        } finally {
            server.closeAllConnections()
            suspendCoroutine { cont -> server.close { cont.resume(Unit) } }
        }
    }

    private companion object {
        val http: dynamic = js("require('http')")
    }
}
