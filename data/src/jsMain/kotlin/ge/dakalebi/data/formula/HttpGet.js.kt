package ge.dakalebi.data.formula

import kotlinx.coroutines.await
import org.w3c.fetch.Response
import kotlin.js.Promise

/**
 * `fetch` with nothing added to it, which is exactly the point.
 *
 * The timeout aborts through an `AbortSignal`, which never reaches the wire, so it
 * adds no header and cannot trigger a preflight. It covers the body as well as the
 * headers: a server that answers and then stalls is as stuck as one that never
 * answers.
 *
 * The globals rather than `window`: the browser and Node both have them, so this
 * runs under the Node tests unchanged.
 */
actual suspend fun httpGetText(url: String, timeoutMillis: Int?): String {
    val controller = if (timeoutMillis != null) AbortController() else null
    val timer = if (controller != null && timeoutMillis != null) {
        setTimeout({ controller.abort() }, timeoutMillis)
    } else {
        null
    }
    try {
        val init: dynamic = js("({})")
        if (controller != null) init.signal = controller.signal
        val response = fetch(url, init).await()
        if (!response.ok) {
            throw FormulaApiException("GET $url failed: ${response.status}")
        }
        return response.text().await()
    } catch (e: Throwable) {
        if (controller?.signal?.aborted == true) {
            throw FormulaApiException("GET $url timed out after $timeoutMillis ms")
        }
        throw e
    } finally {
        if (timer != null) clearTimeout(timer)
    }
}

private external fun fetch(input: String, init: dynamic): Promise<Response>

/** A number in the browser and an object in Node, so it stays opaque. */
private external fun setTimeout(handler: () -> Unit, timeout: Int): dynamic

private external fun clearTimeout(handle: dynamic)

private external class AbortController {
    val signal: dynamic
    fun abort()
}
