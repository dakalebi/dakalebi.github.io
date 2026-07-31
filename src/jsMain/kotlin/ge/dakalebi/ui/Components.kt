package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.app.Route
import ge.dakalebi.app.Router
import ge.dakalebi.app.Toasts
import ge.dakalebi.app.fallbackGradient
import ge.dakalebi.app.formatDuration
import ge.dakalebi.data.Episode
import ge.dakalebi.data.WatchProgress
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** Episode still, or a deterministic gradient stand-in when there is none. */
@Composable
fun Thumb(episode: Episode, showLabel: Boolean = true) {
    var failed by remember(episode.thumbnailUrl) { mutableStateOf(false) }
    val url = episode.thumbnailUrl

    if (url != null && !failed) {
        Img(src = url, alt = episode.title ?: "სერია ${episode.episodeNumber}") {
            attr("loading", "lazy")
            // Formula's CDN occasionally 404s a still; fall back to the gradient.
            addEventListener("error") { failed = true }
        }
    } else {
        Div({
            classes("tile-fallback")
            style {
                property(
                    "background-image",
                    fallbackGradient(episode.seasonNumber, episode.episodeNumber),
                )
            }
        }) {
            if (showLabel) {
                Div {
                    Div({ classes("fb-s") }) { Text("სეზონი ${episode.seasonNumber}") }
                    Div({ classes("fb-e") }) { Text("სერია ${episode.episodeNumber}") }
                }
            }
        }
    }
}

@Composable
fun EpisodeTile(episode: Episode, progress: WatchProgress?) {
    val percent = progress?.percent ?: 0.0
    val watched = progress?.isWatched == true

    Div({ classes("tile") }) {
        A(href = Router.href(Route.Watch(episode.id)), attrs = { classes("tile-link") }) {
            Div({ classes("tile-img") }) {
                Thumb(episode)
                Span({ classes("tile-badge", "mono") }) { Text("E${episode.episodeNumber}") }
                if (watched) {
                    Span({ classes("tile-seen") }) { Text("✓") }
                }
                formatDuration(episode.durationSeconds)?.let {
                    Span({ classes("tile-dur", "mono") }) { Text(it) }
                }
                if (percent > 0) {
                    Div({ classes("tile-prog", if (watched) "done" else "") }) {
                        Div({ style { property("width", "$percent%") } })
                    }
                }
            }
            Div({ classes("tile-meta") }) {
                Span({ classes("tile-name") }) {
                    Text(episode.title ?: "სერია ${episode.episodeNumber}")
                }
                Span({ classes("tile-side") }) {
                    Text(
                        when {
                            watched -> "ნანახია"
                            percent > 0 -> "დაწყებულია"
                            !episode.hasVideo -> "ვიდეო არაა"
                            else -> "სეზონი ${episode.seasonNumber}"
                        },
                    )
                }
            }
        }

        Div({ classes("tile-actions") }) {
            Button({
                classes("tile-act")
                attr("title", "სერიის ლინკის კოპირება")
                attr("aria-label", "სერიის ლინკის კოპირება")
                onClick { event ->
                    event.preventDefault()
                    event.stopPropagation()
                    copyToClipboard(Router.absolute(Route.Watch(episode.id))) { ok ->
                        if (ok) Toasts.ok("სერიის ლინკი დაკოპირდა")
                        else Toasts.error("კოპირება ვერ მოხერხდა")
                    }
                }
            }) { Text("⧉") }

            episode.videoUrl?.let { videoUrl ->
                Button({
                    classes("tile-act")
                    attr("title", "MP4 ლინკის კოპირება")
                    attr("aria-label", "MP4 ლინკის კოპირება")
                    onClick { event ->
                        event.preventDefault()
                        event.stopPropagation()
                        copyToClipboard(videoUrl) { ok ->
                            if (ok) Toasts.ok("MP4 ლინკი დაკოპირდა")
                            else Toasts.error("კოპირება ვერ მოხერხდა")
                        }
                    }
                }) { Text("⤓") }
            }
        }
    }
}

@Composable
fun Rail(title: String, subtitle: String? = null, episodes: List<Episode>, progress: Map<String, WatchProgress>) {
    if (episodes.isEmpty()) return
    Div {
        Div({ classes("rail-head") }) {
            H2 { Text(title) }
            subtitle?.let { Span({ classes("count") }) { Text(it) } }
        }
        Div({ classes("rail") }) {
            episodes.forEach { episode ->
                EpisodeTile(episode, progress[episode.id])
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Div({ classes("scrim"); onClick { onDismiss() } })
    Div({ classes("dialog") }) {
        H3 { Text(title) }
        P { Text(body) }
        Div({ classes("dialog-row") }) {
            Button({ classes("btn", "btn-ghost"); onClick { onDismiss() } }) { Text("გაუქმება") }
            Button({
                classes("btn", if (destructive) "btn-danger" else "btn-primary")
                onClick { onConfirm() }
            }) { Text(confirmLabel) }
        }
    }
}

@Composable
fun ExternalLink(href: String, label: String) {
    A(href = href, attrs = {
        classes("btn", "btn-ghost")
        target(ATarget.Blank)
        attr("rel", "noreferrer")
    }) { Text(label) }
}

/**
 * Clipboard with the legacy fallback: `navigator.clipboard` is unavailable on
 * insecure origins and in some in-app browsers.
 */
private fun copyToClipboard(text: String, done: (Boolean) -> Unit) {
    val clipboard = window.navigator.asDynamic().clipboard
    if (clipboard != null && clipboard.writeText != null) {
        (clipboard.writeText(text) as kotlin.js.Promise<Unit>)
            .then { done(true) }
            .catch { done(legacyCopy(text)) }
    } else {
        done(legacyCopy(text))
    }
}

private fun legacyCopy(text: String): Boolean = runCatching {
    val area = document.createElement("textarea").asDynamic()
    area.value = text
    area.style.position = "fixed"
    area.style.opacity = "0"
    document.body?.appendChild(area as org.w3c.dom.Node)
    area.select()
    val ok = document.asDynamic().execCommand("copy") as Boolean
    document.body?.removeChild(area as org.w3c.dom.Node)
    ok
}.getOrDefault(false)
