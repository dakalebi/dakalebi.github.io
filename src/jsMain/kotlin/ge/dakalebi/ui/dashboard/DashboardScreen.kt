package ge.dakalebi.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.core.formatTime
import ge.dakalebi.di.catalog
import ge.dakalebi.di.session
import ge.dakalebi.di.toasts
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.CatalogStore
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import ge.dakalebi.presentation.ToastStore
import ge.dakalebi.ui.ConfirmDialog
import ge.dakalebi.ui.EpisodeTile
import ge.dakalebi.ui.Icon
import ge.dakalebi.ui.Icons
import ge.dakalebi.ui.Rail
import ge.dakalebi.ui.Thumb
import ge.dakalebi.ui.classNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.roundToInt

private enum class Confirm { None, ResetAll, MarkSeason, ResetSeason }

@Composable
fun DashboardScreen() {
    val session = session()
    val catalog = catalog()
    val toasts = toasts()
    val scope = rememberCoroutineScope()
    val uid = session.uid

    var menuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(Confirm.None) }
    var seasonOverride by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(uid) { if (uid != null) catalog.ensureLoaded(uid) }

    val season = seasonOverride ?: catalog.defaultSeason
    val episodes = season?.let { catalog.season(it) } ?: emptyList()
    val inProgress = catalog.inProgress(limit = 12)

    fun act(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        confirm = Confirm.None
        scope.launch {
            try {
                block()
                toasts.ok(label)
            } catch (e: Throwable) {
                Log.e("dashboard", "action failed: $label", e)
                toasts.error(S.actionFailed)
            } finally {
                busy = false
            }
        }
    }

    Div {
        DashboardNav(onMenu = { menuOpen = true })

        when {
            catalog.loading -> LoadingRails()

            catalog.loadError != null -> LoadFailed(
                message = ErrorMessages.catalogLoad(catalog.loadError),
                onRetry = { uid?.let { id -> scope.launch { catalog.ensureLoaded(id) } } },
            )

            catalog.episodes.isEmpty() -> EmptyCatalog(
                canRefresh = session.isAdmin,
                refreshing = catalog.refreshing,
                note = catalog.refreshNote,
                onRefresh = { scope.refreshCatalog(catalog, toasts) },
            )

            else -> {
                catalog.continueWatching?.let { Hero(it) }

                Div({ classes("rails") }) {
                    if (inProgress.isNotEmpty()) {
                        Rail(
                            title = S.continueLabel.caps,
                            subtitle = "${inProgress.size}",
                            episodes = inProgress,
                            progress = catalog.progress,
                        )
                    }

                    Div {
                        Div({ classes("rail-head") }) {
                            H2 { Text(S.seasons.caps) }
                            Span({ classes("count") }) { Text("${catalog.seasons.size}") }
                        }
                        Div({ classes("chips") }) {
                            catalog.seasons.forEach { number ->
                                val all = catalog.season(number)
                                val done = all.count { catalog.progress[it.id]?.isWatched == true }
                                Button({
                                    classNames("chip", if (number == season) "sel" else null)
                                    onClick { seasonOverride = number }
                                }) {
                                    Text(S.season(number).caps)
                                    if (all.isNotEmpty() && done == all.size) {
                                        Span({ classes("done") }) { Text("✓") }
                                    }
                                }
                            }
                        }
                    }

                    Div {
                        Div({ classes("rail-head") }) {
                            H2 { Text((season?.let { S.season(it) } ?: S.episodes).caps) }
                            Span({ classes("count") }) {
                                val done = episodes.count { catalog.progress[it.id]?.isWatched == true }
                                Text(S.episodeCount(episodes.size, done).caps)
                            }
                            Div({ classes("spacer") })
                            SeasonMenu(
                                disabled = busy || season == null,
                                onMark = { confirm = Confirm.MarkSeason },
                                onReset = { confirm = Confirm.ResetSeason },
                            )
                        }
                        Div({ classes("grid") }) {
                            episodes.forEach { episode ->
                                EpisodeTile(episode, catalog.progress[episode.id])
                            }
                        }
                    }
                }
            }
        }
    }

    if (menuOpen) {
        MenuSheet(
            onClose = { menuOpen = false },
            onResetAll = { menuOpen = false; confirm = Confirm.ResetAll },
            onRefresh = { scope.refreshCatalog(catalog, toasts) },
            onSignOut = { scope.launch { session.signOut() } },
            busy = busy,
        )
    }

    when (confirm) {
        Confirm.ResetAll -> ConfirmDialog(
            title = S.resetAllTitle,
            body = S.resetAllBody,
            confirmLabel = S.resetAllConfirm.caps,
            destructive = true,
            onConfirm = { uid?.let { id -> act(S.resetAllDone) { catalog.resetAll(id) } } },
            onDismiss = { confirm = Confirm.None },
        )

        Confirm.MarkSeason -> ConfirmDialog(
            title = S.markSeasonTitle,
            body = S.markSeasonBody("${season ?: ""}"),
            confirmLabel = S.markSeasonConfirm.caps,
            onConfirm = {
                val s = season
                if (uid != null && s != null) {
                    act(S.markSeasonDone) { catalog.markSeasonWatched(uid, s) }
                }
            },
            onDismiss = { confirm = Confirm.None },
        )

        Confirm.ResetSeason -> ConfirmDialog(
            title = S.resetSeasonTitle,
            body = S.resetSeasonBody("${season ?: ""}"),
            confirmLabel = S.delete.caps,
            destructive = true,
            onConfirm = {
                val s = season
                if (uid != null && s != null) {
                    act(S.resetSeasonDone) { catalog.resetSeason(uid, s) }
                }
            },
            onDismiss = { confirm = Confirm.None },
        )

        Confirm.None -> Unit
    }
}

