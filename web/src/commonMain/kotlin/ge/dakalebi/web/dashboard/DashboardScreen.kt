package ge.dakalebi.web.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ge.dakalebi.core.Log
import ge.dakalebi.core.formatTime
import ge.dakalebi.di.catalog
import ge.dakalebi.di.router
import ge.dakalebi.di.session
import ge.dakalebi.di.toasts
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.CatalogStore
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.ToastStore
import ge.dakalebi.web.CenterNote
import ge.dakalebi.web.ui.AppButton
import ge.dakalebi.web.ui.AppIcons
import ge.dakalebi.web.ui.ButtonTone
import ge.dakalebi.web.ui.Chip
import ge.dakalebi.web.ui.ConfirmDialog
import ge.dakalebi.web.ui.EpisodeTile
import ge.dakalebi.web.ui.Eyebrow
import ge.dakalebi.web.ui.IconButton
import ge.dakalebi.web.ui.ProgressBar
import ge.dakalebi.web.ui.SectionHead
import ge.dakalebi.web.ui.TILE_WIDTH
import ge.dakalebi.web.ui.Thumb
import ge.dakalebi.web.ui.Tokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Confirm { None, ResetAll, MarkSeason, ResetSeason, SignOut }

@Composable
fun DashboardScreen() {
    val session = session()
    val catalog = catalog()
    val toasts = toasts()
    val router = router()
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

    fun open(episode: Episode) = router.go(Route.Watch(episode.id))

    Column(Modifier.fillMaxSize()) {
        DashboardNav(onMenu = { menuOpen = true })

        when {
            catalog.loading -> CenterNote(S.loading.caps)

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

            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                catalog.continueWatching?.let { Hero(it, onWatch = { open(it) }) }

                Column(Modifier.padding(horizontal = Tokens.pad, vertical = 20.dp)) {
                    if (inProgress.isNotEmpty()) {
                        SectionHead(S.continueLabel.caps, "${inProgress.size}")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.railGap)) {
                            items(inProgress, key = { it.id }) { episode ->
                                EpisodeTile(episode, catalog.progress[episode.id]) { open(episode) }
                            }
                        }
                        Spacer(Modifier.height(26.dp))
                    }

                    SectionHead(S.seasons.caps, "${catalog.seasons.size}")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(catalog.seasons, key = { it }) { number ->
                            val all = catalog.season(number)
                            val done = all.count { catalog.progress[it.id]?.isWatched == true }
                            Chip(
                                label = S.season(number).caps,
                                selected = number == season,
                                done = all.isNotEmpty() && done == all.size,
                                onClick = { seasonOverride = number },
                            )
                        }
                    }

                    Spacer(Modifier.height(26.dp))

                    val watched = episodes.count { catalog.progress[it.id]?.isWatched == true }
                    SectionHead(
                        title = (season?.let { S.season(it) } ?: S.episodes).caps,
                        count = S.episodeCount(episodes.size, watched).caps,
                    ) {
                        SeasonMenu(
                            enabled = !busy && season != null,
                            onMark = { confirm = Confirm.MarkSeason },
                            onReset = { confirm = Confirm.ResetSeason },
                        )
                    }
                    EpisodeGrid(episodes, onOpen = ::open)
                }
            }
        }
    }

    if (menuOpen) {
        MenuSheet(
            onClose = { menuOpen = false },
            onResetAll = { menuOpen = false; confirm = Confirm.ResetAll },
            onRefresh = { scope.refreshCatalog(catalog, toasts) },
            onSignOut = { menuOpen = false; confirm = Confirm.SignOut },
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
                val number = season
                if (uid != null && number != null) {
                    act(S.markSeasonDone) { catalog.markSeasonWatched(uid, number) }
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
                val number = season
                if (uid != null && number != null) {
                    act(S.resetSeasonDone) { catalog.resetSeason(uid, number) }
                }
            },
            onDismiss = { confirm = Confirm.None },
        )

        // Signing out costs a re-login to undo, which is exactly the case for asking first.
        Confirm.SignOut -> ConfirmDialog(
            title = S.signOutConfirmTitle,
            body = S.signOutConfirmBody,
            confirmLabel = S.signOut.caps,
            destructive = true,
            onConfirm = {
                confirm = Confirm.None
                scope.launch { session.signOut() }
            },
            onDismiss = { confirm = Confirm.None },
        )

        Confirm.None -> Unit
    }
}

