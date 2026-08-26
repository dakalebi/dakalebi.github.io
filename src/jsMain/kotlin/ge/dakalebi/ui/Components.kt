package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.core.formatDuration
import ge.dakalebi.di.router
import ge.dakalebi.di.toasts
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import io.github.bchmsl.keel.components.Button as KeelButton
import io.github.bchmsl.keel.components.ButtonVariant
import io.github.bchmsl.keel.components.Dialog as KeelDialog
import io.github.bchmsl.keel.components.ProgressBar
import io.github.bchmsl.keel.dom.classNames
import io.github.bchmsl.keel.icons.Icon
import io.github.bchmsl.keel.icons.LucideIcon
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
        Img(src = url, alt = S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber)) {
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
                    Div({ classes("fb-s") }) { Text(S.season(episode.seasonNumber).caps) }
                    Div({ classes("fb-e") }) { Text(S.episode(episode.episodeNumber).caps) }
                }
            }
        }
    }
}

@Composable
fun EpisodeTile(episode: Episode, progress: WatchProgress?) {
    val router = router()
    val toasts = toasts()
    val percent = progress?.percent ?: 0.0
    val watched = progress?.isWatched == true

    Div({ classes("tile") }) {
        A(href = Router.href(Route.Watch(episode.id)), attrs = { classes("tile-link") }) {
            Div({ classes("tile-img") }) {
                Thumb(episode)
                Span({ classes("tile-badge", "mono") }) { Text("E${episode.episodeNumber}") }
                if (watched) {
                    Span({ classes("tile-seen") }) { Icon(LucideIcon.Check, size = ICON_TILE_ACTION) }
                }
                formatDuration(episode.durationSeconds)?.let {
                    Span({ classes("tile-dur", "mono") }) { Text(it) }
                }
                if (percent > 0) {
                    TileProgress(percent, watched)
                }
            }
            Div({ classes("tile-meta") }) {
                // Always season *and* episode. Formula's own title is usually
                // just "სერია 12", which reads identically in every season.
                Span({ classes("tile-name") }) {
                    Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
                }
                Span({ classes("tile-side") }) {
                    Text(
                        when {
                            watched -> S.watchedLabel
                            percent > 0 -> S.startedLabel
                            !episode.hasVideo -> S.noVideo
                            else -> ""
                        }.caps,
                    )
                }
            }
        }

        Div({ classes("tile-actions") }) {
            Button({
                classes("tile-act")
                attr("title", S.copyEpisodeLink)
                attr("aria-label", S.copyEpisodeLink)
                onClick { event ->
                    event.preventDefault()
                    event.stopPropagation()
                    copyToClipboard(router.absolute(Route.Watch(episode.id))) { ok ->
                        if (ok) toasts.ok(S.episodeLinkCopied)
                        else toasts.error(S.copyFailed)
                    }
                }
            }) { Icon(LucideIcon.Link, size = ICON_TILE_ACTION) }

            episode.videoUrl?.let { videoUrl ->
                Button({
                    classes("tile-act")
                    attr("title", S.copyMp4Link)
                    attr("aria-label", S.copyMp4Link)
                    onClick { event ->
                        event.preventDefault()
                        event.stopPropagation()
                        copyToClipboard(videoUrl) { ok ->
                            if (ok) toasts.ok(S.mp4LinkCopied)
                            else toasts.error(S.copyFailed)
                        }
                    }
                }) { Icon(LucideIcon.Download, size = ICON_TILE_ACTION) }
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

/**
 * A fixed row of episodes that does not scroll.
 *
 * Used for "previously" on the watch page. Now that what-comes-next has its own
 * column there is nothing to scroll *to* here, and a horizontal scroller that
 * never scrolls is worse than a plain row: it hides its last item behind an
 * edge with no affordance saying so.
 */
@Composable
fun EpisodeRow(title: String, episodes: List<Episode>, progress: Map<String, WatchProgress>) {
    if (episodes.isEmpty()) return
    Div {
        Div({ classes("rail-head") }) { H2 { Text(title) } }
        Div({ classes("fixedrow") }) {
            episodes.forEach { episode -> EpisodeTile(episode, progress[episode.id]) }
        }
    }
}

/**
 * Vertical "up next" list for the watch page's side column.
 *
 * A horizontal rail works on the dashboard, where the eye sweeps sideways past
 * a season. Beside a player it wastes the one axis there is room on, so this is
 * a stacked list of wide-thumbnail rows instead.
 */
@Composable
fun UpNextList(title: String, episodes: List<Episode>, progress: Map<String, WatchProgress>) {
    if (episodes.isEmpty()) return
    Div {
        Div({ classes("rail-head") }) { H2 { Text(title) } }
        Div({ classes("uplist") }) {
            episodes.forEach { episode -> UpNextRow(episode, progress[episode.id]) }
        }
    }
}

@Composable
private fun UpNextRow(episode: Episode, progress: WatchProgress?) {
    val percent = progress?.percent ?: 0.0
    val watched = progress?.isWatched == true

    A(href = Router.href(Route.Watch(episode.id)), attrs = { classes("uprow") }) {
        Div({ classes("uprow-th") }) {
            Thumb(episode, showLabel = false)
            if (watched) Span({ classes("tile-seen") }) { Icon(LucideIcon.Check, size = ICON_TILE_ACTION) }
            if (percent > 0) {
                TileProgress(percent, watched)
            }
        }
        Div({ classes("uprow-b") }) {
            Div({ classes("uprow-t") }) {
                Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
            }
            formatDuration(episode.durationSeconds)?.let {
                Div({ classes("uprow-s", "mono") }) { Text(it) }
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
    KeelDialog(title = title, description = body, onDismiss = onDismiss) {
        H3 { Text(title) }
        P { Text(body) }
        Div({ classes("dialog-row") }) {
            KeelButton(label = S.cancel.caps, onClick = onDismiss, variant = ButtonVariant.Outline)
            KeelButton(
                label = confirmLabel,
                onClick = onConfirm,
                variant = if (destructive) ButtonVariant.Destructive else ButtonVariant.Default,
            )
        }
    }
}

@Composable
fun ExternalLink(href: String, label: String) {
    // `ButtonVariant`/`ButtonSize`'s own `className` is internal to keel, so an
    // `<a>` styled as a button - which keel has no composable for - names the
    // literal class directly. Matches `ButtonVariant.Outline`/`ButtonSize.Default`.
    A(href = href, attrs = {
        classNames("btn", "btn--outline", "btn--size-default")
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
            .catch { error ->
                Log.w("clipboard", "navigator.clipboard refused, trying execCommand", error)
                done(legacyCopy(text))
            }
    } else {
        Log.d("clipboard", "no navigator.clipboard on this origin, using execCommand")
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
}.onFailure { Log.w("clipboard", "execCommand fallback failed", it) }.getOrDefault(false)

/**
 * The watched-so-far line across the bottom of a thumbnail.
 *
 * keel's `ProgressBar` at `onMedia`, because it lies over a still whose colours are
 * the episode's rather than the app's. `done` is why the finished state is a variant
 * rather than a colour written here: a bar that is full and a bar that is full *and*
 * watched should not look the same.
 *
 * `.tile-prog` keeps only the placement along the bottom edge.
 */
@Composable
private fun TileProgress(percent: Double, watched: Boolean) {
    ProgressBar(
        fraction = percent / 100.0,
        ariaLabel = S.statProgress,
        onMedia = true,
        done = watched,
        attrs = { classes("tile-prog") },
    )
}

/** Matches `.tile-act .ic svg, .tile-seen .ic svg` in web.css. */
private const val ICON_TILE_ACTION = 14
