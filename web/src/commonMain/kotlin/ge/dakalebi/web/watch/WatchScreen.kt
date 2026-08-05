package ge.dakalebi.web.watch

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ge.dakalebi.core.Log
import ge.dakalebi.di.LocalResolveEpisodeVideo
import ge.dakalebi.di.catalog
import ge.dakalebi.di.preferences
import ge.dakalebi.di.router
import ge.dakalebi.di.session
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.service.orderedQualityLabels
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.presentation.Route
import ge.dakalebi.web.CenterNote
import ge.dakalebi.web.copyToClipboard
import ge.dakalebi.web.openExternal
import ge.dakalebi.web.player.VideoCommands
import ge.dakalebi.web.player.VideoState
import ge.dakalebi.web.player.VideoSurface
import ge.dakalebi.web.ui.AppButton
import ge.dakalebi.web.ui.AppIcons
import ge.dakalebi.web.ui.ButtonTone
import ge.dakalebi.web.ui.ConfirmDialog
import ge.dakalebi.web.ui.EpisodeTile
import ge.dakalebi.web.ui.Eyebrow
import ge.dakalebi.web.ui.IconButton
import ge.dakalebi.web.ui.SectionHead
import ge.dakalebi.web.ui.Tokens
import kotlinx.coroutines.launch
import kotlin.math.floor

/** Seconds before the end at which the next-episode card appears. */
private const val PROMPT_WINDOW = 180.0

/** Save at most this often during continuous playback. */
private const val SAVE_EVERY = 7.0

/**
 * One episode: the player, what it is, and what to watch next.
 *
 * The rules are the DOM app's, and they are the part worth keeping identical — when to save, what
 * counts as watched, when the next-episode card appears, what autoplay does at the end. What
 * changed is that the picture now comes from a `<video>` under the canvas and the chrome is drawn
 * by Compose, so this file no longer owns any media plumbing: it owns decisions.
 */
