package ge.dakalebi.ui.watch

import androidx.compose.runtime.Composable
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import ge.dakalebi.ui.Icon
import ge.dakalebi.ui.Icons
import ge.dakalebi.ui.Thumb
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

@Composable
fun WatchNav(episode: Episode?) {
    Div({ classes("nav", "nav-solid") }) {
        A(href = Router.href(Route.Dashboard), attrs = { classes("btn", "btn-quiet") }) {
            Icon(Icons.back)
            Text(S.back.caps)
        }
        episode?.let {
            Span({ classes("eyebrow-mut") }) {
                Text(S.seasonAndEpisode(it.seasonNumber, it.episodeNumber).caps)
            }
        }
    }
}

@Composable
fun NextEpisodeCard(
    episode: Episode,
    countdown: Double,
    autoplayOn: Boolean,
    onPlayNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    Div({ classes("nextcard") }) {
        Div({ classes("nextcard-th") }) { Thumb(episode, showLabel = false) }
        Div({ classes("nextcard-b") }) {
            Div({ classes("eyebrow") }) { Text(S.nextEpisode.caps) }
            Div({ classes("nextcard-t") }) {
                Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
            }
            if (autoplayOn) {
                Div({ classes("nextcard-bar") }) {
                    Div({ style { property("width", "${(countdown * 100).coerceIn(0.0, 100.0)}%") } })
                }
            }
            Div({ classes("nextcard-row") }) {
                Button({ classes("btn", "btn-primary"); style { property("padding", "7px 13px") }; onClick { onPlayNext() } }) {
                    Text(S.watch.caps)
                }
                Button({ classes("btn", "btn-quiet"); onClick { onDismiss() } }) { Text(S.dismiss.caps) }
            }
        }
    }
}
