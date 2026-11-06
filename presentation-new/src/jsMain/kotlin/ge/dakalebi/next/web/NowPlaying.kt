package ge.dakalebi.next.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ge.dakalebi.core.formatTime
import ge.dakalebi.di.catalog
import ge.dakalebi.di.collectState
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.service.orderedQualityLabels
import ge.dakalebi.domain.service.vlcOpenUrl
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.Button
import ge.dakalebi.next.components.ButtonVariant
import ge.dakalebi.next.components.Chip
import ge.dakalebi.next.components.ConfirmDialog
import ge.dakalebi.next.components.EpisodeRow
import ge.dakalebi.next.components.Glyph
import ge.dakalebi.next.components.Icon
import ge.dakalebi.next.components.IconButton
import ge.dakalebi.next.components.IconButtonVariant
import ge.dakalebi.next.components.MenuButton
import ge.dakalebi.next.components.MenuEntry
import ge.dakalebi.next.components.Notice
import ge.dakalebi.next.components.Scrubber
import ge.dakalebi.next.components.Spinner
import ge.dakalebi.next.components.Thumb
import ge.dakalebi.next.components.Transport
import ge.dakalebi.next.components.episodeMeta
import ge.dakalebi.next.drive.driveHref
import ge.dakalebi.next.player.isAppleMobile
import ge.dakalebi.presentation.ErrorMessages
import ge.dakalebi.presentation.NowPlayingViewModel
import ge.dakalebi.presentation.PlaybackUiState
import ge.dakalebi.presentation.PlaybackViewModel
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** Longest wait for the spot to save before opening driving mode anyway. */
private const val DRIVE_SAVE_WAIT_MS = 2_000L

/** Where the player is drawn. The route and the screen width decide; see [AppShell]. */
internal enum class PlayerMode {
    /** A bar on the phone's tab bar: the picture, the episode, the time, play and next. */
    Mini,

    /** The phone's open player: the whole screen, over the tabs. A watch route. */
    Sheet,

    /** The desktop's panel on the right, whenever an episode is loaded. */
    Dock,
}

/**
 * The episode playing, in any of its three places: the mini-player, the phone's sheet and
 * the desktop's panel.
 *
 * One element tree for all three, so the picture keeps its place in the page: the stage
 * that holds the video is the same node in every mode, and [WebPlayer] moves the video
 * there without a reload in any case.
 *
 * On an iPhone or iPad with the native player chosen, the video has Apple's controls and
 * nothing of ours is drawn over it: they give picture in picture, the lock screen and
 * AirPlay. The transport and the chips sit below the picture, and the next-episode card is
 * in the sheet, since nothing survives over Apple's full screen.
 */
@Composable
internal fun NowPlaying(
    nowPlaying: NowPlayingViewModel,
    player: WebPlayer,
    mode: PlayerMode,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
) {
    val np by nowPlaying.state.collectAsState()
    val playback = np.playback ?: return
    val catalog = catalog()
    val settings = settings()
    val toasts = toasts()
    val ui by playback.collectState()

    val episode = nowPlaying.episode(np)
    val savedSeconds = episode?.let { catalog.progress[it.id] }?.progressSeconds ?: 0
    val native = isAppleMobile && settings[Setting.UseNativePlayer]
    val url = ui.videoUrl
    val ready = !catalog.loading

    LaunchedEffect(playback, episode?.id) {
        val current = episode ?: return@LaunchedEffect
        player.setPoster(current.thumbnailUrl)
        playback.resolve(current)
    }

    LaunchedEffect(playback, url, ready) {
        player.bind(playback)
        // The saved spot is read once the catalog has it: the first file starts there.
        if (url != null && ready) player.load(url, savedSeconds, playback.autoplay)
    }

    // A link to an episode the catalog does not have.
    LaunchedEffect(np.episodeId, ready, episode == null) {
        if (ready && episode == null) {
            toasts.error(S.episodeNotFound)
            nowPlaying.stop()
        }
    }

    // Apple's controls on the open player only. A paused mini-player would otherwise show
    // the native overlay over its tiny picture.
    LaunchedEffect(native, mode) { player.video.controls = native && mode != PlayerMode.Mini }

    val root = remember { RootRef() }
    PlayerKeys(player, root, enabled = mode != PlayerMode.Mini && !native, bodyCounts = mode == PlayerMode.Sheet)

    Section({
        classes("np")
        when (mode) {
            PlayerMode.Mini -> classes("np--mini")
            PlayerMode.Sheet -> classes("np--sheet")
            PlayerMode.Dock -> classes("np--dock")
        }
        attr("aria-label", S.nowPlaying)
        ref { element ->
            root.element = element
            onDispose { root.element = null }
        }
    }) {
        if (mode != PlayerMode.Mini) Head(mode, onCollapse, onClose)
        Stage(player, playback, ui, episode, nowPlaying, mode, native)
        if (episode != null) {
            if (mode == PlayerMode.Mini) {
                MiniInfo(nowPlaying, player, ui, episode, savedSeconds)
            } else {
                Body(nowPlaying, player, playback, ui, episode, savedSeconds, native)
            }
        }
    }
    // After the section, so its element is there to take focus.
    PlayerFocus(root, mode)
}

