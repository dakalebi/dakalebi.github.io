package ge.dakalebi.ui.spike

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.di.catalog
import ge.dakalebi.di.collectState
import ge.dakalebi.di.rememberPlaybackViewModel
import ge.dakalebi.di.sessionState
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.service.COMPACT_SCREEN_MAX_QUALITY
import ge.dakalebi.ui.LoginScreen
import ge.dakalebi.ui.player.NativeVideoPlayer
import ge.dakalebi.ui.player.PlayerEvents
import ge.dakalebi.ui.player.isAppleMobile
import ge.dakalebi.ui.player.isCompactScreen
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.url.URL
import org.w3c.dom.url.URLSearchParams
import kotlin.math.floor

/*
 * Redesign phase 0 spike (concept 3, "Library and mini-player"). THROWAWAY: it lives on a
 * draft PR for an iPhone test and is never merged.
 *
 * The question: can one <video> element move between a full now-playing sheet and a small
 * mini-player above a tab bar, on iPhone Safari, without reloading or restarting, while
 * picture-in-picture, the lock screen and AirPlay keep working?
 *
 * How: the video is composed once per episode, always at the same place in the tree. Only
 * its container's inline style changes between "sheet" and "mini", so Compose keeps the
 * same DOM node. It reuses the app's real iPhone player (`NativeVideoPlayer`) and the real
 * `PlaybackViewModel` for the file, and never attaches a playhead, so it saves no progress.
 * Inline styles only, so the per-shell CSS check has no new classes to look up.
 *
 * Open with `/?ui=spike`. Signed out, it shows the normal sign-in screen (guest works).
 */

/** True on `/?ui=spike`: this page renders instead of the web app. */
val isMiniPlayerSpike: Boolean by lazy {
    runCatching { URLSearchParams(window.location.search).get("ui") }.getOrNull() == "spike"
}

/**
 * `&src=/path.mp4` plays a same-origin file instead of the episode's link. Only for a
 * local simulator run on a network that the simulator cannot reach the CDN through.
 * Same-origin paths only, so the preview page cannot be pointed at another site.
 */
private val sourceOverride: String? by lazy {
    runCatching { URLSearchParams(window.location.search).get("src") }.getOrNull()
        ?.takeIf { it.startsWith("/") && !it.startsWith("//") }
}

private const val WALNUT = "#140e0c"
private const val SURFACE = "#2a1f1a"
private const val CREAM = "#faf0e6"
private const val DIM = "#b09a88"
private const val AMBER = "#f4a93c"
private const val TAB_BAR = "56px"

private enum class Tab(val label: String) { Home("Home"), Seasons("Seasons"), Settings("Settings") }

@Composable
fun MiniPlayerSpike() {
    val session = sessionState()
    val catalog = catalog()
    val error = catalog.loadError
    when {
        session.loading -> Note("Loading...")
        session.viewer == null -> LoginScreen()
        catalog.loading -> Note("Loading episodes...")
        error != null -> Note("Catalog failed to load: ${error.message}")
        else -> {
            val episodes = catalog.episodes.filter { it.hasVideo }
            if (episodes.isEmpty()) Note("No playable episodes.") else SpikeScreen(episodes)
        }
    }
}

@Composable
private fun SpikeScreen(episodes: List<Episode>) {
    var tab by remember { mutableStateOf(Tab.Home) }
    var current by remember { mutableStateOf<Episode?>(null) }
    var expanded by remember { mutableStateOf(false) }

    Div({
        style {
            property("min-height", "100dvh")
            property("background", WALNUT)
            property("color", CREAM)
            property("font-family", "system-ui, sans-serif")
            property("padding", "96px 16px calc($TAB_BAR + 96px + env(safe-area-inset-bottom)) 16px")
            property("box-sizing", "border-box")
        }
    }) {
        Div({ style { property("font-size", "13px"); property("color", DIM); property("margin-bottom", "12px") } }) {
            Text(
                "1. Pick an episode and press play. 2. Collapse with the arrow: it should keep playing below. " +
                    "3. Switch tabs and scroll. 4. Tap the mini-player to open it again. 5. Try PiP, AirPlay, " +
                    "fullscreen and locking the phone. The counters at the top must stay at " +
                    "elements 1, reloads 0, resets 0, restarts 0. Files may go to 2 once if the " +
                    "link expired and the app fetched a fresh one.",
            )
        }
        when (tab) {
            Tab.Home -> episodes.take(40).forEach { episode ->
                EpisodeRow(episode, playing = episode.id == current?.id) {
                    current = episode
                    expanded = true
                }
            }
            Tab.Seasons -> Note("Seasons tab (placeholder). Switch here while the mini-player plays.")
            Tab.Settings -> Note("Settings tab (placeholder).")
        }
    }

    val playing = current
    if (playing != null) {
        key(playing.id) {
            PlayerLayer(
                episode = playing,
                expanded = expanded,
                onExpand = { expanded = true },
                onCollapse = { expanded = false },
            )
        }
    }

    TabBar(tab) { tab = it }
}

