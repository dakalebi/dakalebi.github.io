package ge.dakalebi.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The whole Android TV app: a full-screen [WebView] showing the live web UI at
 * [HOME_URL].
 *
 * The point of the shell is auto-update. It bundles no HTML or JavaScript; it loads
 * the deployed site, so every publish to the web is live on the television with no
 * reinstall. Its only real work is the handful of things a browser tab cannot do on a
 * ten-foot screen:
 *
 *  - **Back.** `KEYCODE_BACK` never reaches a WebView's JavaScript, so the shell
 *    forwards it into the page's own Back ladder (`window.__tvShell.onBack`, which the
 *    web input layer publishes). The page owns every level of Back; the shell only
 *    closes the app when the page asks it to, through [AndroidTvHost.exit].
 *  - **Media keys.** Also invisible to the page, so each is translated into the
 *    synthetic `keydown` the web key map already understands.
 *  - **Offline.** A native retry screen instead of Chromium's error page, and an
 *    auto-reload when the network returns.
 *  - **A dead renderer.** [WebViewClient.onRenderProcessGone] must be handled or the
 *    whole process is killed; the shell rebuilds the WebView instead.
 *
 * A plain [Activity] on purpose: no AndroidX, no `:shared`, no Compose. The smallest
 * possible sideload, and nothing to keep in version lockstep.
 */
class MainActivity : Activity() {

    private companion object {
        /** The deployed TV UI. `/tv/` serves the ten-foot shell by itself (see the
         *  web app's shell selection), so no query parameter is needed. */
        const val HOME_URL = "https://dakalebi.github.io/tv/"

        /** A product token appended to — never replacing — the stock user agent.
         *  Firebase and Google endpoints sniff the UA, so a wholesale replacement
         *  breaks sign-in. */
        const val UA_APP_TOKEN = " DakalebiTV/1.0 (AndroidTV)"

        /** The JavaScript-interface name. Deliberately not `__tvShell`: the page
         *  overwrites `window.__tvShell` with a fresh object when its input layer
         *  installs, which would wipe an interface of that name. A distinct name
         *  coexists with it. */
        const val BRIDGE_NAME = "AndroidTvHost"
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var errorOverlay: View
    private lateinit var retryButton: Button

    /** True while the main frame is showing a failed load. Gates the network-return
     *  auto-reload so a flapping connection never reloads a good page. */
    private var hasMainFrameError = false

    // Fullscreen HTML5 <video> hosting.
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }

    /** Reloads the page when the network comes back, but only if we are sitting on
     *  the error screen. Fires on a binder thread, so it hops to the UI thread. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { if (hasMainFrameError) loadHome() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The panel must not sleep mid-episode.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Black under everything, so there is no white flash before the page paints.
        window.setBackgroundDrawableResource(android.R.color.black)

        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
        }
        webView = createWebView()
        errorOverlay = createErrorOverlay()

        // WebView underneath, native error screen on top.
        root.addView(webView)
        root.addView(errorOverlay)
        setContentView(root)

        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            networkCallback,
        )

        loadHome()
    }

    // ---------------------------------------------------------------- input

    /**
     * Back and media keys, caught before they reach the WebView.
     *
     * `dispatchKeyEvent`, not `onKeyDown` or the deprecated `onBackPressed`: it is the
     * one Activity seam that always sees the key regardless of which view holds focus,
     * and it lets Back be consumed so the Activity never finishes on its own — exit is
     * the page's decision, delivered through [AndroidTvHost.exit].
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) handleBack()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            mediaJsKey(event.keyCode)?.let { key ->
                // `key` is from the fixed allowlist below, so inlining it is safe.
                webView.evaluateJavascript(
                    "window.dispatchEvent(new KeyboardEvent('keydown',{key:'$key',bubbles:true}));",
                    null,
                )
                return true
            }
        }
        // D-pad and Enter already arrive in the page as ArrowXxx / Enter.
        return super.dispatchKeyEvent(event)
    }

    /** The error screen has no page to hand Back to, so there Back exits; everywhere
     *  else it goes to the page's Back ladder. */
    private fun handleBack() {
        if (hasMainFrameError) {
            finish()
            return
        }
        webView.evaluateJavascript(
            "window.__tvShell && window.__tvShell.onBack && window.__tvShell.onBack();",
            null,
        )
    }

