package ge.dakalebi.ui.dashboard

import androidx.compose.runtime.Composable
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** The three ways the dashboard can have nothing to show. */

@Composable
fun LoadingRails() {
    Div({ classes("rails"); style { property("padding-top", "80px") } }) {
        Div {
            Div({ classes("rail-head") }) { H2 { Text(S.loading.caps) } }
            Div({ classes("grid") }) {
                repeat(24) {
                    Div({ classes("tile") }) {
                        Div({ classes("skel", "skel-tile") })
                        Div({ classes("skel", "skel-line") })
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
    Div({ style { property("padding-top", "90px") } }) {
        Div({ classes("empty") }) {
            Div({ classes("eyebrow-mut") }) { Text(S.loadFailedEyebrow.caps) }
            Div { Text(message) }
            Button({ classes("btn", "btn-primary"); onClick { onRetry() } }) {
                Text(S.retry.caps)
            }
        }
    }
}

@Composable
fun EmptyCatalog(
    canRefresh: Boolean,
    refreshing: Boolean,
    note: String?,
    onRefresh: () -> Unit,
) {
    Div({ style { property("padding-top", "90px") } }) {
        Div({ classes("empty") }) {
            Div({ classes("eyebrow-mut") }) { Text(S.emptyEyebrow.caps) }
            Div { Text(S.emptyBody) }
            if (canRefresh) {
                Button({
                    classes("btn", "btn-primary")
                    if (refreshing) attr("disabled", "")
                    onClick { onRefresh() }
                }) { Text(if (refreshing) note ?: S.refreshing else S.downloadEpisodes.caps) }
            } else {
                Span { Text(S.waitForAdmin) }
            }
        }
    }
}
