package ge.dakalebi.ui.watch

import androidx.compose.runtime.Composable
import io.github.bchmsl.keel.components.Button
import io.github.bchmsl.keel.components.ButtonSize
import io.github.bchmsl.keel.components.ButtonVariant
import io.github.bchmsl.keel.components.LinkButton
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import ge.dakalebi.ui.Thumb
import io.github.bchmsl.keel.components.ProgressBar
import io.github.bchmsl.keel.components.ProgressBarSize
import io.github.bchmsl.keel.components.Surface
import io.github.bchmsl.keel.components.SurfacePadding
import io.github.bchmsl.keel.icons.Icon
import io.github.bchmsl.keel.icons.LucideIcon
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

@Composable
fun WatchNav(episode: Episode?) {
    Div({ classes("nav", "nav-solid") }) {
        LinkButton(
            href = Router.href(Route.Dashboard),
            label = S.back.caps,
            variant = ButtonVariant.Link,
            // Matches `.btn .ic svg` in web.css.
            leading = { Icon(LucideIcon.ChevronLeft, size = 16) },
        )
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
    // A floating panel over the video. keel owns the box; `.nextcard` keeps only
    // where it sits, how wide it is and the entry animation.
    Surface(padding = SurfacePadding.Small, attrs = { classes("nextcard") }) {
        Div({ classes("nextcard-th") }) { Thumb(episode) }
        Div({ classes("nextcard-b") }) {
            Div({ classes("eyebrow") }) { Text(S.nextEpisode.caps) }
            Div({ classes("nextcard-t") }) {
                Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
            }
            if (autoplayOn) {
                // `aria-hidden`, and keel still requires the label: a bar announcing
                // "22%" does not tell anyone that autoplay is four seconds away, and
                // the card already says which episode is next and offers the button.
                ProgressBar(
                    fraction = countdown,
                    ariaLabel = S.nextEpisode,
                    size = ProgressBarSize.Small,
                    onMedia = true,
                    attrs = { classes("nextcard-bar"); attr("aria-hidden", "true") },
                )
            }
            Div({ classes("nextcard-row") }) {
                Button(
                    label = S.watch.caps,
                    onClick = { onPlayNext() },
                    size = ButtonSize.Small,
                )
                Button(
                    label = S.dismiss.caps,
                    onClick = { onDismiss() },
                    variant = ButtonVariant.Link,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}
