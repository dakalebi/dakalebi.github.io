package ge.dakalebi.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import ge.dakalebi.ui.Thumb
import ge.dakalebi.ui.player.PlayerEvents
import ge.dakalebi.ui.tv.input.TvInput
import ge.dakalebi.ui.tv.player.TvVideoPlayer
import ge.dakalebi.ui.watch.SAVE_EVERY
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLVideoElement
import kotlin.math.floor

/**
 * The one input stack, reachable from the player.
 *
 * A `CompositionLocal` rather than a parameter threaded through three screens,
 * and TV-only so it stays out of `di/Locals.kt` — which is shared code that a
 * future Android app will read, and it has no input stack of this kind.
 */
internal val LocalTvInput = staticCompositionLocalOf<TvInput> {
    error("LocalTvInput was read outside TvApp")
}

/** Non-Compose holders, written from media events several times a second. */
private class TvWatchRefs {
    var video: HTMLVideoElement? = null
    var lastSaved: Double = 0.0
    var resumeApplied: Boolean = false
    var navigated: Boolean = false
    var retried: Boolean = false
}

/**
 * Playback, and the rules about what a position means.
 *
 * Deliberately not built on the web watch screen's controller. That code is a
 * state machine for a WebKit bug: iOS Safari silently ignores a seek issued before
 * the element is seekable, so it retries across four media events with a backoff
 * and gives up after eight attempts. Chromium in an Android TV WebView has no such
 * behaviour — a seek at `loadedmetadata` lands — so reusing it here would import an
 * eight-attempt loop, an AirPlay wall-clock extrapolation and a pile of
 * `isAppleMobile` branches to work around a problem this platform does not have.
 *
 * What *is* shared is everything that carries a rule rather than a workaround: the
 * [SAVE_EVERY] cadence, and the watched threshold and never-rewind guard inside
 * `SaveProgress`, which the store calls and which has its own tests.
 */