/** The player's root element, for the keys and the focus. */
private class RootRef {
    var element: HTMLElement? = null

    /** What had focus outside the player when it last opened, to go back to on close. */
    var opener: HTMLElement? = null
}

/**
 * The keyboard while the player is open, with the old watch page's keys: space, the
 * arrows, F and M. On the desktop's panel only while focus is in it, so the arrows still
 * scroll the page beside it. On the phone's sheet also when nothing is focused, since the
 * sheet is all there is.
 */
@Composable
private fun PlayerKeys(player: WebPlayer, root: RootRef, enabled: Boolean, bodyCounts: Boolean) {
    DisposableEffect(enabled, bodyCounts) {
        if (!enabled) return@DisposableEffect onDispose {}
        val listener: (Event) -> Unit = listener@{ event ->
            val key = event as? KeyboardEvent ?: return@listener
            if (key.defaultPrevented) return@listener
            val target = key.target as? Node
            val inside = target != null && root.element?.contains(target) == true
            val onBody = target == null || target === document.body || target === document.documentElement
            if (inside || (bodyCounts && onBody)) {
                if (player.onKey(key)) key.preventDefault()
            }
        }
        window.addEventListener("keydown", listener)
        onDispose { window.removeEventListener("keydown", listener) }
    }
}

/**
 * Focus follows the player, so its keys work as soon as it opens and the keyboard is not
 * left on the page's body when it goes.
 *
 * - **Opening.** The sheet takes focus to its collapse button, its first control. The
 *   panel takes it to play and pause: it opens beside the page, and its other controls
 *   are many Tabs away.
 * - **Shrinking** the sheet to the mini-player puts focus on the mini-player, which opens
 *   it again.
 * - **Closing** puts focus back on what opened the player, or, when that has left the
 *   page, on the current tab's link.
 *
 * Focus moves back only when it was lost with the player's elements; a viewer who has
 * moved on keeps their place. The sheet also covers the app, so the page under it does
 * not scroll.
 */
