package ge.dakalebi.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
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
import android.window.OnBackInvokedDispatcher

/**
 * The whole Android TV app: a full-screen [WebView] showing the live web UI at
 * [HOME_URL].
 *
 * The point of the shell is auto-update. It bundles no HTML or JavaScript; it loads
 * the deployed site, so every publish to the web is live on the television with no
 * reinstall. Its only real work is the handful of things a browser tab cannot do on a
 * ten-foot screen.
 *
 * The rule for input is that **every remote key reaches the page as the `keydown` a
 * browser would produce**, aimed at the focused element, so the page has one input
 * path and the browser TV version is a faithful test of it. D-pad and OK get that from
 * Chromium for free. Three things do not, and the shell makes them up:
 *
 *  - **Back.** `KEYCODE_BACK` never reaches a WebView's JavaScript, so the shell
 *    presses `GoBack` in the page ([pressKey]). The page owns every level of Back,
 *    including leaving a text field; the shell only closes the app when the page asks
 *    it to while handling that press, through [AndroidTvHost.exit], or when there is
 *    no page to ask.
 *  - **Media keys.** Also invisible to the page, so each is pressed as the `Media*`
 *    key the web key map already understands, held keys included.
 *  - **Held D-pad keys.** Chromium delivers the repeats, but with `repeat` false, and
 *    the player tells a tap from a hold by that flag. The shell presses the repeats
 *    itself, flagged.
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
        /** The deployed TV UI, set per build type in `build.gradle.kts`: `/tv/` for
         *  the real app, `/preview/tv/` for the preview one. Either path serves the
         *  ten-foot shell by itself (see the web app's shell selection), so no query
         *  parameter is needed. */
        const val HOME_URL = BuildConfig.HOME_URL

        /** The one site the main frame may show. Everything the app navigates to is a
         *  hash route on it, and email-and-password sign-in is a `fetch`, not a
         *  navigation, so nothing legitimate ever leaves it. */
        val HOME_HOST: String? = Uri.parse(HOME_URL).host

        /** Pressed in the page for `KEYCODE_BACK`. The web key map reads it as Back,
         *  and it is the UI Events name for exactly this key. */
        const val BACK_KEY = "GoBack"

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

    /** Back presses sent to the page ([handleBack]) whose script has not finished yet.
     *  [AndroidTvHost.exit] is honoured only while this is above zero: the bridge is
     *  injected into every frame, cross-origin ones included, and the page only asks to
     *  exit from inside its handling of such a press. Written on the UI thread, read on
     *  the JavaBridge thread. */
    @Volatile
    private var backPressesInFlight = 0

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

        // Android 13+ delivers Back here instead of as a key (see [dispatchKeyEvent]).
        // Registering is also what stops the system from closing the app on its own.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { handleBack() }
        }

        loadHome()
    }

    // ---------------------------------------------------------------- input

    /**
     * Back, media keys and D-pad repeats, caught before they reach the WebView.
     *
     * `dispatchKeyEvent`, not `onKeyDown`: it is the one Activity seam that always sees
     * the key regardless of which view holds focus, and it lets a key be consumed.
     *
     * **Back is acted on here only below Android 13.** From 13 the manifest opts into
     * `OnBackInvokedCallback`, so the system sends Back to the callback registered in
     * [onCreate]. That is not optional: an app targeting API 36 gets the callback path
     * on Android 16 whether it opts in or not, and this method used to be the only Back
     * handler, so on Android 16 every Back closed the app from any screen. Both routes
     * end in [handleBack].
     *
     * The key still arrives here after the callback has run: the system forwards its
     * key-up marked cancelled, so that later stages drop their own Back handling. The
     * `isCanceled` check is what keeps one press from being handled twice; without it
     * Back skipped a rung of the page's ladder, and exited one press early.
     *
     * Lint's `GestureBackNavigation` asks for callbacks only, which is right from 13
     * up and impossible below it: Android 8 to 12 have no callback to register, and
     * the key is the only Back they send.
     */
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
            return true
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        mediaJsKey(event.keyCode)?.let { key ->
            // Every event, not only the first key-down: a repeat or a key-up let through
            // reaches the page a second time, natively, as a separate press.
            if (down) pressKey(key, repeat = event.repeatCount > 0)
            return true
        }
        // The first press and the key-up stay native, so only the repeats are ours. Only
        // while the page has focus; on the error screen the D-pad drives the Retry button.
        if (down && event.repeatCount > 0 && webView.hasFocus()) {
            dpadJsKey(event.keyCode)?.let { key ->
                pressKey(key, repeat = true)
                return true
            }
        }
        // D-pad and Enter already arrive in the page as ArrowXxx / Enter.
        return super.dispatchKeyEvent(event)
    }

    /**
     * Back goes to the page as a key press, so it takes the same path a browser's
     * Escape does. That matters in a text field: the page reads Back there as "leave
     * the field", which a direct call into its Back ladder skips, and on the sign-in
     * screen the ladder's next rung is exit. While the on-screen keyboard is open, it
     * takes the first Back to close itself, as in any Android app, and the page gets
     * the next one.
     *
     * The app closes by itself only when there is no page to ask: the error screen, or
     * a page whose input layer is not up yet (a slow or stuck load), where Back would
     * otherwise do nothing at all.
     */
    private fun handleBack() {
        if (hasMainFrameError) {
            finish()
            return
        }
        // The page's exit call runs inside this script, so it lands before the result.
        backPressesInFlight++
        pressKey(BACK_KEY) { delivered ->
            backPressesInFlight--
            if (!delivered) finish()
        }
    }

    /**
     * Presses [key] in the page the way a browser does: a `keydown` on the focused
     * element, bubbling up to the window the web input layer listens on.
     *
     * Only once that layer is installed (`window.__tvShell` is its marker), and
     * [onResult] says whether it was. [key] always comes from the fixed tables in this
     * file, so inlining it into the script is safe.
     *
     * A synthetic key has no default action, so a repeat pressed into a text field
     * whose keyboard is closed does not move the caret. The on-screen keyboard takes
     * the D-pad itself while it is open, which is nearly always.
     */
    private fun pressKey(key: String, repeat: Boolean = false, onResult: ((Boolean) -> Unit)? = null) {
        webView.evaluateJavascript(
            "(function(){if(!window.__tvShell)return false;" +
                "var t=document.activeElement||document.body;" +
                "t.dispatchEvent(new KeyboardEvent('keydown'," +
                "{key:'$key',repeat:$repeat,bubbles:true,cancelable:true}));" +
                "return true})()",
        ) { result -> onResult?.invoke(result == "true") }
    }

    private fun dpadJsKey(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
        else -> null
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

    /** The page → shell channel. The web input layer calls this when Back reaches the
     *  top of its ladder, wired through `TvInput.onExitRequested`. Any frame can call
     *  it, so a call outside a Back press is ignored ([backPressesInFlight]). */
    private inner class AndroidTvHost {
        @JavascriptInterface
        fun exit() {
            if (backPressesInFlight <= 0) return
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
            // Auto-update: ordinary HTTP caching. GitHub Pages sends max-age=600, so a
            // new deploy is picked up by the first launch after a cached copy is ten
            // minutes old, and after that unchanged assets come back 304. Not
            // LOAD_NO_CACHE, which would re-download the whole bundle every launch.
            cacheMode = WebSettings.LOAD_DEFAULT
            // The site is HTTPS end to end; block any stray insecure subresource.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // A remote cannot produce the user gesture browsers require to autoplay.
            mediaPlaybackRequiresUserGesture = false
            // The system font size would otherwise scale the page's root font, and the
            // TV layout is sized in rem, so the whole interface grows on top of the
            // app's own interface-size setting: 130% there and a 1.3 system font makes
            // 169%, past anything the layout was built for. The in-app setting is the
            // one size control, as it is in a browser.
            textZoom = 100
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

        // The main frame stays on the app's own site. Anywhere else would still have
        // the host bridge attached, and a television has no browser to hand a link to,
        // so an off-site navigation is simply dropped. Frames are left alone.
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url
            return !(url.scheme == "https" && url.host == HOME_HOST)
        }
    }

    /**
     * Hosts an HTML5 `<video>` gone fullscreen, so it fills the television rather than
     * the WebView's box.
     */
    private inner class FullscreenVideoChromeClient : WebChromeClient() {

        /** Transparent, so a `<video>` with no `poster` shows the black under it until
         *  its first frame. Returning null here makes the WebView draw its own default:
         *  a grey panel with a large play glyph, which a browser never shows. */
        private val blankPoster: Bitmap by lazy {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override fun getDefaultVideoPoster(): Bitmap = blankPoster

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
