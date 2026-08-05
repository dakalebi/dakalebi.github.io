package ge.dakalebi.data.formula

import kotlinx.coroutines.await
import kotlin.js.Promise

/** The bits of `fetch`'s Response this needs, as a typed wasm external. */
private external interface FetchResponse : JsAny {
    val ok: Boolean
    val status: Int
    fun text(): Promise<JsString>
}

/** `fetch` with nothing added, same contract as the js(IR) actual — no custom headers. */
private fun fetch(url: String): Promise<FetchResponse> = js("fetch(url)")

actual suspend fun httpGetText(url: String): String {
    val response = fetch(url).await()
    if (!response.ok) {
        throw FormulaApiException("GET $url failed: ${response.status}")
    }
    return response.text().await().toString()
}
