package ge.dakalebi.data.formula

/**
 * A plain GET returning the response body as text.
 *
 * **Send no custom headers.** This is a property of the transport, not of the
 * caller: the original server-side client set User-Agent / Origin / Referer, all
 * of which are forbidden to scripts, and any non-safelisted header forces a
 * preflight that Formula's API does not advertise
 * `Access-Control-Allow-Headers` for. A well-meaning HTTP client that adds its
 * own `Accept` is enough to break the only reason this app can have no backend.
 *
 * That constraint is why this is an `expect` over the platform's own primitive
 * rather than a shared HTTP library: every such library sets default headers.
 *
 * Must throw [FormulaApiException] on any non-2xx, so a 404 cannot reach the
 * JSON parser and be reported as a decoding problem.
 *
 * With [timeoutMillis], must also throw [FormulaApiException] when the whole
 * response has not arrived in time, and abort the request rather than leave it
 * running. Without it, waits as long as the platform does, which on a phone whose
 * connection hangs can be more than a minute.
 */
expect suspend fun httpGetText(url: String, timeoutMillis: Int? = null): String
