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
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
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
                Toasts.error("მოქმედება ვერ შესრულდა")
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
                            title = "გაგრძელება",
                            subtitle = "${inProgress.size}",
                            episodes = inProgress,
                            progress = Library.progress,
                        )
                    }

                    Div {
                        Div({ classes("rail-head") }) {
                            H2 { Text("სეზონები") }
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
                                    Text("სეზონი $number")
                                    if (all.isNotEmpty() && done == all.size) {
                                        Span({ classes("done") }) { Text("✓") }
                                    }
                                }
                            }
                        }
                    }

                    Div {
                        Div({ classes("rail-head") }) {
                            H2 { Text(season?.let { "სეზონი $it" } ?: "სერიები") }
                            Span({ classes("count") }) {
                                val done = episodes.count { Library.progress[it.id]?.isWatched == true }
                                Text("${episodes.size} სერია · $done ნანახი")
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
            title = "მთლიანი პროგრესის წაშლა?",
            body = "ყველა სეზონისა და სერიის პროგრესი წაიშლება. სერიალი გამოჩნდება ისე, თითქოს თავიდან იწყებ ყურებას.",
            confirmLabel = "ყველაფრის წაშლა",
            destructive = true,
            onConfirm = { uid?.let { id -> act("მთლიანი პროგრესი წაიშალა") { Library.resetAll(id) } } },
            onDismiss = { confirm = Confirm.None },
        )
        Confirm.MarkSeason -> ConfirmDialog(
            title = "სეზონის ნანახად მონიშვნა?",
            body = "მოინიშნოს სეზონი ${season ?: ""}-ის ყველა სერია ნანახად?",
            confirmLabel = "მონიშვნა",
            onConfirm = {
                val s = season
                if (uid != null && s != null) act("სეზონი მოინიშნა ნანახად") {
                    Library.markSeasonWatched(uid, s)
                }
            },
            onDismiss = { confirm = Confirm.None },
        )
        Confirm.ResetSeason -> ConfirmDialog(
            title = "სეზონის პროგრესის წაშლა?",
            body = "წაიშალოს სეზონი ${season ?: ""}-ის ყველა სერიის პროგრესი?",
            confirmLabel = "წაშლა",
            destructive = true,
            onConfirm = {
                val s = season
                if (uid != null && s != null) act("სეზონის პროგრესი წაიშალა") {
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
            attr("aria-label", "მენიუ")
            onClick { onMenu() }
        }) { Icon(Icons.menu, "მენიუ") }
        Span({ classes("nav-logo") }) { Text("დაქალები") }
        Div({ classes("nav-right") }) {
            if (Library.refreshing) {
                Span({ classes("count"); style { property("font-size", "11px"); property("color", "var(--mut)") } }) {
                    Text(Library.refreshNote ?: "მიმდინარეობს...")
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
                Text(if (resuming) "გაგრძელება" else if (watched) "ბოლო ნანახი" else "დასაწყისი")
            }
            H1({ classes("hero-h") }) {
                Text("სეზონი ${episode.seasonNumber}")
                Div { Text("სერია ${episode.episodeNumber}") }
            }
            if (percent > 0) {
                Div({ classes("hero-bar") }) {
                    Div({ style { property("width", "$percent%") } })
                }
            }
            Div({ classes("hero-sub") }) {
                Text(
                    when {
                        watched -> "ნანახია"
                        duration != null && duration > 0 && position > 0 -> {
                            val left = ((duration - position) / 60.0).roundToInt()
                            "დარჩა $left წუთი · ${formatTime(position.toDouble())} / ${formatTime(duration.toDouble())}"
                        }
                        else -> episode.title ?: "სერია ${episode.episodeNumber}"
                    },
                )
            }
            Div({ classes("hero-cta") }) {
                A(href = Router.href(Route.Watch(episode.id)), attrs = { classes("btn", "btn-primary") }) {
                    Text(if (resuming) "▶  გაგრძელება" else "▶  ყურება")
                }
                if (!episode.hasVideo) {
                    Span({ classes("hero-sub") }) { Text("ვიდეო ამ სერიისთვის მიუწვდომელია") }
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
            attr("aria-label", "სეზონის მოქმედებები")
            if (disabled) attr("disabled", "")
            onClick { open = !open }
        }) { Icon(Icons.more, "სეზონის მოქმედებები") }

        if (open) {
            Div({ classes("scrim"); style { property("background", "transparent") }; onClick { open = false } })
            Div({ classes("menu") }) {
                Button({ classes("menu-item"); onClick { open = false; onMark() } }) {
                    Text("სეზონის ნანახად მონიშვნა")
                }
                Button({ classes("menu-item", "danger"); onClick { open = false; onReset() } }) {
                    Text("სეზონის პროგრესის წაშლა")
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
            Div({ classes("eyebrow-mut") }) { Text("მენიუ") }
            Div({ style { property("font-size", "13px"); property("color", "var(--tx-dim)") } }) {
                Text(AuthStore.email ?: "")
            }
        }

        Div({ classes("sheet-stats") }) {
            Stat("${Library.watchedCount}/${Library.episodes.size}", "ნანახი")
            Stat("${Library.startedCount}", "დაწყებული")
            Stat("${Library.percentWatched}%", "პროგრესი")
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
                    Text(if (Library.refreshing) (Library.refreshNote ?: "მიმდინარეობს...") else "სერიების განახლება")
                }
            }
            Button({
                classes("sheet-item", "danger")
                if (busy) attr("disabled", "")
                onClick { onResetAll() }
            }) { Text("მთლიანი პროგრესის წაშლა") }
            Button({ classes("sheet-item"); onClick { onSignOut() } }) { Text("გასვლა") }
        }

        Div {
            Div({ classes("eyebrow-mut"); style { property("margin-bottom", "8px") } }) { Text("პარამეტრები") }
            Button({
                classes("toggle-row")
                onClick { Prefs.setAutoplayNext(!Prefs.autoplayNext) }
            }) {
                Div({ classes("lab") }) {
                    Div { Text("შემდეგი სერიის ავტომატურად ჩართვა") }
                    Span { Text("სერიის დასრულებისას შემდეგი ავტომატურად ჩაირთვება.") }
                }
                Div({ classNames("switch", if (Prefs.autoplayNext) "on" else null) }) { Div() }
            }
        }

        Div({ style { property("margin-top", "auto"); property("font-size", "11px"); property("color", "var(--mut)") } }) {
            Text("ბოლო განახლება: ${formatDateTime(Library.meta?.lastRefreshAtMillis)}")
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
            Div({ classes("rail-head") }) { H2 { Text("იტვირთება") } }
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
            Div({ classes("eyebrow-mut") }) { Text("ჩატვირთვა ვერ მოხერხდა") }
            Div { Text(message) }
            Button({ classes("btn", "btn-primary"); onClick { onRetry() } }) {
                Text("თავიდან ცდა")
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
            Div({ classes("eyebrow-mut") }) { Text("ცარიელია") }
            Div { Text("ბაზაში სერიები ჯერ არ არის.") }
            if (canRefresh) {
                Button({
                    classes("btn", "btn-primary")
                    if (refreshing) attr("disabled", "")
                    onClick { onRefresh() }
                }) { Text(if (refreshing) (note ?: "მიმდინარეობს...") else "სერიების ჩამოტვირთვა") }
            } else {
                Span { Text("დაელოდე, სანამ ადმინი ჩამოტვირთავს სერიებს.") }
            }
        }
    }
}
