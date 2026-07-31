package ge.dakalebi.ui.player

import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement

/**
 * iPhone / iPad detection.
 *
 * iPadOS 13+ reports itself as "MacIntel" with a desktop user agent, so the
 * touch-point check is not optional — without it every iPad falls through to
 * the custom player, which is exactly what the requirement forbids. Desktop
 * Safari on a Mac has maxTouchPoints of 0 and correctly keeps the custom UI.
 */
val isAppleMobile: Boolean by lazy {
    val nav = window.navigator
    val ua = nav.userAgent
    val platform = runCatching { nav.asDynamic().platform as? String }.getOrNull().orEmpty()
    val touchPoints = runCatching { nav.asDynamic().maxTouchPoints as? Int }.getOrNull() ?: 0
    Regex("iPhone|iPod|iPad", RegexOption.IGNORE_CASE).containsMatchIn(ua) ||
        (platform == "MacIntel" && touchPoints > 1)
}

/** True once the element exposes the WebKit inline-fullscreen video API. */
fun looksLikeIosVideo(video: HTMLVideoElement?): Boolean {
    if (isAppleMobile) return true
    val dyn = video?.asDynamic() ?: return false
    return jsTypeOf(dyn.webkitEnterFullscreen) == "function" ||
        jsTypeOf(dyn.webkitSupportsFullscreen) != "undefined"
}