@Composable
private fun PlayerFocus(root: RootRef, mode: PlayerMode) {
    DisposableEffect(mode) {
        val active = document.activeElement as? HTMLElement
        if (active != null && active !== document.body && root.element?.contains(active) != true) {
            root.opener = active
        }
        if (mode == PlayerMode.Mini) return@DisposableEffect onDispose {}

        val sheet = mode == PlayerMode.Sheet
        val html = document.documentElement
        if (sheet) html?.classList?.add("np-sheet-open")
        val first = if (sheet) {
            root.element?.querySelector(".np-head .icon-button")
        } else {
            root.element?.querySelector(".transport-primary") ?: root.element?.querySelector(".np-stage[tabindex]")
        }
        (first as? HTMLElement)?.focus()

        onDispose {
            if (sheet) html?.classList?.remove("np-sheet-open")
            if (focusLost()) {
                // Still on the page: the sheet shrank to the mini-player.
                (root.element?.takeIf { it.isConnected }?.querySelector(".np-mini-open") as? HTMLElement)?.focus()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (focusLost()) (root.opener?.takeIf { it.isConnected } ?: currentTabLink())?.focus()
        }
    }
}

/** Whether focus went with a removed element and fell back to the page's body. */
private fun focusLost(): Boolean = document.activeElement.let { it == null || it === document.body }

/** The side menu's or the tab bar's link to the current tab, whichever is drawn. */
private fun currentTabLink(): HTMLElement? =
    document.querySelectorAll(".sidenav-item[aria-current=\"page\"], .tabbar-tab[aria-current=\"page\"]")
        .asList()
        .filterIsInstance<HTMLElement>()
        .firstOrNull { it.getClientRects().length > 0 }

/** The sheet's and the panel's top: collapse (phone only), the title, and close. */
@Composable
private fun Head(mode: PlayerMode, onCollapse: () -> Unit, onClose: () -> Unit) {
    Div({ classes("np-head") }) {
        if (mode == PlayerMode.Sheet) {
            IconButton(Glyph.ChevronDown, S.collapsePlayer, onClick = onCollapse)
        }
        H2({ classes("np-head-title") }) { Text(S.nowPlaying.caps) }
        IconButton(Glyph.X, S.closePlayer, onClick = onClose)
    }
}

/**
 * The picture, and what is drawn over it: the spinner, what a key did, and on the web
 * player the controls, which hide while it plays untouched. In full screen the stage is
 * all there is, so it carries the transport and the next-episode card too.
 */
@Composable
private fun Stage(
    player: WebPlayer,
    playback: PlaybackViewModel,
    ui: PlaybackUiState,
    episode: Episode?,
    nowPlaying: NowPlayingViewModel,
    mode: PlayerMode,
    native: Boolean,
) {
    val catalog = catalog()
    val toasts = toasts()
    val overlay = mode != PlayerMode.Mini && !native
    val waiting = ui.error == null && (ui.videoUrl == null || player.buffering)
    var lastPointer by remember { mutableStateOf("mouse") }

    Div({
        classes("np-stage")
        if (overlay && !player.controlsShown) classes("np-stage--idle")
        // Focusable, so a click on the picture puts the keys on the player.
        if (overlay) attr("tabindex", "-1")
        ref { element ->
            player.stageIs(element)
            onDispose { player.stageIs(null) }
        }
        if (overlay) {
            // A moving mouse or pen shows the controls; a finger does not. A tap also sends
            // a `mousemove` for old pages, before its click, and that one showing the
            // controls would make the click below pause instead of only showing them.
            addEventListener("pointermove") { event ->
                if (event.nativeEvent.asDynamic().pointerType != "touch") player.revealControls()
            }
            onMouseLeave { player.hideControlsNow() }
        }
    }) {
        Div({
            classes("np-picture")
            ref { slot ->
                player.attachTo(slot)
                onDispose {}
            }
            if (overlay) {
                addEventListener("pointerdown") { event ->
                    lastPointer = (event.nativeEvent.asDynamic().pointerType as? String) ?: "mouse"
                }
                onClick { event ->
                    val count = event.nativeEvent.asDynamic().detail as? Int ?: 1
                    player.clickPicture(count, touch = lastPointer == "touch")
                }
                onDoubleClick { player.doubleClickPicture() }
            }
        })

        if (mode != PlayerMode.Mini) {
            if (waiting) Div({ classes("np-center") }) { Spinner(onMedia = true) }
            ui.error?.let { error ->
                Div({ classes("np-center") }) { Icon(Glyph.CircleAlert, label = ErrorMessages.playback(error)) }
            }
            player.feedback?.let { message ->
                Div({ classes("np-feedback"); attr("aria-hidden", "true") }) { Text(message) }
            }
        }

        if (overlay) {
            Div({ classes("np-controls") }) {
                Div({ classes("np-tools") }) {
                    val sources = ui.sources.ifEmpty { episode?.sources.orEmpty() }
                    val labels = orderedQualityLabels(sources)
                    if (labels.size > 1) {
                        MenuButton(
                            label = S.quality,
                            glyph = Glyph.Settings,
                            value = ui.quality,
                            variant = IconButtonVariant.OnMedia,
                            entries = labels.map { label ->
                                MenuEntry(
                                    label = label,
                                    checked = label == ui.quality,
                                    onSelect = {
                                        if (label != ui.quality) {
                                            player.holdPositionForQualitySwap()
                                            playback.selectQuality(label) { toasts.error(S.settingNotSynced) }
                                        }
                                    },
                                )
                            },
                        )
                    }
                    if (player.castAvailable) {
                        IconButton(Glyph.Cast, S.cast, player::startCast, IconButtonVariant.OnMedia, pressed = player.casting)
                    }
                    if (player.pipSupported) {
                        IconButton(
                            Glyph.PictureInPicture,
                            S.pictureInPicture,
                            player::togglePip,
                            IconButtonVariant.OnMedia,
                            pressed = player.pip,
                        )
                    }
                    IconButton(
                        if (player.muted) Glyph.VolumeX else Glyph.Volume2,
                        if (player.muted) S.unmute else S.mute,
                        player::toggleMute,
                        IconButtonVariant.OnMedia,
                    )
                    IconButton(
                        if (player.fullscreen) Glyph.Minimize else Glyph.Maximize,
                        S.fullscreen,
                        player::toggleFullscreen,
                        IconButtonVariant.OnMedia,
                        pressed = player.fullscreen,
                    )
                }

                if (player.fullscreen && episode != null) {
                    Div({ classes("np-fsbar") }) {
                        Scrubber(
                            position = shownPosition(ui, player),
                            duration = ui.duration,
                            onSeek = player::seek,
                            onMedia = true,
                        )
                        PlayerTransport(nowPlaying, player, episode, compact = true)
                    }
                }
            }

            val next = episode?.let { catalog.next(it) }
            if (player.fullscreen && next != null && (ui.promptDue || ui.ended)) {
                Div({ classes("np-upnext-over") }) {
                    UpNext(next, ended = ui.ended, onPlay = { nowPlaying.skipToNext() }, onDismiss = playback::dismissPrompt)
                }
            }
        }
    }
}

/** The mini-player's text and buttons. A tap anywhere else on it opens the player. */
@Composable
private fun MiniInfo(
    nowPlaying: NowPlayingViewModel,
    player: WebPlayer,
    ui: PlaybackUiState,
    episode: Episode,
    savedSeconds: Int,
) {
    val catalog = catalog()
    val title = S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber)
    val position = (ui.position ?: savedSeconds).toDouble()
    val duration = ui.duration ?: catalog.progress[episode.id]?.durationSeconds?.toDouble()

    A(Router.href(Route.Watch(episode.id)), {
        classes("np-mini-open")
        attr("aria-label", "${S.nowPlaying}: $title")
    }) {
        // The episode on top and the season under it, as on the episode tiles: the whole
        // "season · episode" in caps does not fit a phone's bar beside two buttons.
        Span({ classes("np-mini-title") }) { Text(S.episode(episode.episodeNumber)) }
        Span({ classes("np-mini-time") }) {
            val time = if (duration != null) "${formatTime(position)} / ${formatTime(duration)}" else formatTime(position)
            Text("${S.season(episode.seasonNumber)} · $time")
        }
    }
    IconButton(
        if (player.playing) Glyph.Pause else Glyph.Play,
        if (player.playing) S.pause else S.play,
        onClick = player::togglePlay,
        attrs = { classes("np-mini-button") },
    )
    IconButton(
        Glyph.SkipForward,
        S.nextEpisodeAction,
        onClick = { nowPlaying.skipToNext() },
        disabled = catalog.next(episode) == null,
        attrs = { classes("np-mini-button") },
    )
    val share = if (duration != null && duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0
    Div({ classes("np-mini-progress"); attr("aria-hidden", "true") }) {
        Div({
            classes("np-mini-progress-fill")
            style { property("width", "${share * 100}%") }
        })
    }
}

/** Below the picture: the episode, the scrubber, the transport, its actions and the queue. */
@Composable
private fun Body(
    nowPlaying: NowPlayingViewModel,
    player: WebPlayer,
    playback: PlaybackViewModel,
    ui: PlaybackUiState,
    episode: Episode,
    savedSeconds: Int,
    native: Boolean,
) {
    val catalog = catalog()
    val toasts = toasts()
    val scope = rememberCoroutineScope()
    var confirmReset by remember(episode.id) { mutableStateOf(false) }
    val entry = catalog.progress[episode.id]
    val next = catalog.next(episode)
    val error = ui.error?.let { ErrorMessages.playback(it) }

    Div({ classes("np-body") }) {
        Div({ classes("np-heading") }) {
            H2({ classes("np-title") }) {
                Text(S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber).caps)
            }
            val meta = episodeMeta(episode, entry)
            if (meta.isNotEmpty()) P({ classes("np-meta") }) { Text(meta) }
        }

        // Apple's controls carry their own timeline.
        if (!native) {
            Scrubber(position = shownPosition(ui, player, savedSeconds), duration = ui.duration, onSeek = player::seek)
        }
        PlayerTransport(nowPlaying, player, episode, compact = false)

        // An episode that will not play here but is on Cavea Plus says where it is, the
        // way an iPhone is pointed to VLC: one link that opens Cavea Plus's own page.
        if (error != null) {
            val caveaUrl = ui.caveaUrl.takeIf { ui.offersCavea }
            Notice(
                title = error,
                actionLabel = caveaUrl?.let { S.openOnCavea },
                actionHref = caveaUrl,
                actionExternal = true,
                actionIcon = Glyph.ExternalLink,
            )
        }

        if (next != null && (ui.ended || ui.promptDue)) {
            UpNext(
                next,
                ended = ui.ended,
                onPlay = { nowPlaying.skipToNext() },
                onDismiss = playback::dismissPrompt,
            )
        }

        Div({ classes("np-chips") }) {
            Chip(S.watchFromStart, icon = Glyph.SkipBack, onClick = {
                player.restart()
                playback.watchFromStart()
            })
            if (entry?.isWatched == true) {
                Chip(S.watchedLabel, icon = Glyph.Check, onClick = { confirmReset = true }, attrs = { classes("chip--done") })
            } else {
                Chip(S.markAsWatched, icon = Glyph.Check, onClick = {
                    playback.markWatched()
                    toasts.ok(S.episodeMarkedWatched)
                })
            }

            // Every episode on an iPhone or iPad, not only after an error: VLC brings its own
            // decoder, so it also plays what Safari cannot (all of season 2). Not on a
            // computer, where VLC registers no link scheme. The label says where VLC starts.
            val videoUrl = ui.videoUrl
            if (isAppleMobile && videoUrl != null) {
                val start = ui.position ?: savedSeconds
                Chip(
                    label = if (start >= 1) "${S.openInVlc} · ${formatTime(start.toDouble())}" else S.openInVlc,
                    icon = Glyph.ExternalLink,
                    href = vlcOpenUrl(
                        current = episode,
                        currentUrl = videoUrl,
                        // VLC plays its queue through, which is autoplay, and the web has none.
                        upcoming = emptyList(),
                        quality = ui.quality,
                        startSeconds = start,
                        title = { S.seasonAndEpisode(it.seasonNumber, it.episodeNumber) },
                    ),
                )
            }

            // Another page that reads the saved spot, so the spot is saved first: a save
            // started while the page unloads may never reach the server.
            val driveLink = driveHref(Route.Watch(episode.id))
            Chip(S.driveMode, href = driveLink, attrs = {
                onClick { event ->
                    // A modified click opens a new tab, and this page keeps playing.
                    if (event.metaKey || event.ctrlKey || event.shiftKey) return@onClick
                    event.preventDefault()
                    player.video.pause()
                    val save = playback.save()
                    scope.launch {
                        withTimeoutOrNull(DRIVE_SAVE_WAIT_MS) { save?.join() }
                        window.location.href = driveLink
                    }
                }
            })
            // An episode only Cavea Plus has has no Formula page.
            episode.episodePageUrl?.let { page ->
                Chip(S.openOnFormula, icon = Glyph.ExternalLink, href = page, external = true)
            }
        }

        Queue(nowPlaying, episode)
    }

    if (confirmReset) {
        ConfirmDialog(
            title = S.clearProgressTitle,
            body = S.clearProgressBody,
            confirmLabel = S.delete,
            onConfirm = {
                confirmReset = false
                scope.launch {
                    if (playback.clearProgress()) {
                        player.seek(0.0)
                        toasts.ok(S.progressCleared)
                    } else {
                        toasts.error(S.clearFailed)
                    }
                }
            },
            onDismiss = { confirmReset = false },
        )
    }
}

