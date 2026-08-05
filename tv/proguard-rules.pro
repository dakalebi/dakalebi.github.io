# The shell's host bridge is reached only from JavaScript (addJavascriptInterface), so
# R8 sees no caller and would strip or rename exit() — which the page invokes as
# window.AndroidTvHost.exit() to close the app on a top-level Back. The default
# optimize config already keeps @JavascriptInterface members; this is an explicit,
# self-documenting keep for the same thing.
-keepclassmembers class ge.dakalebi.tv.MainActivity$AndroidTvHost {
    @android.webkit.JavascriptInterface <methods>;
}