@Composable
fun WatchScreen(episodeId: String) {
    val session = session()
    val catalog = catalog()
    val toasts = toasts()
    val router = router()
    val prefs = preferences()
    val resolveVideo = LocalResolveEpisodeVideo.current
    val autoplay = settings().autoplayNext

    val scope = rememberCoroutineScope()
    val uid = session.uid

    val state = remember(episodeId) { VideoState() }
    var commands by remember(episodeId) { mutableStateOf<VideoCommands?>(null) }
    val saved = remember(episodeId) { SaveTracker() }

    var videoUrl by remember(episodeId) { mutableStateOf<String?>(null) }
    var quality by remember(episodeId) { mutableStateOf<String?>(null) }
    // The renditions the menu offers. Held here so the label and the menu always read the same map.
    var available by remember(episodeId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember(episodeId) { mutableStateOf<String?>(null) }
    var ended by remember(episodeId) { mutableStateOf(false) }
    var promptDismissed by remember(episodeId) { mutableStateOf(false) }
    var confirmReset by remember(episodeId) { mutableStateOf(false) }

    LaunchedEffect(uid) { if (uid != null) catalog.ensureLoaded(uid) }

    val episode = catalog.byId(episodeId)
    val entry = episode?.let { catalog.progress[it.id] }
    val watched = entry?.isWatched == true
    val savedSeconds = entry?.progressSeconds ?: 0
    val progressReady = !catalog.loading
    val nextEpisode = episode?.let { catalog.next(it) }

    val shouldAutoplay = remember(episodeId) { prefs.playIntent(episodeId) != "paused" }

    // ------------------------------------------------------------------------ resolution

    LaunchedEffect(episode?.id) {
        val current = episode ?: return@LaunchedEffect
        error = null
        val resolved = resolveVideo(current)
        if (resolved !== current) catalog.putEpisode(resolved)

        // One map for everything downstream: the label and the quality menu have to agree about
        // which rendition is best, and the stored copy is in a different order from a fresh one.
        val sources = resolved.sources.ifEmpty { current.sources }
        available = sources
        val preferred = prefs.preferredQuality?.takeIf { sources.containsKey(it) }
        quality = preferred ?: orderedQualityLabels(sources).firstOrNull()
        videoUrl = preferred?.let { sources[it] }
            ?: resolved.videoUrl
            ?: quality?.let { sources[it] }

        if (videoUrl == null) error = S.videoLinkFailed
    }

    // ----------------------------------------------------------------------- persistence

    fun persist(isWatched: Boolean? = null, allowReset: Boolean = false) {
        val current = episode ?: return
        val id = uid ?: return
        val seconds = floor(state.positionSeconds).toInt()
        if (seconds < 1 && isWatched == null && !allowReset) return
        val duration = state.durationSeconds.takeIf { it > 0 }?.let { floor(it).toInt() }

        scope.launch {
            runCatching {
                catalog.saveProgress(
                    uid = id,
                    episodeId = current.id,
                    progressSeconds = if (allowReset) 0 else seconds,
                    durationSeconds = duration,
                    isWatched = isWatched,
                    allowReset = allowReset,
                )
            }.onFailure {
                // Silent, this would look exactly like "the app forgot where I was".
                Log.e("progress", "save failed for ${current.id} at ${seconds}s", it)
            }
        }
    }

    // Leaving the screen is the last chance to record the position: the element is torn down
    // immediately after, and nothing else in the app writes progress.
    DisposableEffect(episode?.id, uid) {
        onDispose { persist() }
    }

    // ------------------------------------------------------------------------ navigation

    fun goToNext() {
        val next = nextEpisode ?: return
        if (saved.navigated) return
        saved.navigated = true
        val duration = state.durationSeconds
        val nearEnd = duration > 0 && state.positionSeconds / duration >= 0.9
        persist(isWatched = if (nearEnd || ended) true else null)
        router.go(Route.Watch(next.id))
    }

    // ------------------------------------------------------------------------------- ui

    if (episode == null) {
        Column(Modifier.fillMaxSize().background(Tokens.bg)) {
            WatchNav(null)
            CenterNote(if (catalog.loading) S.loading else S.episodeNotFound)
        }
        return
    }

    // Null until the duration is known, which is also when the card must stay away: without a
    // duration there is no "three minutes from the end" to be inside.
    val remaining = (state.durationSeconds - state.positionSeconds)
        .takeIf { state.durationSeconds > 0 }
    val inWindow = remaining != null && remaining <= PROMPT_WINDOW
    val upNext = nextEpisode?.takeIf { inWindow && !ended && !promptDismissed }
    val countdown = remaining?.let { (PROMPT_WINDOW - it) / PROMPT_WINDOW } ?: 0.0

    // Re-arm the prompt if the viewer seeks back out of the window.
    LaunchedEffect(inWindow) {
        if (!inWindow) promptDismissed = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        WatchNav(episode)

        // The picture, then the chrome beneath it. The `<video>` is an element laid over the canvas,
        // so the app must not draw inside its rectangle — see `PlayerChrome`.
        Column(Modifier.fillMaxWidth().widthIn(max = 1180.dp)) {
            val url = videoUrl
            if (url != null && progressReady) {
                key(episode.id) {
                    VideoSurface(
                        url = url,
                        autoPlay = shouldAutoplay,
                        startAtSeconds = savedSeconds.toDouble(),
                        state = state,
                        onCommands = { commands = it },
                        onTimeUpdate = { position, _ ->
                            if (position - saved.lastSaved >= SAVE_EVERY) {
                                saved.lastSaved = position
                                persist()
                            }
                        },
                        onEnded = {
                            prefs.setPlayIntent(episodeId, "paused")
                            persist(isWatched = true)
                            ended = true
                            toasts.ok(S.markedAsWatched)
                            if (autoplay && nextEpisode != null) goToNext()
                        },
                        onError = {
                            if (!saved.retried) {
                                saved.retried = true
                                toasts.show(S.retryingVideo)
                                scope.launch {
                                    val resolved = resolveVideo(episode)
                                    catalog.putEpisode(resolved)
                                    videoUrl = resolved.videoUrl
                                    if (resolved.videoUrl == null) error = S.videoLoadFailed
                                }
                            } else {
                                error = S.videoLoadFailed
                            }
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                }

                // Remembering whether this episode was left playing is what stops next/previous
                // from resuming playback the viewer had deliberately paused.
                LaunchedEffect(state.paused) {
                    prefs.setPlayIntent(episodeId, if (state.paused) "paused" else "playing")
                    if (state.paused) persist()
                }

                PlayerChrome(state, commands)

                if (upNext != null) {
                    NextEpisodeCard(
                        title = S.seasonAndEpisode(
                            upNext.seasonNumber,
                            upNext.episodeNumber,
                        ).caps,
                        countdown = countdown,
                        autoplayOn = autoplay,
                        onPlayNext = { goToNext() },
                        onDismiss = { promptDismissed = true },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            } else {
                PlayerPlaceholder(
                    text = error ?: S.loading,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
        }

        Column(
            Modifier.background(Tokens.bg).padding(horizontal = Tokens.pad, vertical = 18.dp),
        ) {
            Text(
                text = S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps,
                color = Tokens.tx,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Tokens.red, fontSize = 12.5.sp)
            }

            if (ended && nextEpisode != null) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Tokens.radiusSmall)
                        .background(Tokens.elev)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = S.episodeFinished.caps,
                            color = Tokens.tx,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = S.nextUp(
                                nextEpisode.seasonNumber,
                                nextEpisode.episodeNumber,
                            ).caps,
                            color = Tokens.mut,
                            fontSize = 11.5.sp,
                        )
                    }
                    AppButton(S.nextEpisodeAction.caps, { goToNext() }, ButtonTone.Primary)
                }
            }

            Spacer(Modifier.height(16.dp))
            WatchActions(
                episode = episode,
                watched = watched,
                hasNext = nextEpisode != null,
                onFromStart = {
                    commands?.seekTo(0.0)
                    saved.lastSaved = 0.0
                    ended = false
                    persist(allowReset = true)
                    commands?.play()
                },
                onMarkWatched = {
                    persist(isWatched = true)
                    toasts.ok(S.episodeMarkedWatched)
                },
                onClearProgress = { confirmReset = true },
                onNext = { goToNext() },
                onCopyLink = {
                    copyToClipboard(router.absolute(Route.Watch(episode.id))) { ok ->
                        if (ok) toasts.ok(S.episodeLinkCopied) else toasts.error(S.copyFailed)
                    }
                },
                onCopyVideo = {
                    val target = videoUrl ?: episode.videoUrl
                    if (target != null) {
                        copyToClipboard(target) { ok ->
                            if (ok) toasts.ok(S.mp4LinkCopied) else toasts.error(S.copyFailed)
                        }
                    }
                },
                quality = quality,
                available = available,
                onQuality = { label ->
                    quality = label
                    prefs.setPreferredQuality(label)
                    available[label]?.let { videoUrl = it }
                },
            )

            Spacer(Modifier.height(26.dp))
            EpisodeStrip(S.nextEpisodes.caps, catalog.upcoming(episode, 8))
            Spacer(Modifier.height(22.dp))
            EpisodeStrip(S.previousEpisodes.caps, catalog.previous(episode, 4))
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = S.clearProgressTitle,
            body = S.clearProgressBody,
            confirmLabel = S.delete.caps,
            destructive = true,
            onConfirm = {
                confirmReset = false
                val id = uid ?: return@ConfirmDialog
                scope.launch {
                    runCatching { catalog.clearProgress(id, episode.id) }
                        .onSuccess {
                            commands?.seekTo(0.0)
                            saved.lastSaved = 0.0
                            ended = false
                            toasts.ok(S.progressCleared)
                        }
                        .onFailure {
                            Log.e("progress", "clear failed for ${episode.id}", it)
                            toasts.error(S.clearFailed)
                        }
                }
            },
            onDismiss = { confirmReset = false },
        )
    }
}

/**
 * The mutable, non-Compose half of the screen.
 *
 * These are written from media callbacks several times a second. Holding them as Compose state
 * would recompose the page on every `timeupdate`; one remembered object keyed on the episode gives
 * the callbacks somewhere to write that costs nothing and resets itself when the episode changes.
 */
private class SaveTracker {
    var lastSaved: Double = 0.0
    var retried: Boolean = false
    var navigated: Boolean = false
}

@Composable
private fun WatchNav(episode: Episode?) {
    val router = router()
    Row(
        Modifier
            .fillMaxWidth()
            .background(Tokens.bg)
            .padding(horizontal = Tokens.pad, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppButton(
            label = S.back.caps,
            onClick = { router.go(Route.Dashboard) },
            tone = ButtonTone.Quiet,
            leading = { ge.dakalebi.web.ui.AppIconView(AppIcons.back, size = 15.dp) },
        )
        Spacer(Modifier.width(10.dp))
        episode?.let {
            Eyebrow(S.seasonAndEpisode(it.seasonNumber, it.episodeNumber).caps)
        }
    }
}

/** Everything you can do to this episode, in one wrapping row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchActions(
    episode: Episode,
    watched: Boolean,
    hasNext: Boolean,
    onFromStart: () -> Unit,
    onMarkWatched: () -> Unit,
    onClearProgress: () -> Unit,
    onNext: () -> Unit,
    onCopyLink: () -> Unit,
    onCopyVideo: () -> Unit,
    quality: String?,
    available: Map<String, String>,
    onQuality: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppButton(S.watchFromStart.caps, onFromStart)

        if (watched) {
            AppButton(S.watchedTick.caps, onClearProgress)
        } else {
            AppButton(S.markAsWatched.caps, onMarkWatched)
        }

        if (hasNext) AppButton(S.nextEpisodeAction.caps, onNext, ButtonTone.Primary)

        AppButton(S.openOnFormula.caps, { openExternal(episode.episodePageUrl) })

        IconButton(AppIcons.link, S.copyEpisodeLink, onCopyLink)
        if (episode.hasVideo) IconButton(AppIcons.download, S.copyMp4Link, onCopyVideo)

        if (available.size > 1) QualityPicker(quality, available.keys.toList(), onQuality)
    }
}

/** Which rendition is playing, and the others on offer. */
@Composable
private fun QualityPicker(current: String?, labels: List<String>, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { label ->
            ge.dakalebi.web.ui.Chip(
                label = label,
                selected = label == current,
                done = false,
                onClick = { onPick(label) },
            )
        }
    }
}

/** A row of episodes that wraps rather than scrolls: there is nothing here to scroll *to*. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeStrip(title: String, episodes: List<Episode>) {
    if (episodes.isEmpty()) return
    val catalog = catalog()
    val router = router()

    SectionHead(title)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Tokens.railGap),
        verticalArrangement = Arrangement.spacedBy(Tokens.railGap),
    ) {
        episodes.forEach { episode ->
            EpisodeTile(episode, catalog.progress[episode.id]) {
                router.go(Route.Watch(episode.id))
            }
        }
    }
}