/**
 * Kicks off a rebuild and reports it.
 *
 * The store runs the work and owns the in-flight state; the words — the progress counter and both
 * outcomes — are chosen here, which is why this sits in the UI rather than in the store.
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

    Row(
        Modifier
            .fillMaxWidth()
            .background(Tokens.bg)
            .padding(horizontal = Tokens.pad, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(AppIcons.menu, S.menu, onMenu, tint = Tokens.tx)
        Spacer(Modifier.width(6.dp))
        Text(
            text = S.appName.caps,
            color = Tokens.tx,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        if (catalog.refreshing) {
            Text(
                text = catalog.refreshNote ?: S.refreshing,
                color = Tokens.mut,
                fontSize = 11.sp,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        // The account's initial, which is all the DOM app shows here too.
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Tokens.elev2),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = session.email?.take(1)?.uppercase() ?: "?",
                color = Tokens.txDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The episode grid: fixed-width tiles that wrap.
 *
 * A `FlowRow` rather than a `LazyVerticalGrid`, because this sits inside the page's own vertical
 * scroll and a lazy grid cannot be measured against an unbounded height. It also matches what the
 * CSS did — `repeat(auto-fill, minmax(...))` is a wrap, not a fixed column count — and a season is
 * about thirty tiles, so there is nothing here worth virtualising.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeGrid(episodes: List<Episode>, onOpen: (Episode) -> Unit) {
    val catalog = catalog()
    if (episodes.isEmpty()) return

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Tokens.railGap),
        verticalArrangement = Arrangement.spacedBy(Tokens.railGap),
    ) {
        episodes.forEach { episode ->
            EpisodeTile(episode, catalog.progress[episode.id]) { onOpen(episode) }
        }
    }
}

/** The one episode the app thinks you want next, given the whole width of the page. */
@Composable
private fun Hero(episode: Episode, onWatch: () -> Unit) {
    val catalog = catalog()
    val entry = catalog.progress[episode.id]
    val watched = entry?.isWatched == true
    val position = entry?.progressSeconds ?: 0
    val duration = entry?.durationSeconds ?: episode.durationSeconds
    val percent = entry?.percent ?: 0.0
    val resuming = !watched && position > 5

    // Side by side rather than a full-bleed still with the copy over it. A real still is a DOM
    // element laid over the canvas, so anything written across it would be covered; the picture
    // therefore takes the right of the band and the copy sits beside it, over the app's own surface.
    Row(Modifier.fillMaxWidth().heightIn(min = 240.dp)) {
        Column(
            Modifier
                .weight(1.3f)
                .padding(start = Tokens.pad, end = 20.dp, top = 22.dp, bottom = 22.dp),
        ) {
            Eyebrow(
                text = when {
                    resuming -> S.continueLabel
                    watched -> S.lastWatched
                    else -> S.beginning
                }.caps,
                color = Tokens.red,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = S.season(episode.seasonNumber).caps,
                color = Tokens.tx,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = S.episode(episode.episodeNumber).caps,
                color = Tokens.tx,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            if (percent > 0) {
                Spacer(Modifier.height(10.dp))
                ProgressBar(percent, watched, Modifier.width(240.dp), height = 4)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    watched -> S.watchedLabel
                    duration != null && duration > 0 && position > 0 -> S.minutesLeft(
                        ((duration - position) / 60.0).roundToInt(),
                        formatTime(position.toDouble()),
                        formatTime(duration.toDouble()),
                    )

                    else -> episode.title ?: S.episode(episode.episodeNumber)
                },
                color = Tokens.txDim,
                fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppButton(
                    label = (if (resuming) S.resume else S.watch).caps,
                    onClick = onWatch,
                    tone = ButtonTone.Primary,
                    leading = { ge.dakalebi.web.ui.AppIconView(AppIcons.play, size = 13.dp) },
                )
                if (!episode.hasVideo) {
                    Spacer(Modifier.width(12.dp))
                    Text(S.videoUnavailableForEpisode, color = Tokens.mut, fontSize = 11.5.sp)
                }
            }
        }

        // Proportional, so a narrow window shrinks the picture rather than the sentence next to it.
        Thumb(
            episode = episode,
            showLabel = false,
            topCornerRadius = 14.dp,
            modifier = Modifier
                .weight(1f)
                .padding(end = Tokens.pad, top = 22.dp, bottom = 22.dp)
                .aspectRatio(16f / 9f)
                .clip(Tokens.radius),
        )
    }
}

/** Mark-season-watched and reset-season, in a small popover. */
@Composable
private fun SeasonMenu(enabled: Boolean, onMark: () -> Unit, onReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(AppIcons.more, S.seasonActions, { open = !open }, enabled = enabled)
        if (open) {
            Column(
                Modifier
                    .width(230.dp)
                    .clip(Tokens.radiusSmall)
                    .background(Tokens.elev2)
                    .padding(vertical = 6.dp),
            ) {
                MenuRow(S.markSeasonWatched.caps) { open = false; onMark() }
                MenuRow(S.resetSeasonProgress.caps, destructive = true) { open = false; onReset() }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (destructive) Tokens.red else Tokens.txDim,
        fontSize = 12.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    )
}

@Composable
private fun LoadFailed(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.pad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Eyebrow(S.loadFailedEyebrow.caps)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Tokens.txDim, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        AppButton(S.retry.caps, onRetry, ButtonTone.Primary)
    }
}

@Composable
private fun EmptyCatalog(
    canRefresh: Boolean,
    refreshing: Boolean,
    note: String?,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.pad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Eyebrow(S.emptyEyebrow.caps)
        Spacer(Modifier.height(8.dp))
        Text(S.emptyBody, color = Tokens.txDim, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        if (canRefresh) {
            AppButton(
                label = if (refreshing) note ?: S.refreshing else S.downloadEpisodes.caps,
                onClick = onRefresh,
                tone = ButtonTone.Primary,
                enabled = !refreshing,
            )
        } else {
            Text(S.waitForAdmin, color = Tokens.mut, fontSize = 12.sp)
        }
    }
}
