package ge.dakalebi.web.ui

/**
 * The DOM elements the app cannot draw itself: the player's `<video>` and every episode still.
 *
 * A canvas renderer can do neither. It cannot decode video, and it cannot read the provider's
 * images — that CDN sends no CORS headers, so those bytes may be *displayed* by the DOM but never
 * sampled by a renderer. Both are therefore real elements, positioned over the rectangle Compose
 * measured for them.
 *
 * **Over, not under.** Mounting them beneath and leaving a transparent hole in the app is the
 * arrangement this started with, and it does not work here: pixels the canvas clears — with
 * `BlendMode.Clear`, or simply by never painting them — do not come out of Compose's web renderer
 * transparent. Measured in three renderers, including two with no extensions and real GPU
 * compositing: the "hole" reads opaque white and nothing behind the canvas is ever visible.
 *
 * So the layering is the other way round, and the *layout* is what keeps the two apart: nothing the
 * app draws may sit inside one of these rectangles, because an element above the canvas would cover
 * it. That is why an episode tile carries its badges below its still rather than on top of it, and
 * why the player's chrome is a bar beneath the picture rather than an overlay across it.
 */

/**
 * Puts [element] over the app.
 *
 * Compose's own tree is lowered rather than the element raised, because the element's own
 * `z-index` cannot beat a sibling that has none: `ComposeViewport` empties the container it is
 * given and appends its tree, so what the app owns is exactly "every body child that is not ours".
 * That also rules out declaring the app's layer in the stylesheet — Compose renders into a canvas
 * inside a shadow root, whose host carries no class or id to match on.
 */
internal fun mountOverlay(element: JsAny) {
    js(
        """{
        document.body.appendChild(element);
        Array.prototype.forEach.call(document.body.children, function (child) {
            if (child.classList && child.classList.contains('dk-over')) return;
            child.style.position = 'relative';
            child.style.zIndex = '1';
        });
    }""",
    )
}

/**
 * Moves [element] onto the rectangle Compose measured for it, in CSS pixels, and clips it to the
 * part of that rectangle Compose can actually see.
 *
 * Two rectangles are needed, not one. The element is `position: fixed`, so nothing clips it: an
 * episode tile scrolled half under the navigation bar would draw over the bar unless it is clipped
 * to match. Sizing it to the *visible* part instead would be wrong in a different way — the picture
 * would squeeze as it scrolled rather than slide behind the edge.
 *
 * [visibleTop] and [visibleBottom] are the clipped bounds; everything else is the layout's own.
 */
internal fun placeOverlay(
    element: JsAny,
    left: Double,
    top: Double,
    width: Double,
    height: Double,
    visibleTop: Double,
    visibleBottom: Double,
) {
    js(
        """{
        if (width <= 0 || height <= 0 || visibleBottom - visibleTop <= 0) {
            element.style.visibility = 'hidden';
            return;
        }
        element.style.visibility = 'visible';
        element.style.left = left + 'px';
        element.style.top = top + 'px';
        element.style.width = width + 'px';
        element.style.height = height + 'px';
        var clipTop = Math.max(0, visibleTop - top);
        var clipBottom = Math.max(0, (top + height) - visibleBottom);
        element.style.clipPath = 'inset(' + clipTop + 'px 0px ' + clipBottom + 'px 0px)';
    }""",
    )
}

internal fun removeOverlay(element: JsAny) {
    js("{ if (element.parentNode) element.parentNode.removeChild(element); }")
}

/**
 * Takes [element] out of the page while a modal is up, and puts it back afterwards.
 *
 * `display` rather than `visibility`, so this is independent of the visibility [placeOverlay] uses
 * for scrolling: either alone is enough to hide the element, and neither can undo the other. A
 * `<video>` keeps playing while hidden, which is what should happen — the viewer is answering a
 * dialog, not stopping the episode.
 */
internal fun setOverlaySuppressed(element: JsAny, suppressed: Boolean) {
    js("element.style.display = suppressed ? 'none' : 'block'")
}
