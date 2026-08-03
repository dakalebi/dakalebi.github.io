package ge.dakalebi.data.formula

import kotlinx.browser.window
import kotlinx.coroutines.await

/** `fetch` with nothing added to it, which is exactly the point. */
actual suspend fun httpGetText(url: String): String {
    val response = window.fetch(url).await()
    if (!response.ok) {
        throw FormulaApiException("GET $url failed: ${response.status}")
    }
    return response.text().await()
}