/**
 * The five buttons, wired. Previous restarts the episode once it is a few seconds in, the
 * way a music player does, and goes back one otherwise.
 */
@Composable
private fun PlayerTransport(nowPlaying: NowPlayingViewModel, player: WebPlayer, episode: Episode, compact: Boolean) {
    val catalog = catalog()
    val hasPrevious = catalog.previous(episode, 1).isNotEmpty()
    val hasNext = catalog.next(episode) != null
    Transport(
        playing = player.playing,
        onToggle = player::togglePlay,
        onBack = { player.seekBy(-SKIP_SECONDS.toDouble()) },
        onForward = { player.seekBy(SKIP_SECONDS.toDouble()) },
        onPrevious = {
            if (player.currentTime > RESTART_THRESHOLD || !hasPrevious) player.restart() else nowPlaying.skipToPrevious()
        },
        onNext = if (hasNext) ({ nowPlaying.skipToNext() }) else null,
        skipSeconds = SKIP_SECONDS,
        compact = compact,
    )
}

/**
 * The end-of-episode card: what plays next, and the button that plays it. The web has no
 * autoplay, so it waits for the tap. Before the end it can be put away.
 */
@Composable
private fun UpNext(next: Episode, ended: Boolean, onPlay: () -> Unit, onDismiss: () -> Unit) {
    val catalog = catalog()
    Div({ classes("upnext") }) {
        Thumb(next, catalog.progress[next.id]?.takeIf { it.isStarted }?.percent?.let { it / 100.0 })
        Div({ classes("upnext-text") }) {
            // "Up next" (plural in Georgian) over one episode reads wrong, and the title
            // already says "next": the eyebrow is only for the end.
            if (ended) P({ classes("upnext-eyebrow") }) { Text(S.episodeFinished.caps) }
            P({ classes("upnext-title") }) { Text(S.nextUp(next.seasonNumber, next.episodeNumber)) }
        }
        Div({ classes("upnext-actions") }) {
            Button(S.nextEpisodeAction, onClick = onPlay, variant = ButtonVariant.Primary, icon = Glyph.SkipForward)
            if (!ended) Button(S.dismiss, onClick = onDismiss, variant = ButtonVariant.Quiet)
        }
    }
}