/**
 * The one video element, plus the sheet and mini-player chrome around it.
 *
 * Every child is always composed and only shown or hidden by style, so toggling between
 * sheet and mini never adds or removes a node above or beside the video.
 */
@Composable
private fun PlayerLayer(
    episode: Episode,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
) {
    val playback = rememberPlaybackViewModel(
        episodeId = episode.id,
        maxQuality = COMPACT_SCREEN_MAX_QUALITY.takeIf { isCompactScreen },
        appleMobile = isAppleMobile,
    )
    val ui by playback.collectState()
    LaunchedEffect(episode.id) { playback.resolve(episode) }

    val stats = remember { SpikeStats() }
    val holder = remember { arrayOfNulls<HTMLVideoElement>(1) }
    val events = remember {
        PlayerEvents(
            onElement = { element ->
                holder[0] = element
                if (element != null) stats.watch(element)
            },
            onTimeUpdate = { stats.onTime(it) },
            onPlay = { stats.paused = false },
            onPause = { stats.paused = true },
            onEnded = { stats.lastEvent = "ended" },
            // Same as the watch screen: one retry with a fresh link, then an error.
            onError = { holder[0]?.let { playback.onMediaError(stats.onError(it)) } },
            onAirPlayChange = { active, _ -> stats.airplay = active },
        )
    }
    val expand = {
        stats.toggles++
        onExpand()
    }
    val collapse = {
        stats.toggles++
        onCollapse()
    }

    Diagnostics(stats, ui.error?.name)

    Div({
        style {
            property("position", "fixed")
            property("z-index", "20")
            property("box-sizing", "border-box")
            property("display", "flex")
            if (expanded) {
                property("inset", "0")
                property("flex-direction", "column")
                property("background", WALNUT)
                property("padding", "calc(64px + env(safe-area-inset-top)) 0 env(safe-area-inset-bottom) 0")
            } else {
                property("left", "8px")
                property("right", "8px")
                property("bottom", "calc($TAB_BAR + 8px + env(safe-area-inset-bottom))")
                property("height", "72px")
                property("flex-direction", "row")
                property("align-items", "center")
                property("gap", "12px")
                property("padding", "0 8px")
                property("background", SURFACE)
                property("border-radius", "12px")
                property("box-shadow", "0 8px 24px rgba(0,0,0,.5)")
            }
        }
    }) {
        // Sheet header: only visible while expanded.
        Div({
            style {
                property("display", if (expanded) "flex" else "none")
                property("align-items", "center")
                property("gap", "12px")
                property("padding", "0 16px 12px 16px")
            }
        }) {
            SpikeButton("Collapse", label = "▾") { collapse() }
            Div({ style { property("font-weight", "600") } }) { Text(episodeLabel(episode)) }
        }

        // The video box. Same node in both modes; only its size changes.
        Div({
            style {
                property("position", "relative")
                property("flex", "none")
                property("transition", "width 320ms ease, height 320ms ease")
                if (expanded) {
                    property("width", "100%")
                    property("height", "56.25vw")
                } else {
                    property("width", "96px")
                    property("height", "54px")
                    property("border-radius", "8px")
                    property("overflow", "hidden")
                }
            }
        }) {
            val url = sourceOverride ?: ui.videoUrl
            if (url != null) {
                NativeVideoPlayer(src = url, autoPlay = false, events = events)
            } else {
                Div({ style { property("padding", "8px"); property("font-size", "12px") } }) {
                    Text(if (ui.resolving) "Resolving..." else "No file")
                }
            }
            // In mini mode a tap anywhere on the picture opens the sheet instead of
            // reaching the native controls, which are unusable at this size.
            Div({
                style {
                    property("position", "absolute")
                    property("inset", "0")
                    property("z-index", "2")
                    property("display", if (expanded) "none" else "block")
                }
                onClick { expand() }
            })
        }

        // Mini-player info and buttons: only visible while collapsed.
        Div({
            style {
                property("display", if (expanded) "none" else "flex")
                property("flex", "1")
                property("min-width", "0")
                property("align-items", "center")
                property("gap", "8px")
            }
        }) {
            Div({
                style {
                    property("flex", "1")
                    property("min-width", "0")
                    property("white-space", "nowrap")
                    property("overflow", "hidden")
                    property("text-overflow", "ellipsis")
                    property("font-size", "14px")
                }
                onClick { expand() }
            }) { Text(episodeLabel(episode)) }
            SpikeButton(if (stats.paused) "Play" else "Pause", label = if (stats.paused) "▶" else "❚❚") {
                val video = holder[0] ?: return@SpikeButton
                if (video.paused) {
                    video.play()
                } else {
                    video.pause()
                }
            }
            SpikeButton("Open", label = "▴") { expand() }
        }

        // Sheet body: only visible while expanded.
        Div({
            style {
                property("display", if (expanded) "block" else "none")
                property("padding", "16px")
                property("color", DIM)
                property("font-size", "13px")
            }
        }) {
            Text("Queue, chips and transport go here in the real design. Collapse, switch tabs, then come back.")
        }
    }
}

