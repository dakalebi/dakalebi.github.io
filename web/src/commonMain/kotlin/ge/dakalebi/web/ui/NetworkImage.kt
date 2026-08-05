package ge.dakalebi.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One image from the network, filling [modifier]'s bounds.
 *
 * Expected rather than written once because how a platform gets a remote image is exactly where
 * this app's two front ends diverge, and on the web the answer is not the obvious one.
 *
 * **Why this is not a decode into an `ImageBitmap`.** The provider's CDN
 * (`cdn.formula.ge`) serves the episode stills with **no `Access-Control-Allow-Origin` header** —
 * measured, not assumed. A canvas renderer has to read an image's bytes to draw it, either by
 * `fetch` (rejected by CORS) or by uploading a DOM image to a texture (a `SecurityError`, because
 * a cross-origin image without CORS taints what it is drawn into). So on a canvas there is no path
 * to these pixels at all. The DOM's own `<img>` has never needed permission to *display* what it
 * may not *read*, which is why the wasm implementation mounts a real element under the app and
 * lets Compose paint over it — the same hole-punch the player uses for `<video>`.
 *
 * [onFailed] fires when the image did not arrive, so the caller can fall back to the gradient
 * stand-in. The CDN 404s a still now and then, and an episode whose artwork is missing should look
 * like an episode that never had any.
 */
@Composable
expect fun NetworkImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier,
    topCornerRadius: Dp = 0.dp,
    onFailed: () -> Unit = {},
)
