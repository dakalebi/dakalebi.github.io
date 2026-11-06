package ge.dakalebi.next.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.Toast
import ge.dakalebi.presentation.ToastKind
import kotlin.math.roundToInt
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * How far along something is, as a bar. [value] is 0 to 1.
 *
 * A bar alone is not enough: it is a `progressbar` named by [label] whose value is read
 * as [valueText] (a percentage when null). [thin] is the hairline under a thumbnail.
 */
@Composable
fun ProgressBar(value: Double, label: String, valueText: String? = null, thin: Boolean = false) {
    val percent = (value.coerceIn(0.0, 1.0) * 100).roundToInt()

    Div({
        classes("progress")
        if (thin) classes("progress--thin")
        attr("role", "progressbar")
        attr("aria-label", label)
        attr("aria-valuemin", "0")
        attr("aria-valuemax", "100")
        attr("aria-valuenow", "$percent")
        attr("aria-valuetext", valueText ?: "$percent%")
    }) {
        Div({
            classes("progress-fill")
            style { width(percent.percent) }
        })
    }
}

/** The shapes a loading screen is drawn with before its content arrives. */
enum class SkeletonShape {
    /** One line of text. */
    Line,

    /** An episode row: a 16:9 thumbnail and two lines. */
    Row,

    /** A TV or grid tile: a 16:9 block and a line under it. */
    Tile,

    /** A season ring. */
    Ring,
}

/**
 * A placeholder in the shape of what is loading. Hidden from screen readers: the region
 * that holds it says it is busy instead (`aria-busy` and a "loading" text). It shimmers,
 * except under reduced motion, where it stays still.
 */
@Composable
fun Skeleton(shape: SkeletonShape) {
    when (shape) {
        SkeletonShape.Line -> Div({ classes("skeleton", "skeleton--line"); attr("aria-hidden", "true") })

        SkeletonShape.Row -> Div({ classes("skeleton-row"); attr("aria-hidden", "true") }) {
            Div({ classes("skeleton", "skeleton--thumb") })
            Div({ classes("skeleton-lines") }) {
                Div({ classes("skeleton", "skeleton--line") })
                Div({ classes("skeleton", "skeleton--line", "skeleton--short") })
            }
        }

        SkeletonShape.Tile -> Div({ classes("skeleton-tile"); attr("aria-hidden", "true") }) {
            Div({ classes("skeleton", "skeleton--thumb") })
            Div({ classes("skeleton", "skeleton--line", "skeleton--short") })
        }

        SkeletonShape.Ring -> Div({ classes("skeleton", "skeleton--ring"); attr("aria-hidden", "true") })
    }
}

/**
 * Something is happening and its length is unknown. The player's buffering only: lists
 * and screens load with a [Skeleton], which shows what is coming.
 *
 * [onMedia] is the light version for over video. Under reduced motion it pulses instead
 * of spinning.
 */
@Composable
fun Spinner(label: String = S.loading, onMedia: Boolean = false) {
    Span({
        classes("spinner")
        if (onMedia) classes("spinner--on-media")
        attr("role", "progressbar")
        attr("aria-label", label)
    })
}

/** A short tag, such as "guest". The label-s role, not caps. */
@Composable
fun Badge(text: String) {
    Span({ classes("badge") }) { Text(text) }
}

/**
 * Something went wrong here, and what to do about it. An alert, so it is read out when
 * it appears.
 *
 * The action ([actionLabel], usually "retry") is drawn after the words. [actionAttrs]
 * go on its button. With [actionHref] it is a link instead, such as "Open on Cavea
 * Plus", opened in a new tab when [actionExternal]; [onAction] still runs on the click.
 */
@Composable
fun Notice(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    actionAttrs: ExtraAttrs? = null,
    actionHref: String? = null,
    actionExternal: Boolean = false,
    actionIcon: Glyph? = null,
) {
    Div({ classes("notice"); attr("role", "alert") }) {
        Icon(Glyph.CircleAlert)
        Div({ classes("notice-text") }) {
            P({ classes("notice-title") }) { Text(title) }
            if (body != null) P({ classes("notice-body") }) { Text(body) }
        }
        if (actionLabel != null) {
            Button(
                actionLabel,
                onClick = onAction,
                variant = ButtonVariant.Secondary,
                icon = actionIcon,
                href = actionHref,
                external = actionExternal,
                attrs = actionAttrs,
            )
        }
    }
}

/**
 * A place with nothing in it yet: a title, one line on why, and one thing to do.
 * [glyph] is an optional large icon above the words.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    glyph: Glyph? = null,
    actionAttrs: ExtraAttrs? = null,
) {
    Div({ classes("empty") }) {
        if (glyph != null) {
            Span({ classes("empty-glyph") }) { Icon(glyph) }
        }
        H2({ classes("empty-title") }) { Text(title.caps) }
        P({ classes("empty-body") }) { Text(body) }
        if (actionLabel != null) {
            Button(actionLabel, onClick = onAction, variant = ButtonVariant.Primary, attrs = actionAttrs)
        }
    }
}

/**
 * Where toasts appear: one live region at the bottom of the screen, always present so a
 * screen reader is listening before the first message. Polite, and only additions are
 * read, so a second toast does not repeat the first.
 *
 * It sits above the tab bar and the mini-player: a screen that shows them sets
 * `--toast-offset` to their height. [toasts] comes from `ToastStore.items`, which also
 * removes them when their time is up.
 */
@Composable
fun ToastRegion(toasts: List<Toast>) {
    Div({
        classes("toast-region")
        attr("role", "status")
        attr("aria-live", "polite")
        attr("aria-atomic", "false")
    }) {
        toasts.forEach { toast ->
            key(toast.id) {
                Div({
                    classes("toast")
                    when (toast.kind) {
                        ToastKind.Plain -> Unit
                        ToastKind.Ok -> classes("toast--ok")
                        ToastKind.Error -> classes("toast--error")
                    }
                }) {
                    when (toast.kind) {
                        ToastKind.Plain -> Unit
                        ToastKind.Ok -> Icon(Glyph.Check, small = true)
                        ToastKind.Error -> Icon(Glyph.CircleAlert, small = true)
                    }
                    Span({ classes("toast-text") }) { Text(toast.message) }
                }
            }
        }
    }
}