/** Always on top, in both modes, so the counters can be read while testing. */
@Composable
private fun Diagnostics(stats: SpikeStats, error: String?) {
    val ok = stats.elements <= 1 && stats.reloads == 0 && stats.resets == 0 && stats.restarts == 0
    val verdict = when {
        !ok -> "FAIL"
        stats.elements == 0 -> "No video yet"
        else -> "OK so far"
    }
    Div({
        style {
            property("position", "fixed")
            property("top", "0")
            property("left", "0")
            property("right", "0")
            property("z-index", "30")
            property("padding", "calc(6px + env(safe-area-inset-top)) 12px 6px 12px")
            property("background", "rgba(0,0,0,.85)")
            property("color", if (ok) "#9fd49a" else "#ff8a7a")
            property("font", "11px/1.4 ui-monospace, Menlo, monospace")
        }
    }) {
        Text(
            "$verdict | elements ${stats.elements} | files ${stats.files} | reloads ${stats.reloads} | " +
                "resets ${stats.resets} | restarts ${stats.restarts} | waiting ${stats.waits} | " +
                "toggles ${stats.toggles} | t ${floor(stats.time).toInt()}s ${if (stats.paused) "paused" else "playing"} | " +
                "mode ${stats.mode} | airplay ${if (stats.airplay) "on" else "off"} | host ${stats.host} | " +
                "last ${stats.lastEvent}" + (error?.let { " | app error $it" } ?: ""),
        )
    }
}

/**
 * Counts what would show a reload or a restart. Written from media events.
 *
 * A new file is fine: the first load, and the one retry the ViewModel makes with a fresh
 * link when the old one expired. Loading or emptying the same file again is the failure
 * this spike looks for, and so is a second element.
 */
private class SpikeStats {
    var elements by mutableStateOf(0)
    var files by mutableStateOf(0)
    var reloads by mutableStateOf(0)
    var resets by mutableStateOf(0)
    var restarts by mutableStateOf(0)
    var waits by mutableStateOf(0)
    var toggles by mutableStateOf(0)
    var time by mutableStateOf(0.0)
    var paused by mutableStateOf(true)
    var mode by mutableStateOf("inline")
    var airplay by mutableStateOf(false)
    var host by mutableStateOf("-")
    var lastEvent by mutableStateOf("-")

    private var lastTime = 0.0
    private var seeking = false
    private var loadedSrc: String? = null

