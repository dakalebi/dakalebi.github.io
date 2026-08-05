package ge.dakalebi.web

import ge.dakalebi.core.Log
import kotlinx.browser.document
import kotlinx.browser.window

actual fun setDocumentTitle(title: String) {
    document.title = title
}

actual fun setDocumentLang(tag: String) {
    document.documentElement?.setAttribute("lang", tag)
}

/**
 * `navigator.clipboard` with the legacy fallback, which is still needed: the API is unavailable
 * on insecure origins and in some in-app browsers.
 *
 * Written as one JS function because the fallback is a sequence of DOM mutations that would cost
 * six typed externals to express, and there is nothing in it Kotlin makes safer.
 */
private fun writeClipboard(text: String, done: (Boolean) -> Unit) {
    js(
        """{
        var legacy = function () {
            try {
                var area = document.createElement('textarea');
                area.value = text;
                area.style.position = 'fixed';
                area.style.opacity = '0';
                document.body.appendChild(area);
                area.select();
                var ok = document.execCommand('copy');
                document.body.removeChild(area);
                return ok;
            } catch (e) {
                return false;
            }
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text)
                .then(function () { done(true); })
                .catch(function () { done(legacy()); });
        } else {
            done(legacy());
        }
    }""",
    )
}

actual fun copyToClipboard(text: String, done: (Boolean) -> Unit) {
    runCatching { writeClipboard(text, done) }
        .onFailure {
            Log.w("clipboard", "copy unavailable", it)
            done(false)
        }
}

actual fun openExternal(url: String) {
    window.open(url, "_blank")
}
