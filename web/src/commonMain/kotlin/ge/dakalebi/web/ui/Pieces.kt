package ge.dakalebi.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ge.dakalebi.core.formatDuration
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps

/**
 * The pieces every screen is built from.
 *
 * Compose UI has no stylesheet, so what `web.css` expressed as classes is expressed here as
 * composables. That is the trade this rewrite makes: the styling stops being separately
 * addressable and becomes part of the component, which is also why there is exactly one of
 * each rather than a class per variant.
 */

/** Small, letter-spaced, dim: the label above a heading. */
@Composable
fun Eyebrow(text: String, color: Color = Tokens.mut, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * Episode still, or the deterministic gradient stand-in when there is none.
 *
 * The still is a DOM element under the canvas (see [NetworkImage]), so this composable must paint
 * *nothing* while one is showing — a background here would cover it. The gradient is drawn only
 * when there is no image to show, or when the CDN failed to produce one.
 */
@Composable
fun Thumb(
    episode: Episode,
    showLabel: Boolean = true,
    topCornerRadius: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val url = episode.thumbnailUrl
    var failed by remember(url) { mutableStateOf(false) }

    if (url != null && !failed) {
        NetworkImage(
            url = url,
            contentDescription = S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber),
            modifier = modifier,
            topCornerRadius = topCornerRadius,
            onFailed = { failed = true },
        )
        return
    }

    Box(modifier.background(fallbackBrush(episode.seasonNumber, episode.episodeNumber))) {
        if (showLabel) {
            Column(
                modifier = Modifier.fillMaxSize().padding(10.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = S.season(episode.seasonNumber).caps,
                    color = Tokens.txDim,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = S.episode(episode.episodeNumber).caps,
                    color = Tokens.tx,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * How far through an episode the viewer is.
 *
 * Green once finished, red while in progress — the same signal the DOM app uses, and the reason
 * a completed row reads as done at a glance without a tick to look for.
 */
@Composable
fun ProgressBar(percent: Double, watched: Boolean, modifier: Modifier = Modifier, height: Int = 3) {
    Box(modifier.fillMaxWidth().height(height.dp).background(Color.White.copy(alpha = 0.14f))) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth((percent / 100.0).coerceIn(0.0, 1.0).toFloat())
                .background(if (watched) Tokens.ok else Tokens.red),
        )
    }
}

/** A badge sitting on top of media: the episode number, a duration, a tick. */
@Composable
private fun MediaBadge(text: String, modifier: Modifier = Modifier, color: Color = Tokens.tx) {
    Text(
        text = text,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.66f), Tokens.radiusSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = color,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * One episode in the grid or a rail.
 *
 * Nothing is drawn on top of the still. It cannot be: a real still is a DOM element laid over the
 * canvas (see [NetworkImage]), so anything the app painted there would be covered. The episode
 * number, the runtime and the watched tick therefore sit in the row beneath it, and the progress bar
 * spans the seam between the two — which is where a progress bar belongs anyway.
 *
 * The copy-link and copy-MP4 actions the DOM app puts here are deliberately left out: they exist for
 * sharing a link out of the page, and both are reachable from the watch screen.
 */
@Composable
fun EpisodeTile(
    episode: Episode,
    progress: WatchProgress?,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val percent = progress?.percent ?: 0.0
    val watched = progress?.isWatched == true

    Column(
        modifier
            .width(TILE_WIDTH.dp)
            .clip(Tokens.radius)
            .background(Tokens.elev)
            .border(1.dp, Tokens.line, Tokens.radius)
            .clickable(onClick = onOpen),
    ) {
        // The still's own top corners have to be rounded: it is a layer above the canvas, so the
        // tile's `clip` cannot reach it.
        Thumb(
            episode = episode,
            topCornerRadius = 14.dp,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )

        if (percent > 0) ProgressBar(percent, watched)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaBadge("E${episode.episodeNumber}")
            Spacer(Modifier.width(7.dp))
            // The episode alone, not "season N · episode M". The badge already carries the number
            // and every tile on screen belongs to one season, so spending the row's width on the
            // season only truncated the part that identifies the tile.
            Text(
                text = S.episode(episode.episodeNumber).caps,
                color = Tokens.tx,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (watched) {
                AppIconView(AppIcons.check, tint = Tokens.ok, size = 13.dp)
                Spacer(Modifier.width(6.dp))
            }
            val side = when {
                watched -> S.watchedLabel
                percent > 0 -> S.startedLabel
                !episode.hasVideo -> S.noVideo
                else -> formatDuration(episode.durationSeconds) ?: ""
            }
            if (side.isNotEmpty()) {
                Text(
                    text = side.caps,
                    color = if (watched) Tokens.ok else Tokens.mut,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/** Fixed tile width, so a grid and a rail set episodes at the same size. */
const val TILE_WIDTH = 208

@Composable
fun SectionHead(
    title: String,
    count: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Tokens.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        count?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = Tokens.mut, fontSize = 11.5.sp)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

// ---------------------------------------------------------------------------- controls

enum class ButtonTone { Primary, Ghost, Danger, Quiet }

@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    tone: ButtonTone = ButtonTone.Ghost,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
) {
    val background = when (tone) {
        ButtonTone.Primary -> Tokens.red
        ButtonTone.Ghost -> Tokens.elev2
        ButtonTone.Danger -> Tokens.redDim
        ButtonTone.Quiet -> Color.Transparent
    }
    // A disabled primary keeps an outline: without one it reads as a line of dim text rather
    // than as the button it will become once the form is filled in.
    val border = when {
        !enabled && tone != ButtonTone.Quiet -> Tokens.line
        tone == ButtonTone.Ghost -> Tokens.lineStrong
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> Tokens.mut
        tone == ButtonTone.Quiet -> Tokens.txDim
        else -> Tokens.tx
    }

    Row(
        modifier
            .clip(Tokens.pill)
            .background(if (enabled) background else Tokens.elev)
            .border(1.dp, border, Tokens.pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(7.dp))
        }
        Text(label, color = content, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun IconButton(
    icon: AppIcon,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Tokens.txDim,
    size: Int = 20,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(Tokens.pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AppIconView(icon, tint = if (enabled) tint else Tokens.mut, size = size.dp)
    }
}

/** A season chip, with a tick once every episode in it is watched. */
@Composable
fun Chip(label: String, selected: Boolean, done: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(Tokens.pill)
            .background(if (selected) Tokens.tx else Tokens.elev2)
            .border(1.dp, if (selected) Tokens.tx else Tokens.line, Tokens.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Tokens.txDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        if (done) {
            Spacer(Modifier.width(5.dp))
            AppIconView(
                AppIcons.check,
                tint = if (selected) Color.Black else Tokens.ok,
                size = 12.dp,
            )
        }
    }
}