    fun watch(video: HTMLVideoElement) {
        elements++
        lastTime = 0.0
        Log.d("spike", "video element created (#$elements)")
        video.addEventListener("loadstart", {
            val src = video.src
            if (src == loadedSrc) {
                reloads++
                note("reload of the same file")
            } else {
                files++
                loadedSrc = src
                lastTime = 0.0
                host = runCatching { URL(src).host }.getOrDefault("?")
                note("loadstart, file $files")
            }
        })
        video.addEventListener("emptied", {
            if (video.src == loadedSrc) {
                resets++
                note("emptied, same file")
            } else {
                note("emptied for a new file")
            }
        })
        video.addEventListener("waiting", { note("waiting"); waits++ })
        video.addEventListener("seeking", { seeking = true })
        video.addEventListener("seeked", {
            seeking = false
            lastTime = video.currentTime
        })
        video.addEventListener("webkitpresentationmodechanged", {
            mode = (video.asDynamic().webkitPresentationMode as? String) ?: "?"
            note("mode $mode")
        })
        video.addEventListener("enterpictureinpicture", { mode = "picture-in-picture"; note("pip on") })
        video.addEventListener("leavepictureinpicture", { mode = "inline"; note("pip off") })
        video.addEventListener("webkitbeginfullscreen", { mode = "fullscreen"; note("fullscreen on") })
        video.addEventListener("webkitendfullscreen", { mode = "inline"; note("fullscreen off") })
    }

    fun onTime(video: HTMLVideoElement) {
        val t = video.currentTime
        if (!seeking && t + 1.0 < lastTime) {
            restarts++
            note("jumped back from ${floor(lastTime).toInt()}s to ${floor(t).toInt()}s")
        }
        lastTime = t
        if (floor(t) != floor(time)) time = t
    }

    /** The element's error code, or null. Shown, then handed to the ViewModel's retry. */
    fun onError(video: HTMLVideoElement): Int? {
        val code = video.error?.code?.toInt()
        note("media error code $code")
        return code
    }

    private fun note(event: String) {
        lastEvent = event
        Log.d("spike", event)
    }
}

@Composable
private fun EpisodeRow(episode: Episode, playing: Boolean, onClick: () -> Unit) {
    Div({
        style {
            property("padding", "14px 12px")
            property("margin-bottom", "8px")
            property("border-radius", "12px")
            property("background", if (playing) "#3a2b23" else SURFACE)
            property("color", if (playing) AMBER else CREAM)
            property("cursor", "pointer")
            property("font-size", "15px")
        }
        onClick { onClick() }
    }) { Text(episodeLabel(episode)) }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Div({
        style {
            property("position", "fixed")
            property("left", "0")
            property("right", "0")
            property("bottom", "0")
            property("z-index", "10")
            property("height", "calc($TAB_BAR + env(safe-area-inset-bottom))")
            property("padding-bottom", "env(safe-area-inset-bottom)")
            property("box-sizing", "border-box")
            property("display", "flex")
            property("background", "#1d1512")
            property("border-top", "1px solid #423128")
        }
    }) {
        Tab.entries.forEach { tab ->
            Button({
                plainButton()
                style {
                    property("flex", "1")
                    property("color", if (tab == selected) AMBER else DIM)
                    property("font-size", "13px")
                }
                onClick { onSelect(tab) }
            }) { Text(tab.label) }
        }
    }
}

@Composable
private fun SpikeButton(name: String, label: String, onClick: () -> Unit) {
    Button({
        plainButton()
        attr("aria-label", name)
        style {
            property("width", "44px")
            property("height", "44px")
            property("flex", "none")
            property("color", CREAM)
            property("font-size", "18px")
        }
        onClick { onClick() }
    }) { Text(label) }
}

private fun AttrsScope<HTMLButtonElement>.plainButton() {
    attr("type", "button")
    style {
        property("background", "none")
        property("border", "0")
        property("padding", "0")
        property("cursor", "pointer")
    }
}

@Composable
private fun Note(text: String) {
    Div({
        style {
            property("padding", "24px 16px")
            property("color", DIM)
            property("font-family", "system-ui, sans-serif")
        }
    }) { Text(text) }
}

private fun episodeLabel(episode: Episode): String =
    "S${episode.seasonNumber} E${episode.episodeNumber}" + (episode.title?.let { " · $it" } ?: "")
