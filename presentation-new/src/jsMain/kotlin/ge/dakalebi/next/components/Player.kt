package ge.dakalebi.next.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.core.formatTime
import ge.dakalebi.i18n.S
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.max

/**
 * Where the episode is, and the place to move it: a range input over the progress line,
 * with the time played under its start and the time left under its end.
 *
 * A native range, so a keyboard and a screen reader can use it with nothing added. While
 * a finger or the mouse drags it, it shows the drag rather than the video, and [onSeek]
 * fires once, on release.
 *
 * [onMedia] is the light version for over the picture. Without a [duration] it is drawn
 * but cannot move.
 */
@Composable
fun Scrubber(
    position: Double,
    duration: Double?,
    onSeek: (Double) -> Unit,
    onMedia: Boolean = false,
    attrs: ExtraAttrs? = null,
) {
    var dragging by remember { mutableStateOf<Double?>(null) }
    val input = remember { InputRef() }
    val end = duration?.takeIf { it > 0 } ?: 0.0
    val shown = (dragging ?: position).coerceIn(0.0, max(end, 0.0))
    val percent = if (end > 0) shown / end * 100 else 0.0

    // Straight on the element, not among the attributes below. Compose HTML answers any
    // change in the attributes by removing them all and setting them again, `type`
    // included, and a range that loses its type mid-drag drops the drag: no `change`, no
    // seek. The spoken time changes every second, so it stays out of that set, which then
    // only changes with the duration.
    val spokenTime = "${formatTime(shown)} / ${formatTime(end)}"
    SideEffect { input.element?.setAttribute("aria-valuetext", spokenTime) }

    Div({
        classes("scrub")
        if (onMedia) classes("scrub--on-media")
        attrs?.invoke(this)
    }) {
        Input(InputType.Range) {
            classes("scrub-input")
            attr("min", "0")
            attr("max", "$end")
            attr("step", "1")
            value(shown)
            attr("aria-label", S.timeline)
            ref { element ->
                input.element = element
                element.setAttribute("aria-valuetext", spokenTime)
                onDispose { input.element = null }
            }
            if (end <= 0) disabled()
            style { property("--scrub-value", "$percent%") }
            onInput { event -> dragging = event.value?.toDouble() }
            onChange { event ->
                val target = event.value?.toDouble()
                dragging = null
                if (target != null) onSeek(target)
            }
        }
        Div({ classes("scrub-times") }) {
            Span { Text(formatTime(shown)) }
            Span { Text(if (end > 0) "−${formatTime(end - shown)}" else formatTime(0.0)) }
        }
    }
}

/**
 * The player's five buttons: previous episode, back ten seconds, play or pause (the big
 * amber one), forward ten seconds, next episode.
 *
 * A missing [onPrevious] or [onNext] draws its button disabled, so the row keeps its shape
 * on the first and the last episode. [compact] is the smaller row over the picture in full
 * screen.
 */
@Composable
fun Transport(
    playing: Boolean,
    onToggle: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    skipSeconds: Int = 10,
    compact: Boolean = false,
) {
    val variant = if (compact) IconButtonVariant.OnMedia else IconButtonVariant.Quiet
    Div({
        classes("transport")
        if (compact) classes("transport--compact")
    }) {
        IconButton(
            Glyph.SkipBack,
            S.previousEpisodeAction,
            onClick = { onPrevious?.invoke() },
            variant = variant,
            disabled = onPrevious == null,
        )
        IconButton(Glyph.Back10, S.skipBack(skipSeconds), onClick = onBack, variant = variant)
        IconButton(
            if (playing) Glyph.Pause else Glyph.Play,
            if (playing) S.pause else S.play,
            onClick = onToggle,
            variant = IconButtonVariant.Primary,
            attrs = { classes("transport-primary") },
        )
        IconButton(Glyph.Forward10, S.skipForward(skipSeconds), onClick = onForward, variant = variant)
        IconButton(
            Glyph.SkipForward,
            S.nextEpisodeAction,
            onClick = { onNext?.invoke() },
            variant = variant,
            disabled = onNext == null,
        )
    }
}
