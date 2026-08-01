package ge.dakalebi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ge.dakalebi.app.Log
import ge.dakalebi.app.Prefs
import ge.dakalebi.app.Route
import ge.dakalebi.app.Router
import ge.dakalebi.app.Toasts
import ge.dakalebi.app.formatDateTime
import ge.dakalebi.app.formatTime
import ge.dakalebi.auth.AuthStore
import ge.dakalebi.data.Episode
import ge.dakalebi.data.Library
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
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
    val scope = rememberCoroutineScope()
    val uid = AuthStore.uid

    var menuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(Confirm.None) }
    var seasonOverride by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(uid) {
        if (uid != null) Library.ensureLoaded(uid)
    }

    val season = seasonOverride ?: Library.defaultSeason
    val episodes = season?.let { Library.season(it) } ?: emptyList()
    val inProgress = Library.progress.values
        .filter { it.isStarted }
        .sortedByDescending { it.lastWatchedAtMillis }
        .mapNotNull { Library.byId(it.episodeId) }
        .take(12)

    fun act(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        confirm = Confirm.None
        scope.launch {
            try {
                block()
                Toasts.ok(label)
            } catch (e: Throwable) {
                Log.e("dashboard", "action failed: $label", e)
                Toasts.error(S.actionFailed)
            } finally {
                busy = false
            }
        }
    }

    Div {
        DashboardNav(onMenu = { menuOpen = true })

        when {
            Library.loading -> LoadingRails()
            Library.loadError != null -> LoadFailed(
                message = Library.loadError ?: "",
                onRetry = { uid?.let { id -> scope.launch { Library.ensureLoaded(id) } } },
            )
            Library.episodes.isEmpty() -> EmptyCatalog(
                canRefresh = AuthStore.isAdmin,
                refreshing = Library.refreshing,
                note = Library.refreshNote,
                onRefresh = { scope.launch { Library.refreshCatalog() } },
            )
            else -> {
                Library.continueWatching?.let { Hero(it) }

                Div({ classes("rails") }) {
                    if (inProgress.isNotEmpty()) {
                        Rail(
                            title = S.continueLabel.caps,
                            subtitle = "${inProgress.size}",
                            episodes = inProgress,
                            progress = Library.progress,
                        )
                    }

                    Div {
                        Div({ classes("rail-head") }) {
                            H2 { Text(S.seasons.caps) }
                            Span({ classes("count") }) { Text("${Library.seasons.size}") }
                        }
                        Div({ classes("chips") }) {
                            Library.seasons.forEach { number ->
                                val all = Library.season(number)
                                val done = all.count { Library.progress[it.id]?.isWatched == true }
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
                                val done = episodes.count { Library.progress[it.id]?.isWatched == true }
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
                                EpisodeTile(episode, Library.progress[episode.id])
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
            onRefresh = { scope.launch { Library.refreshCatalog() } },
            onSignOut = { scope.launch { AuthStore.signOutNow() } },
            busy = busy,
        )
    }

    when (confirm) {
        Confirm.ResetAll -> ConfirmDialog(
            title = S.resetAllTitle,
            body = S.resetAllBody,
            confirmLabel = S.resetAllConfirm.caps,
            destructive = true,
            onConfirm = { uid?.let { id -> act(S.resetAllDone) { Library.resetAll(id) } } },
            onDismiss = { confirm = Confirm.None },
        )
        Confirm.MarkSeason -> ConfirmDialog(
            title = S.markSeasonTitle,
            body = S.markSeasonBody("${season ?: ""}"),
            confirmLabel = S.markSeasonConfirm.caps,
            onConfirm = {
                val s = season
                if (uid != null && s != null) act(S.markSeasonDone) {
                    Library.markSeasonWatched(uid, s)
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
                if (uid != null && s != null) act(S.resetSeasonDone) {
                    Library.resetSeason(uid, s)
                }
            },
            onDismiss = { confirm = Confirm.None },
        )
        Confirm.None -> Unit
    }
}

@Composable
private fun DashboardNav(onMenu: () -> Unit) {
    Div({ classes("nav") }) {
        Button({
            classes("icon-btn")
            attr("aria-label", S.menu)
            onClick { onMenu() }
        }) { Icon(Icons.menu, S.menu) }
        // The mark is lettering, so it *is* the wordmark - the name lives in
        // `alt` rather than being repeated beside it.
        Img(src = "logo.png", alt = S.appName) { classes("nav-mark") }
        Div({ classes("nav-right") }) {
            if (Library.refreshing) {
                Span({ classes("count"); style { property("font-size", "11px"); property("color", "var(--mut)") } }) {
                    Text(Library.refreshNote ?: S.refreshing)
                }
            }
            Div({ classes("avatar"); attr("title", AuthStore.email ?: "") }) {
                Text(AuthStore.email?.take(1)?.uppercase() ?: "?")
            }
        }
    }
}

@Composable
private fun Hero(episode: Episode) {
    val entry = Library.progress[episode.id]
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
                Text((if (resuming) S.continueLabel else if (watched) S.lastWatched else S.beginning).caps)
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
                            S.minutesLeft(left, formatTime(position.toDouble()), formatTime(duration.toDouble()))
                        }
                        else -> episode.title ?: S.episode(episode.episodeNumber)
                    },
                )
            }
            Div({ classes("hero-cta") }) {
                A(href = Router.href(Route.Watch(episode.id)), attrs = { classes("btn", "btn-primary") }) {
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

@Composable
private fun MenuSheet(
    onClose: () -> Unit,
    onResetAll: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    busy: Boolean,
) {
    DismissOnEscape(onClose)
    Div({ classes("scrim"); onClick { onClose() } })
    Div({ classes("sheet") }) {
        Div {
            Div({ classes("eyebrow-mut") }) { Text(S.menu.caps) }
            Div({ style { property("font-size", "13px"); property("color", "var(--tx-dim)") } }) {
                Text(AuthStore.email ?: "")
            }
        }

        Div({ classes("sheet-stats") }) {
            Stat("${Library.watchedCount}/${Library.episodes.size}", S.statWatched.caps)
            Stat("${Library.startedCount}", S.statStarted.caps)
            Stat("${Library.percentWatched}%", S.statProgress.caps)
        }

        Div({ classes("hero-bar"); style { property("max-width", "none") } }) {
            Div({ style { property("width", "${Library.percentWatched}%") } })
        }

        Div({ classes("sheet-list") }) {
            if (AuthStore.isAdmin) {
                Button({
                    classes("sheet-item")
                    if (Library.refreshing) attr("disabled", "")
                    onClick { onRefresh() }
                }) {
                    Text(if (Library.refreshing) (Library.refreshNote ?: S.refreshing) else S.refreshEpisodes.caps)
                }
            }
            Button({
                classes("sheet-item", "danger")
                if (busy) attr("disabled", "")
                onClick { onResetAll() }
            }) { Text(S.resetAllProgress.caps) }
            Button({ classes("sheet-item"); onClick { onSignOut() } }) { Text(S.signOut.caps) }
        }

        Div {
            Div({ classes("eyebrow-mut"); style { property("margin-bottom", "8px") } }) { Text(S.settings.caps) }
            Button({
                classes("toggle-row")
                onClick { Prefs.setAutoplayNext(!Prefs.autoplayNext) }
            }) {
                Div({ classes("lab") }) {
                    Div { Text(S.autoplayTitle.caps) }
                    Span { Text(S.autoplayBody) }
                }
                Div({ classNames("switch", if (Prefs.autoplayNext) "on" else null) }) { Div() }
            }
        }

        Div({ style { property("margin-top", "auto"); property("font-size", "11px"); property("color", "var(--mut)") } }) {
            Text(S.lastRefreshed(formatDateTime(Library.meta?.lastRefreshAtMillis)))
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Div({ classes("stat") }) {
        Div({ classes("mono") }) { Text(value) }
        Span { Text(label) }
    }
}

@Composable
private fun LoadingRails() {
    Div({ classes("rails"); style { property("padding-top", "80px") } }) {
        Div {
            Div({ classes("rail-head") }) { H2 { Text(S.loading.caps) } }
            Div({ classes("grid") }) {
                repeat(8) {
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
private fun LoadFailed(message: String, onRetry: () -> Unit) {
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
private fun EmptyCatalog(
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
                }) { Text(if (refreshing) (note ?: S.refreshing) else S.downloadEpisodes.caps) }
            } else {
                Span { Text(S.waitForAdmin) }
            }
        }
    }
}
