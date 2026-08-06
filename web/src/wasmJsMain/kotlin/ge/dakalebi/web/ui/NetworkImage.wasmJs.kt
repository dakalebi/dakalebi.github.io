package ge.dakalebi.web.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * An `<img>` over the rectangle Compose reserved for it.
 *
 * The provider's CDN sends no CORS headers, so these bytes can be *displayed* by the DOM but never
 * *read* by a renderer — the whole reason this is an element rather than an `ImageBitmap` (see the
 * expect declaration, and [mountOverlay] for why it sits above the app rather than below it).
 *
 * Because it covers what it is given, nothing the app draws may share that rectangle. Callers place
 * their badges and progress bars around it, not on it.
 *
 * There is no cache here. The browser's HTTP cache is the cache — it is what an `<img>` uses, it
 * already respects the CDN's headers, and it survives a reload, which no in-memory map does.
 */
@Composable
actual fun NetworkImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier,
    topCornerRadius: Dp,
    onFailed: () -> Unit,
) {
    val element = remember { createImageElement() }
    val density = LocalDensity.current.density
    val failed by rememberUpdatedState(onFailed)

    DisposableEffect(element) {
        attachImageListeners(element) { failed() }
        onDispose { removeOverlay(element) }
    }

    LaunchedEffect(url, contentDescription, topCornerRadius) {
        setImageSource(
            element = element,
            url = url,
            alt = contentDescription ?: "",
            radius = topCornerRadius.value.toDouble(),
        )
    }

    // A modal is drawn *below* this element, so the element has to leave rather than be covered.
    val suppressed = OverlayGate.suppressed
    LaunchedEffect(suppressed) { setOverlaySuppressed(element, suppressed) }

    Box(
        modifier.onGloballyPositioned { coordinates ->
            // The layout's own rectangle, plus what an ancestor's clip has left of it. Compose
            // measures in its own pixels; CSS wants both divided by the device pixel ratio the
            // canvas was scaled by.
            val position = coordinates.positionInWindow()
            val visible = coordinates.boundsInWindow()
            placeOverlay(
                element = element,
                left = (position.x / density).toDouble(),
                top = (position.y / density).toDouble(),
                width = (coordinates.size.width / density).toDouble(),
                height = (coordinates.size.height / density).toDouble(),
                visibleTop = (visible.top / density).toDouble(),
                visibleBottom = (visible.bottom / density).toDouble(),
            )
        },
    )
}

private fun createImageElement(): JsAny {
    val image = newImageElement()
    mountOverlay(image)
    return image
}

private fun newImageElement(): JsAny = js(
    """{
    var image = document.createElement('img');
    image.className = 'dk-over';
    image.decoding = 'async';
    image.loading = 'eager';
    image.alt = '';
    image.style.display = 'none';
    return image;
}""",
)

private fun setImageSource(element: JsAny, url: String, alt: String, radius: Double) {
    js(
        """{
        element.alt = alt;
        element.style.borderRadius = radius + 'px ' + radius + 'px 0 0';
        if (element.src !== url) element.src = url;
        element.style.display = 'block';
    }""",
    )
}

private fun attachImageListeners(element: JsAny, onFailed: () -> Unit) {
    js("element.addEventListener('error', function () { onFailed(); })")
}