    private fun mediaJsKey(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "MediaPlayPause"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "MediaPlay"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "MediaPause"
        KeyEvent.KEYCODE_MEDIA_STOP -> "MediaStop"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "MediaTrackNext"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "MediaTrackPrevious"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "MediaFastForward"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "MediaRewind"
        else -> null
    }

    /** The page → shell channel. The web input layer calls this from its top-level
     *  "press Back again to exit" rung, wired through `TvInput.onExitRequested`. */
    private inner class AndroidTvHost {
        @JavascriptInterface
        fun exit() {
            // Called on a JS binder thread; the Activity must be touched on the UI one.
            runOnUiThread { finish() }
        }
    }

    // --------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(this)
        view.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        view.setBackgroundColor(Color.BLACK)
        // The WebView must own focus or D-pad keys never reach the page.
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        with(view.settings) {
            javaScriptEnabled = true
            // localStorage and the store Firebase Auth persists its session to.
            domStorageEnabled = true
            // Auto-update: serve from cache but revalidate against the page's
            // ETag/Last-Modified, so a new deploy is picked up on the next launch
            // while unchanged assets still come back 304. Not LOAD_NO_CACHE, which
            // would re-download the whole bundle every launch.
            cacheMode = WebSettings.LOAD_DEFAULT
            // The site is HTTPS end to end; block any stray insecure subresource.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // A remote cannot produce the user gesture browsers require to autoplay.
            mediaPlaybackRequiresUserGesture = false
            userAgentString += UA_APP_TOKEN
        }

        // Persist the auth session across launches, including the sign-in iframe.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }

        view.addJavascriptInterface(AndroidTvHost(), BRIDGE_NAME)
        view.webViewClient = ShellWebViewClient()
        // A WebChromeClient must be set or HTML5 <video> attaches no surface and
        // plays audio over black; this one also hosts native fullscreen video.
        view.webChromeClient = FullscreenVideoChromeClient()
        return view
    }

    private inner class ShellWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            hasMainFrameError = false
        }

        // Main frame only. A failed image or font on an otherwise good page must not
        // tear the page down.
        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) showError()
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (request.isForMainFrame) showError()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!hasMainFrameError) hideError()
        }

        // Not handling this kills the whole process when the renderer dies. The dead
        // WebView can never be reused, so it is destroyed and rebuilt.
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            rebuildWebView()
            return true
        }

        // Every URL, including the Firebase auth redirects, stays in this WebView.
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = false
    }

    /**
     * Hosts an HTML5 `<video>` gone fullscreen, so it fills the television rather than
     * the WebView's box.
     */
    private inner class FullscreenVideoChromeClient : WebChromeClient() {

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            webView.visibility = View.GONE
            root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            root.removeView(view)
            customView = null
            webView.visibility = View.VISIBLE
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            webView.requestFocus()
        }
    }

    private fun rebuildWebView() {
        root.removeView(webView)
        webView.destroy()
        webView = createWebView()
        root.addView(webView, 0) // Beneath the error overlay.
        loadHome()
    }

    // ----------------------------------------------------------- error screen

    private fun createErrorOverlay(): View {
        val title = TextView(this).apply {
            text = getString(R.string.error_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        }
        val message = TextView(this).apply {
            text = getString(R.string.error_body)
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        retryButton = Button(this).apply {
            text = getString(R.string.error_retry)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { loadHome() }
        }
        return LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Opaque, so it hides Chromium's own error page underneath.
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            val gap = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 24 }
            addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(message, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(retryButton, gap)
        }
    }

    private fun showError() {
        hasMainFrameError = true
        errorOverlay.visibility = View.VISIBLE
        errorOverlay.bringToFront()
        retryButton.requestFocus()
    }

    private fun hideError() {
        hasMainFrameError = false
        errorOverlay.visibility = View.GONE
        webView.requestFocus()
    }

    private fun loadHome() {
        hideError()
        webView.loadUrl(HOME_URL)
        webView.requestFocus()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        // Flush the auth cookie to disk so a cold start keeps the session.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        root.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