@Composable
fun TvWatchScreen(episodeId: String) {
    val catalog = catalog()
    val session = session()
    val router = router()
    val settings = settings()
    val prefs = preferences()
    val toasts = toasts()
    val input = LocalTvInput.current
    val resolveVideo = LocalResolveEpisodeVideo.current
    val scope = rememberCoroutineScope()

    val refs = remember(episodeId) { TvWatchRefs() }
    var error by remember(episodeId) { mutableStateOf<String?>(null) }

    /*
     * What the live resolve produced, if it has answered yet — and only that.
     *
     * Everything the player needs is derived below, so playback starts from the URL
     * already in the catalog instead of waiting on a round trip to Formula. The web
     * screen sets these *only* from its effect, which costs it a "loading" frame on
     * every episode even though it is holding a perfectly good stored URL.
     */
    var resolvedUrl by remember(episodeId) { mutableStateOf<String?>(null) }
    var resolvedSources by remember(episodeId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pickedQuality by remember(episodeId) { mutableStateOf<String?>(null) }

    val uid = session.uid
    val episode = catalog.byId(episodeId)
    val entry = episode?.let { catalog.progress[it.id] }
    val savedSeconds = entry?.progressSeconds ?: 0
    val progressReady = !catalog.loading
    val nextEpisode = episode?.let { catalog.next(it) }

    // The rails below the player chrome. `upcoming` and `previous` already exist on the
    // store, so these are lookups, not new logic. See [TvConfig.RAIL_COUNT] for the count.
    val upcoming = episode?.let { catalog.upcoming(it, TvConfig.RAIL_COUNT) } ?: emptyList()
    val earlier = episode?.let { catalog.previous(it, TvConfig.RAIL_COUNT) } ?: emptyList()

    // One map for everything downstream, so the label and the menu cannot disagree
    // about which rendition is best — the defect that shipped on the web once.
    val sources = resolvedSources.ifEmpty { episode?.sources ?: emptyMap() }
    val quality = pickedQuality
        ?: prefs.preferredQuality?.takeIf { sources.containsKey(it) }
        ?: orderedQualityLabels(sources).firstOrNull()
    val videoUrl = resolvedUrl ?: quality?.let { sources[it] } ?: episode?.videoUrl

    LaunchedEffect(episode?.id) {
        val current = episode ?: return@LaunchedEffect
        error = null
        val resolved = resolveVideo(current)
        if (resolved !== current) catalog.putEpisode(resolved)
        resolvedSources = resolved.sources.ifEmpty { current.sources }
        val preferred = prefs.preferredQuality?.takeIf { resolvedSources.containsKey(it) }
        resolvedUrl = preferred?.let { resolvedSources[it] }
            ?: resolved.videoUrl
            ?: orderedQualityLabels(resolvedSources).firstOrNull()?.let { resolvedSources[it] }
        if (resolvedUrl == null && current.videoUrl == null) error = S.videoLinkFailed
    }

    fun persist(isWatched: Boolean? = null) {
        val video = refs.video ?: return
        val current = episode ?: return
        val id = uid ?: return
        val seconds = floor(video.currentTime).toInt()
        if (seconds < 1 && isWatched == null) return
        val duration = video.duration.takeIf { it.isFinite() && it > 0 }?.let { floor(it).toInt() }
        scope.launch {
            runCatching {
                catalog.saveProgress(
                    uid = id,
                    episodeId = current.id,
                    progressSeconds = seconds,
                    durationSeconds = duration,
                    isWatched = isWatched,
                )
            }.onFailure { Log.e("progress", "save failed for ${current.id} at ${seconds}s", it) }
        }
    }

    // The saves the web screen makes on `visibilitychange` and `beforeunload`. A
    // television does not background a tab, but it does get switched off at the
    // wall, and leaving the screen is the moment that matters.
    DisposableEffect(episode?.id, uid) {
        onDispose { persist() }
    }

    fun goToNext() {
        val next = nextEpisode ?: return
        if (refs.navigated) return
        refs.navigated = true
        persist()
        router.go(Route.Watch(next.id))
    }

    if (episode == null) {
        Div({ classes("tv-note") }) {
            Text(if (catalog.loading) S.loading else S.episodeNotFound)
        }
        return
    }

    val events = PlayerEvents(
        onElement = { refs.video = it },
        onLoadedMetadata = { video ->
            // The whole of resume, on a platform whose seeks land. Clamped short of
            // the end so resuming a nearly-finished episode does not immediately
            // fire `ended` and roll into the next one.
            if (!refs.resumeApplied && progressReady) {
                refs.resumeApplied = true
                val duration = video.duration
                if (duration.isFinite() && duration > 0 && savedSeconds > 1) {
                    val target = minOf(savedSeconds.toDouble(), duration - 5.0).coerceAtLeast(0.0)
                    runCatching { video.currentTime = target }
                        .onFailure { Log.w("resume", "seek to ${target}s rejected", it) }
                    refs.lastSaved = target
                }
            }
        },
        onTimeUpdate = { video ->
            val time = video.currentTime
            if (time - refs.lastSaved >= SAVE_EVERY) {
                refs.lastSaved = time
                persist()
            }
        },
        onPause = { persist() },
        onEnded = {
            persist(isWatched = true)
            if (settings.autoplayNext && nextEpisode != null) goToNext()
        },
        onError = {
            Log.e("player", "media error for ${episode.id} (src=$videoUrl)")
            if (!refs.retried) {
                refs.retried = true
                scope.launch {
                    val resolved = resolveVideo(episode)
                    catalog.putEpisode(resolved)
                    resolvedUrl = resolved.videoUrl
                    if (resolved.videoUrl == null) error = S.videoLoadFailed
                }
            } else {
                error = S.videoLoadFailed
            }
        },
    )

    val url = videoUrl
    if (url != null && progressReady) {
        // Keyed so moving to the next episode builds a new element rather than
        // reusing one that still holds the previous file's state.
        key(episode.id) {
            TvVideoPlayer(
                src = url,
                autoPlay = true,
                sources = sources,
                quality = quality,
                onQualitySelected = { label ->
                    pickedQuality = label
                    prefs.setPreferredQuality(label)
                    sources[label]?.let { resolvedUrl = it }
                },
                input = input,
                events = events,
                title = S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps,
                subtitle = S.seriesTitle,
                // The same account-synced setting the drawer shows, flipped from the
                // control row so it is reachable mid-episode.
                autoplayNext = settings.autoplayNext,
                onToggleAutoplay = {
                    settings.setAutoplayNext(scope, !settings.autoplayNext) {
                        toasts.error(S.settingNotSynced)
                    }
                },
                chromeExtra = {
                    if (upcoming.isNotEmpty() || earlier.isNotEmpty()) {
                        Div({ classes("tv-player-rails") }) {
                            // The head of the next rail is the true next episode, so a
                            // fresh Down lands there rather than mid-row.
                            TvRail(
                                key = "player-next",
                                title = S.nextEpisodes.caps,
                                episodes = upcoming,
                                progress = catalog.progress,
                                entryEpisodeId = upcoming.firstOrNull()?.id,
                            )
                            // The tail of the previous rail is the episode just before
                            // this one, which is the one worth landing on.
                            TvRail(
                                key = "player-prev",
                                title = S.previousEpisodes.caps,
                                episodes = earlier,
                                progress = catalog.progress,
                                entryEpisodeId = earlier.lastOrNull()?.id,
                            )
                        }
                    }
                },
                upNext = nextEpisode?.let { next -> @Composable { TvUpNextCard(next) } },
            )
        }
    } else {
        Div({ classes("tv-note") }) { Text(error ?: S.loading) }
    }
}

/**
 * The body of the up-next overlay: the next episode's still and its number.
 *
 * Passed to the player as a slot so the player itself never learns what an episode is.
 * A plain card, not a link or a focus stop — the player decides when it shows and never
 * focuses it, so OK keeps meaning play/pause. Playing it manually is the up-next rail's
 * job.
 */
@Composable
private fun TvUpNextCard(episode: Episode) {
    Div({ classes("tv-upnext-card") }) {
        Div({ classes("tv-upnext-th") }) { Thumb(episode, showLabel = false) }
        Div({ classes("tv-upnext-b") }) {
            Div({ classes("tv-upnext-eyebrow") }) { Text(S.nextEpisode.caps) }
            Div({ classes("tv-upnext-t") }) {
                Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
            }
        }
    }
}