/**
 * What plays after this episode, across seasons. A row plays its episode here rather than
 * following its link, so the player stays open; the link is still there for a new tab.
 */
@Composable
private fun Queue(nowPlaying: NowPlayingViewModel, episode: Episode) {
    val catalog = catalog()
    val np by nowPlaying.state.collectAsState()
    val queue = nowPlaying.queue(np)
    if (queue.isEmpty()) return

    Section({ classes("np-queue") }) {
        H3({ classes("section-title") }) { Text(S.nextEpisodes.caps) }
        Ul({ classes("ep-list") }) {
            queue.forEach { item ->
                Li {
                    EpisodeRow(
                        item,
                        catalog.progress[item.id],
                        href = Router.href(Route.Watch(item.id)),
                        withSeason = item.seasonNumber != episode.seasonNumber,
                        attrs = {
                            onClick { event ->
                                if (event.metaKey || event.ctrlKey || event.shiftKey) return@onClick
                                event.preventDefault()
                                nowPlaying.play(item.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Where the scrubber stands: the view model's position once the resume has landed, the
 * saved spot before that, so it does not flash 0:00 over a resume.
 */
private fun shownPosition(ui: PlaybackUiState, player: WebPlayer, savedSeconds: Int = 0): Double =
    ui.position?.toDouble() ?: if (player.playing) player.currentTime else savedSeconds.toDouble()
