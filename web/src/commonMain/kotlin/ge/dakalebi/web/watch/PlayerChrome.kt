package ge.dakalebi.web.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ge.dakalebi.core.formatTime
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.web.player.VideoCommands
import ge.dakalebi.web.player.VideoState
import ge.dakalebi.web.ui.AppIconView
import ge.dakalebi.web.ui.AppIcons
import ge.dakalebi.web.ui.IconButton
import ge.dakalebi.web.ui.Tokens
import kotlinx.coroutines.delay

/** How long the chrome stays up after the last interaction, while playing. */
private const val HIDE_AFTER_MS = 2600L

/** What the arrow keys and the skip buttons move by. */
private const val SKIP_SECONDS = 10.0

/**
 * The player's controls: a bar beneath the picture, not an overlay across it.
 *
 * The picture is a DOM `<video>` laid over the canvas — the only way a canvas app can show video at
 * all — and an element above the canvas covers whatever the app painted there. So the chrome sits
 * below the frame, always visible, which for a mouse and keyboard is arguably the better trade
 * anyway: nothing to summon, and nothing that hides while you are reaching for it.
 *
 * It talks to the decoder only through [VideoCommands], so the same chrome will drive a native
 * surface unchanged.
 */
@Composable
fun PlayerChrome(
    state: VideoState,
    commands: VideoCommands?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Tokens.elev)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        SeekBar(
            fraction = state.fraction,
            onSeekFraction = { fraction ->
                val duration = state.durationSeconds
                if (duration > 0) commands?.seekTo(fraction * duration)
            },
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                icon = if (state.paused) AppIcons.play else AppIcons.pause,
                label = if (state.paused) S.play else S.pause,
                onClick = { commands?.togglePlay(state.paused) },
                tint = Tokens.tx,
                size = 22,
            )
            SkipButton(back = true) {
                commands?.seekBy(-SKIP_SECONDS, state.positionSeconds, state.durationSeconds)
            }
            SkipButton(back = false) {
                commands?.seekBy(SKIP_SECONDS, state.positionSeconds, state.durationSeconds)
            }
            IconButton(
                icon = if (state.muted) AppIcons.volumeOff else AppIcons.volumeOn,
                label = if (state.muted) S.unmute else S.mute,
                onClick = { commands?.setMuted(!state.muted) },
                tint = Tokens.tx,
            )

            Spacer(Modifier.width(6.dp))
            Clock(state.positionSeconds, state.durationSeconds)
            if (state.buffering) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(color = Tokens.mut, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.weight(1f))

            IconButton(
                icon = if (state.fullscreen) AppIcons.exitFullscreen else AppIcons.fullscreen,
                label = S.fullscreen,
                onClick = { commands?.toggleFullscreen() },
                tint = Tokens.tx,
            )
        }
    }
}

/**
 * The timeline.
 *
 * Click-to-seek is done from the tap's position over the measured width rather than with a
 * `Slider`: Material's slider carries a thumb, a value range and a drag contract this needs none
 * of, and its default colours would have to be overridden away entirely.
 */
@Composable
private fun SeekBar(fraction: Double, onSeekFraction: (Double) -> Unit) {
    var width by remember { mutableStateOf(1f) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .onGloballyPositioned { width = it.size.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onSeekFraction((offset.x / width).toDouble()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(Tokens.pill)
                .background(Color.White.copy(alpha = 0.26f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                    .background(Tokens.red),
            )
        }
    }
}

/** The elapsed and total time, as one line of tabular-looking text. */
@Composable
private fun Clock(position: Double, duration: Double) {
    Text(
        text = formatTime(position) + " / " + formatTime(duration),
        color = Tokens.txDim,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * Skip ten seconds.
 *
 * The icon is the circular arrow alone and the "10" is set beside it as text, in the app's own
 * font — the SVG the DOM app uses puts the number inside the glyph, which a path cannot carry.
 */
@Composable
private fun SkipButton(back: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(Tokens.pill)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppIconView(
            icon = if (back) AppIcons.back10 else AppIcons.forward10,
            tint = Tokens.tx,
            size = 21.dp,
        )
        Text(
            text = "10",
            color = Tokens.tx,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * The next-episode card, over the player's bottom-right corner.
 *
 * Appears three minutes from the end, which is where the DOM app puts it. The countdown bar is
 * drawn only when autoplay is on, because otherwise nothing is counting down.
 */
@Composable
fun NextEpisodeCard(
    title: String,
    countdown: Double,
    autoplayOn: Boolean,
    onPlayNext: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(268.dp)
            .clip(Tokens.radius)
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(14.dp),
    ) {
        Text(S.nextEpisode.caps, color = Tokens.mut, fontSize = 10.sp, letterSpacing = 1.3.sp)
        Spacer(Modifier.height(4.dp))
        Text(title, color = Tokens.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

        if (autoplayOn) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(Tokens.pill)
                    .background(Color.White.copy(alpha = 0.2f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(countdown.toFloat().coerceIn(0f, 1f))
                        .background(Tokens.red),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ge.dakalebi.web.ui.AppButton(
                label = S.watch.caps,
                onClick = onPlayNext,
                tone = ge.dakalebi.web.ui.ButtonTone.Primary,
            )
            ge.dakalebi.web.ui.AppButton(
                label = S.dismiss.caps,
                onClick = onDismiss,
                tone = ge.dakalebi.web.ui.ButtonTone.Quiet,
            )
        }
    }
}

/** Drawn while the URL is being resolved, or when it could not be. */
@Composable
fun PlayerPlaceholder(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Tokens.elev), contentAlignment = Alignment.Center) {
        Text(text, color = Tokens.mut, fontSize = 12.5.sp)
    }
}
