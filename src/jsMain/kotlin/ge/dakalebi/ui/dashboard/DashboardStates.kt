package ge.dakalebi.ui.dashboard

import androidx.compose.runtime.Composable
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.i18n.cased
import ge.dakalebi.ui.Text
import io.github.bchmsl.keel.components.Button
import io.github.bchmsl.keel.components.EmptyState
import io.github.bchmsl.keel.components.Skeleton
import io.github.bchmsl.keel.components.SkeletonShape
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Span

/** The three ways the dashboard can have nothing to show. */

@Composable
fun LoadingRails() {
    Div({ classes("rails"); style { property("padding-top", "80px") } }) {
        Div {
            Div({ classes("rail-head") }) { H2 { Text(S.loading.caps) } }
            // `aria-busy` on the grid rather than a label on each shape: keel's
            // `Skeleton` is `aria-hidden`, because 24 placeholders each announcing
            // themselves tells a screen reader user less than one "busy" does.
            Div({ classes("grid"); attr("aria-busy", "true") }) {
                repeat(24) {
                    Div({ classes("tile") }) {
                        // The classes that survive carry only size - the thumbnail's
                        // aspect ratio and the line's width. keel owns the shimmer, and
                        // `SkeletonShape.Line` owns the line's height.
                        Skeleton(attrs = { classes("skel-tile") })
                        Skeleton(SkeletonShape.Line, attrs = { classes("skel-line") })
                    }
                }
            }
        }
    }
}

/**
 * Shown when the catalog could not be read. It carries a retry because the
 * alternative is a dead end: nothing else in the app calls `ensureLoaded`
 * again, so without this the only way out was a full page reload.
 */
@Composable
fun LoadFailed(message: String, onRetry: () -> Unit) {
    EmptyPage {
        // keel's EmptyState paints its strings itself, past this app's `Text`, so
        // they are cased here.
        EmptyState(
            title = S.loadFailedEyebrow.cased,
            body = message.cased,
            action = { Button(label = S.retry.caps, onClick = onRetry) },
        )
    }
}

@Composable
fun EmptyCatalog(
    canRefresh: Boolean,
    refreshing: Boolean,
    note: String?,
    onRefresh: () -> Unit,
) {
    EmptyPage {
        EmptyState(
            title = S.emptyEyebrow.cased,
            body = S.emptyBody.cased,
            action = {
                if (canRefresh) {
                    Button(
                        label = if (refreshing) (note ?: S.refreshing).cased else S.downloadEpisodes.caps,
                        onClick = onRefresh,
                        enabled = !refreshing,
                    )
                } else {
                    Span { Text(S.waitForAdmin) }
                }
            },
        )
    }
}

/**
 * The page gutter the two empty screens sit in.
 *
 * It is here rather than on the panel itself because it is a page concern: the old
 * `.empty` rule carried `margin: 0 var(--pad)` alongside its border and padding, and
 * separating the two is what let the rest of the rule go to keel.
 */
@Composable
private fun EmptyPage(content: @Composable () -> Unit) {
    Div({ style { property("padding", "90px var(--pad) 0") } }) { content() }
}
