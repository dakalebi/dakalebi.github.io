package ge.dakalebi.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.url.URLSearchParams

/** Which front end this page is. */
enum class Shell { Web, Tv }

/**
 * Read from the document, not sniffed from the user agent.
 *
 * `tv/index.html` says what it is on the root element, which makes the decision
 * greppable and makes it a statement about the page rather than a guess about the
 * device. A TV is not a device class you can detect reliably anyway: the same
 * bundle is a phone page, a desktop page, and a page inside an Android TV
 * WebView whose user agent looks like Chrome on Android.
 *
 * To *look* at the TV UI without a television, open `/tv/` in any browser: it is
 * an ordinary page. `?ui=` exists to force a different root onto a page that did
 * not declare one, which is what `tv-demo` below needs. Note that `/?ui=tv` draws
 * the TV UI against `web.css`, because the stylesheet is chosen by the document —
 * useful for driving focus, not for judging the design.
 */
val shell: Shell by lazy {
    val requested = runCatching { URLSearchParams(window.location.search).get("ui") }.getOrNull()
    if ((requested ?: declaredShell)?.startsWith("tv") == true) Shell.Tv else Shell.Web
}

/** What the document itself says it is, ignoring any `?ui=` override. */
private val declaredShell: String? by lazy {
    document.getElementById("root")?.getAttribute("data-shell")
}

/**
 * True when the graph should be built from fixtures instead of Firebase.
 *
 * There is no Firebase session in an automated browser and signing in is not
 * possible there, so without this the TV screens could only ever be looked at on
 * a real television. Deliberately a separate flag rather than a third [Shell]:
 * it changes what the data is, not which UI renders it.
 */
val useFixtures: Boolean by lazy {
    runCatching { URLSearchParams(window.location.search).get("ui") }.getOrNull() == "tv-demo"
}

/**
 * Prefix for the images that sit at the site root.
 *
 * `logo.png` is referenced relatively, so from `/tv/` it would resolve to
 * `/tv/logo.png` and 404. One level up is all the TV page ever needs, because
 * that is where it lives.
 *
 * Keyed on the *document*, not on [shell]. `?ui=tv` renders the TV UI from the
 * root path, where a `../` would point outside the site — so the override must
 * change which UI draws without changing where the images are.
 */
val assetBase: String get() = if (declaredShell?.startsWith("tv") == true) "../" else ""
