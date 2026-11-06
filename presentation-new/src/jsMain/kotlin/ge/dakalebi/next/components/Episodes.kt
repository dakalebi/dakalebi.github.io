package ge.dakalebi.next.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.core.formatDuration
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.i18n.S
import kotlin.math.roundToInt
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * An episode's still, or the fallback gradient when it has none or the image fails.
 *
 * The episode number sits on it in a small dark pill, so it reads on any frame. The
 * hairline along the bottom is the watched share ([progress], 0 to 1), drawn only
 * once an episode is started.
 *
 * Decorative to assistive technology: the row or tile it sits in names the episode
 * and says how far in it is, so the image adds nothing to read out.
 */
@Composable
fun Thumb(episode: Episode, progress: Double? = null, attrs: ExtraAttrs? = null) {
    // Formula's CDN sometimes 404s a still. The gradient under it is the fallback.
    var failed by remember(episode.thumbnailUrl) { mutableStateOf(false) }
    val url = episode.thumbnailUrl?.takeIf { it.isNotBlank() && !failed }

    Div({
        classes("thumb")
        attr("aria-hidden", "true")
        attrs?.invoke(this)
    }) {
        if (url != null) {
            Img(src = url, alt = "") {
                classes("thumb-img")
                attr("loading", "lazy")
                attr("decoding", "async")
                addEventListener("error") { failed = true }
            }
        }
        Span({ classes("thumb-number") }) { Text("${episode.episodeNumber}") }
        if (progress != null && progress > 0) {
            Div({ classes("thumb-progress") }) {
                Div({
                    classes("thumb-progress-fill")
                    style { property("width", "${(progress.coerceIn(0.0, 1.0) * 100).roundToInt()}%") }
                })
            }
        }
    }
}

/** How far into [this] episode the viewer is, 0 to 1, or null when it is not started. */
private fun WatchProgress?.startedShare(): Double? =
    this?.takeIf { it.isStarted }?.percent?.let { it / 100.0 }

/**
 * The line under an episode's name: its length, then how much is left if it is
 * started, or that it has no video.
 */
fun episodeMeta(episode: Episode, progress: WatchProgress?): String {
    val duration = progress?.durationSeconds ?: episode.durationSeconds
    val parts = buildList {
        formatDuration(duration)?.let(::add)
        when {
            progress?.isStarted == true && duration != null && duration > 0 -> {
                val left = ((duration - progress.progressSeconds) / 60.0).roundToInt().coerceAtLeast(1)
                add(S.minutesRemaining(left))
            }
            !episode.hasVideo -> add(S.noVideo)
        }
    }
    return parts.joinToString(" · ")
}

/**
 * One episode in a list: its still, its name and length, and a check once it is
 * watched. The whole row is one link to [href].
 *
 * [current] marks the episode the viewer is on (`aria-current`), drawn on a raised
 * ground. [withSeason] names the season too, for a list that crosses seasons.
 */
@Composable
fun EpisodeRow(
    episode: Episode,
    progress: WatchProgress?,
    href: String,
    current: Boolean = false,
    withSeason: Boolean = false,
    attrs: ExtraAttrs? = null,
) {
    val watched = progress?.isWatched == true
    A(href, {
        classes("ep-row")
        if (current) attr("aria-current", "true")
        attrs?.invoke(this)
    }) {
        Thumb(episode, progress.startedShare())
        Span({ classes("ep-row-text") }) {
            Span({ classes("ep-row-name") }) {
                Text(
                    if (withSeason) S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber)
                    else S.episode(episode.episodeNumber),
                )
            }
            Span({ classes("ep-row-meta") }) { Text(episodeMeta(episode, progress)) }
        }
        Span({ classes("ep-row-end") }) {
            if (watched) Icon(Glyph.Check, label = S.watchedLabel)
        }
    }
}

/**
 * One episode as a tile in a sideways row: the still on top, the name and length
 * under it. The whole tile is one link to [href].
 *
 * A tile is narrow, so [withSeason] puts the season on a small line of its own above
 * the name rather than in it, and the line under the name is short: how much is left
 * for a started episode, its length otherwise.
 */
@Composable
fun EpisodeTile(
    episode: Episode,
    progress: WatchProgress?,
    href: String,
    withSeason: Boolean = false,
    attrs: ExtraAttrs? = null,
) {
    A(href, {
        classes("ep-tile")
        attrs?.invoke(this)
    }) {
        Thumb(episode, progress.startedShare())
        if (withSeason) Span({ classes("ep-tile-season") }) { Text(S.season(episode.seasonNumber)) }
        Span({ classes("ep-tile-name") }) { Text(S.episode(episode.episodeNumber)) }
        Span({ classes("ep-tile-meta") }) { Text(tileMeta(episode, progress)) }
    }
}

/** The short line under a tile's name: watched, minutes left, or the length. */
private fun tileMeta(episode: Episode, progress: WatchProgress?): String {
    val duration = progress?.durationSeconds ?: episode.durationSeconds
    return when {
        progress?.isWatched == true -> S.watchedLabel
        progress?.isStarted == true && duration != null && duration > 0 -> S.minutesRemaining(
            ((duration - progress.progressSeconds) / 60.0).roundToInt().coerceAtLeast(1),
        )
        !episode.hasVideo -> S.noVideo
        else -> formatDuration(duration).orEmpty()
    }
}

/**
 * A season's number in a ring that fills as its episodes are watched: [watched] of
 * [total]. [current] is the season the viewer is on, drawn in amber.
 *
 * Only the picture. The link or button around it carries the name, which says the
 * same thing in words ([seasonLabel]).
 */
@Composable
fun SeasonRing(season: Int, watched: Int, total: Int, current: Boolean = false) {
    val share = if (total == 0) 0 else (watched * 100) / total
    Span({
        classes("ring")
        if (current) classes("ring--current")
        attr("aria-hidden", "true")
        style { property("--ring-value", "$share") }
    }) {
        Span({ classes("ring-number") }) { Text("$season") }
    }
}

/** What a [SeasonRing] shows, in words: "Season 6 · 52 episodes · 20 watched". */
fun seasonLabel(season: Int, watched: Int, total: Int): String =
    "${S.season(season)} · ${S.episodeCount(total, watched)}"