/**
 * Kicks off a rebuild and reports it.
 *
 * The store runs the work and owns the in-flight state; the words — the
 * progress counter and both outcomes — are chosen here, which is why this sits
 * in the UI rather than in the store.
 */
private fun CoroutineScope.refreshCatalog(catalog: CatalogStore, toasts: ToastStore) {
    launch {
        catalog.refreshCatalog { done, total ->
            if (total == 0) S.refreshingEpisodes else S.refreshSeasonProgress(done, total)
        }
            .onSuccess { toasts.ok(S.refreshed(it.episodes, it.written, it.withoutVideo)) }
            .onFailure { toasts.error(ErrorMessages.catalogRefresh(it)) }
    }
}

@Composable
private fun DashboardNav(onMenu: () -> Unit) {
    val session = session()
    val catalog = catalog()

    Div({ classes("nav") }) {
        Button({
            classes("icon-btn")
            attr("aria-label", S.menu)
            onClick { onMenu() }
        }) { Icon(Icons.menu, S.menu) }
        // The mark is lettering, so it *is* the wordmark — the name lives in
        // `alt` rather than being repeated beside it.
        Img(src = "logo.png", alt = S.appName) { classes("nav-mark") }
        Div({ classes("nav-right") }) {
            if (catalog.refreshing) {
                Span({
                    classes("count")
                    style { property("font-size", "11px"); property("color", "var(--mut)") }
                }) {
                    Text(catalog.refreshNote ?: S.refreshing)
                }
            }
            Div({ classes("avatar"); attr("title", session.email ?: "") }) {
                Text(session.email?.take(1)?.uppercase() ?: "?")
            }
        }
    }
}

@Composable
private fun Hero(episode: Episode) {
    val catalog = catalog()
    val entry = catalog.progress[episode.id]
    val watched = entry?.isWatched == true
    val position = entry?.progressSeconds ?: 0
    val duration = entry?.durationSeconds ?: episode.durationSeconds
    val percent = entry?.percent ?: 0.0
    val resuming = !watched && position > 5

    Div({ classes("hero") }) {
        Div({ classes("hero-img") }) { Thumb(episode, showLabel = false) }
        Div({ classes("hero-scrim") })
        Div({ classes("hero-body") }) {
            Div({ classes("eyebrow") }) {
                Text(
                    when {
                        resuming -> S.continueLabel
                        watched -> S.lastWatched
                        else -> S.beginning
                    }.caps,
                )
            }
            H1({ classes("hero-h") }) {
                Text(S.season(episode.seasonNumber).caps)
                Div { Text(S.episode(episode.episodeNumber).caps) }
            }
            if (percent > 0) {
                Div({ classes("hero-bar") }) {
                    Div({ style { property("width", "$percent%") } })
                }
            }
            Div({ classes("hero-sub") }) {
                Text(
                    when {
                        watched -> S.watchedLabel
                        duration != null && duration > 0 && position > 0 -> {
                            val left = ((duration - position) / 60.0).roundToInt()
                            S.minutesLeft(
                                left,
                                formatTime(position.toDouble()),
                                formatTime(duration.toDouble()),
                            )
                        }

                        else -> episode.title ?: S.episode(episode.episodeNumber)
                    },
                )
            }
            Div({ classes("hero-cta") }) {
                A(
                    href = Router.href(Route.Watch(episode.id)),
                    attrs = { classes("btn", "btn-primary") },
                ) {
                    Text("▶  " + (if (resuming) S.resume else S.watch).caps)
                }
                if (!episode.hasVideo) {
                    Span({ classes("hero-sub") }) { Text(S.videoUnavailableForEpisode) }
                }
            }
        }
    }
}

@Composable
private fun SeasonMenu(disabled: Boolean, onMark: () -> Unit, onReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Div({ classes("rel") }) {
        Button({
            classes("icon-btn")
            attr("aria-label", S.seasonActions)
            if (disabled) attr("disabled", "")
            onClick { open = !open }
        }) { Icon(Icons.more, S.seasonActions) }

        if (open) {
            Div({ classes("popover-catch"); onClick { open = false } })
            Div({ classes("menu") }) {
                Button({ classes("menu-item"); onClick { open = false; onMark() } }) {
                    Text(S.markSeasonWatched.caps)
                }
                Button({ classes("menu-item", "danger"); onClick { open = false; onReset() } }) {
                    Text(S.resetSeasonProgress.caps)
                }
            }
        }
    }
}
