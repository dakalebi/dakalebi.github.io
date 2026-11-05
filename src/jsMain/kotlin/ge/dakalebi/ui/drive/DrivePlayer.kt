package ge.dakalebi.ui.drive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.service.COMPACT_SCREEN_MAX_QUALITY
import ge.dakalebi.domain.service.defaultQualityLabel
import ge.dakalebi.domain.service.withStartFragment
import ge.dakalebi.domain.usecase.ResolveEpisodeVideo
import ge.dakalebi.i18n.S
import ge.dakalebi.presentation.CatalogStore
import ge.dakalebi.presentation.PreferencesStore
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import ge.dakalebi.presentation.SettingsStore
import ge.dakalebi.ui.assetBase
import ge.dakalebi.ui.player.isAppleMobile
import ge.dakalebi.ui.player.isCompactScreen
import ge.dakalebi.ui.watch.SAVE_EVERY
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URL
import kotlin.js.json
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** How far the big skip buttons, and the steering wheel's previous-track button, move. */
internal const val SKIP_SECONDS = 30.0

/** Resuming a nearly finished episode stops this far short, so it does not end at once. */
private const val END_MARGIN = 5.0

/** Close enough to the saved position to count as there. */
private const val SEEK_TOLERANCE = 1.0

/** A stream that never accepts the resume seek plays from where it is, rather than fighting forever. */
private const val MAX_RESUME_ATTEMPTS = 8

/** `new MediaMetadata(init)`. Only constructed after checking the browser has it. */
private external class MediaMetadata(init: dynamic)

/**
 * Driving mode's player: one native `<video>` for the whole visit, and the big controls
 * and system buttons that reach it.
 *
 * **The native player.** The element has `controls` and `playsinline`, the same as
 * [ge.dakalebi.ui.player.NativeVideoPlayer]: on an iPhone that is Apple's own player,
 * with its fullscreen, AirPlay and picture-in-picture. The big buttons on the player
 * screen drive this same element, so the two never disagree.
 *
 * **One element, never rebuilt.** iOS lets an element start without a tap only after a
 * tap has started it once. The home screen's button starts it inside its tap, before
 * the player screen exists, and every later start, autoplay into the next episode
 * included, reuses that permission. A fresh element per episode would lose it. The
 * player screen only lends the element a place on the page ([attach]/[detach]).
 *
 * **Not Compose.** The fields below are Compose state so the screen can draw them, but
 * nothing here waits for a recomposition. The `ended` handler swaps the source and calls
 * `play()` itself, and the route follows.
 *
 * The rules about what a position means are shared, not repeated: [SAVE_EVERY] for the
 * cadence, and the watched threshold and never-rewind guard inside `SaveProgress`.
 */
internal class DrivePlayer(
    private val catalog: CatalogStore,
    private val settings: SettingsStore,
    private val preferences: PreferencesStore,
    private val router: Router,
    private val resolveVideo: ResolveEpisodeVideo,
    private val uid: () -> String?,
) {
    /**
     * Lives as long as the page, like the graph's own scope. A composable's scope is
     * cancelled as the screen leaves, which is the moment the last save is made.
     */
    private val scope = MainScope()

    private val video = (document.createElement("video") as HTMLVideoElement).apply {
        controls = true
        preload = "auto"
        setAttribute("playsinline", "")
        setAttribute("x-webkit-airplay", "allow")
    }

    var episodeId: String? by mutableStateOf(null)
        private set
    var playing: Boolean by mutableStateOf(false)
        private set
    var buffering: Boolean by mutableStateOf(false)
        private set

    /** Whole seconds, so the screen redraws once a second rather than four times. */
    var position: Int by mutableStateOf(0)
        private set
    var duration: Int? by mutableStateOf(null)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set

    /** Where the episode should start, until the element is confirmed to be there. */
    private var resumeTarget: Double? = null
    private var resumeAttempts = 0
    private var lastSaved = 0.0
    private var retried = false

    /** The viewer's intent, as opposed to the element's state: a reload after a failed load keeps it. */
    private var wantsToPlay = false
    private var wakeLock: dynamic = null
    private var wakeLockPending = false

    private val current: Episode? get() = episodeId?.let { catalog.byId(it) }

    /** Read in composition, so the Next button follows the catalog. */
    val hasNext: Boolean get() = current?.let { catalog.next(it) } != null

    // ------------------------------------------------------------ lifecycle

    /** Attaches every listener. Returns the teardown. */
    fun install(): () -> Unit {
        val mediaEvents = listOf<Pair<String, (Event) -> Unit>>(
            // iOS drops a seek issued this early, so on iPhone the first attempt waits.
            "loadedmetadata" to { onDurationKnown(); if (!isAppleMobile) resume(confirming = false) },
            "loadeddata" to { resume(confirming = false) },
            "canplay" to { buffering = false; resume(confirming = false) },
            "durationchange" to { onDurationKnown() },
            "seeked" to { resume(confirming = true) },
            "playing" to { buffering = false; resume(confirming = true) },
            "waiting" to { buffering = true },
            "timeupdate" to { onTimeUpdate() },
            "play" to { onPlay() },
            "pause" to { onPause() },
            "ended" to { onEnded() },
            "error" to { onError() },
        )
        mediaEvents.forEach { (type, handler) -> video.addEventListener(type, handler) }

        // Leaving the app on a phone often never fires `beforeunload`, so this is the save
        // that matters. Coming back, the wake lock has to be asked for again: the browser
        // drops it whenever the page is hidden.
        val onVisibility: (Event) -> Unit = {
            if (document.asDynamic().visibilityState == "hidden") persist()
            else if (playing) acquireWakeLock()
        }
        val onPageHide: (Event) -> Unit = { persist() }
        document.addEventListener("visibilitychange", onVisibility)
        window.addEventListener("pagehide", onPageHide)

        installMediaSession()

        return {
            persist()
            wantsToPlay = false
            video.pause()
            mediaEvents.forEach { (type, handler) -> video.removeEventListener(type, handler) }
            document.removeEventListener("visibilitychange", onVisibility)
            window.removeEventListener("pagehide", onPageHide)
            clearMediaSession()
            releaseWakeLock()
        }
    }

    /** Shows the picture in [slot], the player screen's frame. */
    fun attach(slot: HTMLElement) {
        if (video.parentNode !== slot) slot.appendChild(video)
    }

    /**
     * The player screen is leaving. The episode stops where it is, which also saves the
     * place, and the element leaves the page. It is kept, not rebuilt: see the class note.
     */
    fun detach(slot: HTMLElement) {
        pause()
        if (video.parentNode === slot) slot.removeChild(video)
    }

    // -------------------------------------------------------------- control

    /**
     * Loads [episode], and starts it when [play] is true.
     *
     * Call with `play = true` only from a tap handler. iOS refuses `play()` outside a
     * user gesture until this element has been started by one, so starting from an
     * effect after navigation would be silently refused on the first episode.
     */
    fun open(episode: Episode, play: Boolean) {
        if (episode.id == episodeId) {
            if (play) playNow()
            return
        }
        // The episode being left. Skipped after `ended`, which has already saved it as watched.
        if (episodeId != null && !video.ended) persist()

        val entry = catalog.progress[episode.id]
        // A finished episode starts over. Resuming it would land five seconds from the end.
        val saved = entry?.takeIf { !it.isWatched }?.progressSeconds ?: 0

        episodeId = episode.id
        failed = false
        retried = false
        buffering = false
        position = saved
        duration = episode.durationSeconds ?: entry?.durationSeconds
        resumeTarget = saved.toDouble().takeIf { saved > 1 }
        resumeAttempts = 0
        lastSaved = resumeTarget ?: 0.0
        updateMetadata(episode)

        val url = sourceFor(episode)
        if (url == null) {
            fail()
            wantsToPlay = false
            video.removeAttribute("src")
            video.load()
            return
        }
        // iOS resumes through the source itself, as the web player does (see
        // [withStartFragment]). [resume] stays as the fallback and finds it already there.
        video.src = if (isAppleMobile) withStartFragment(url, saved) else url
        if (play) playNow()
    }

    fun toggle() {
        if (video.paused) playNow() else pause()
    }

    fun pause() {
        wantsToPlay = false
        video.pause()
    }

    /** Relative to the saved position while the resume has not landed yet, not to 0:00. */
    fun skip(seconds: Double) {
        if (episodeId == null) return
        val from = resumeTarget ?: video.currentTime
        seek(from + seconds)
    }

    fun next() {
        val next = current?.let { catalog.next(it) } ?: return
        advanceTo(next)
    }

    /** Signed out: nothing may keep playing, or saving, for an account that is gone. */
    fun stop() {
        wantsToPlay = false
        video.pause()
        video.removeAttribute("src")
        video.load()
        episodeId = null
        playing = false
        resumeTarget = null
        runCatching { mediaSession?.metadata = null }
    }

    private fun playNow() {
        wantsToPlay = true
        val episode = current
        // A tap on Play after a failed load is a request to try again.
        if (failed && episode != null) {
            episodeId = null
            open(episode, play = true)
            return
        }
        video.play().catch { error ->
            // `NotAllowedError` on iOS when this was not a tap. The big button is still there.
            Log.w("drive", "play() refused", error)
            playing = false
        }
    }

    private fun seek(target: Double) {
        val total = video.duration.takeIf { it.isFinite() && it > 0 }
        val clamped = target.coerceAtLeast(0.0).let { if (total != null) min(it, total - 1.0) else it }
        // A seek the viewer asked for replaces the resume, which must not drag them back.
        resumeTarget = null
        runCatching { video.currentTime = clamped }
            .onFailure { Log.w("drive", "seek to ${clamped}s rejected", it) }
        position = floor(clamped).toInt()
        lastSaved = clamped
        updatePositionState()
    }

    private fun advanceTo(next: Episode) {
        open(next, play = true)
        // Only when the player is what is open. An episode ending while the viewer is on
        // the home screen must not pull them onto another screen.
        if (router.current is Route.Watch) router.replace(Route.Watch(next.id))
    }

    /**
     * Gives up on this episode until the next tap. A failed load can leave the element
     * reporting itself unpaused, and a `play` event may already have arrived, so the
     * screen would otherwise keep saying "Pause" over an episode that is not playing.
     */
    private fun fail() {
        failed = true
        playing = false
        buffering = false
        releaseWakeLock()
        runCatching { mediaSession?.playbackState = "paused" }
    }

    // --------------------------------------------------------------- events

    private fun onDurationKnown() {
        duration = video.duration.takeIf { it.isFinite() && it > 0 }?.let { floor(it).toInt() }
        updatePositionState()
    }

    private fun onTimeUpdate() {
        resume(confirming = true)
        if (resumeTarget != null) return
        val time = video.currentTime
        val whole = floor(time).toInt()
        if (whole != position) {
            position = whole
            updatePositionState()
        }
        if (time - lastSaved >= SAVE_EVERY) {
            lastSaved = time
            persist()
        }
    }

    // The native controls start and stop the element without going through [playNow]
    // or [pause], so the viewer's intent is read back from the element's own events.
    private fun onPlay() {
        wantsToPlay = true
        playing = true
        acquireWakeLock()
        runCatching { mediaSession?.playbackState = "playing" }
    }

    private fun onPause() {
        // A load error can pause the element too. That is not the viewer stopping, and
        // the retry in [onError] should carry on playing.
        if (video.error == null) wantsToPlay = false
        playing = false
        persist()
        releaseWakeLock()
        runCatching { mediaSession?.playbackState = "paused" }
    }

    private fun onEnded() {
        persist(isWatched = true)
        val next = current?.let { catalog.next(it) }
        if (settings.autoplayNext && next != null) advanceTo(next) else wantsToPlay = false
    }

    private fun onError() {
        // Emptying the source on sign-out reports an error too. That one is not a failure.
        if (video.getAttribute("src").isNullOrEmpty()) return
        val episode = current ?: return
        Log.e("drive", "media error for ${episode.id} (code=${video.error?.code})")
        if (retried) {
            fail()
            return
        }
        retried = true
        // The stored link may have expired. Ask Formula for a fresh one, once, and carry on
        // from where this one stopped.
        resumeTarget = resumeTarget ?: video.currentTime.takeIf { it > 1 }
        resumeAttempts = 0
        val id = episode.id
        scope.launch {
            val fresh = runCatching { resolveVideo(episode) }
                .onFailure { Log.e("drive", "resolve failed for $id", it) }
                .getOrNull()
            if (episodeId != id) return@launch
            val url = fresh?.let { catalog.putEpisode(it); sourceFor(it) }
            if (url == null) {
                fail()
                return@launch
            }
            video.src = url
            if (wantsToPlay) {
                video.play().catch {
                    Log.w("drive", "play() after retry refused", it)
                    playing = false
                }
            }
        }
    }

    // --------------------------------------------------------------- resume

    /**
     * Moves to the saved position once the element will take it, and only counts it done
     * once the element is seen there.
     *
     * iOS silently drops a seek issued before the element is ready, and still reads back
     * the new `currentTime` straight after the assignment. So the assignment is never the
     * proof: a later `seeked`, `playing` or `timeupdate` that finds the element at the
     * target, with no seek still running, is. Until then each media event tries again,
     * up to [MAX_RESUME_ATTEMPTS].
     *
     * Saves are held while this is pending. Saving the 0:00 a dropped seek leaves behind
     * would cost the viewer their place.
     */
    private fun resume(confirming: Boolean) {
        val wanted = resumeTarget ?: return
        val total = video.duration
        if (!total.isFinite() || total <= 0) return
        val target = min(wanted, max(0.0, total - END_MARGIN))

        if (abs(video.currentTime - target) < SEEK_TOLERANCE) {
            if (confirming && !video.seeking) {
                resumeTarget = null
                lastSaved = target
                position = floor(target).toInt()
                updatePositionState()
            }
            return
        }
        if (resumeAttempts >= MAX_RESUME_ATTEMPTS) {
            Log.w("resume", "seek to ${target}s never landed; playing from ${video.currentTime}s")
            resumeTarget = null
            return
        }
        resumeAttempts += 1
        runCatching { video.currentTime = target }
            .onFailure { Log.w("resume", "seek to ${target}s rejected", it) }
    }

    // -------------------------------------------------------------- saving

    private fun persist(isWatched: Boolean? = null) {
        val id = episodeId ?: return
        val user = uid() ?: return
        if (resumeTarget != null && isWatched == null) return
        val seconds = floor(video.currentTime).toInt()
        if (seconds < 1 && isWatched == null) return
        val total = video.duration.takeIf { it.isFinite() && it > 0 }?.let { floor(it).toInt() }
        scope.launch {
            runCatching {
                catalog.saveProgress(
                    uid = user,
                    episodeId = id,
                    progressSeconds = seconds,
                    durationSeconds = total,
                    isWatched = isWatched,
                )
            }.onFailure { Log.e("progress", "save failed for $id at ${seconds}s", it) }
        }
    }

    // -------------------------------------------------------- media session

    /**
     * The lock screen, Control Center, a Bluetooth head unit and the steering-wheel
     * buttons all reach the page through this one object. `undefined` where unsupported.
     */
    private val mediaSession: dynamic get() = window.navigator.asDynamic().mediaSession

    private val actions: List<Pair<String, (dynamic) -> Unit>> = listOf(
        "play" to { _ -> playNow() },
        "pause" to { _ -> pause() },
        // The system may say how far its own skip button goes. Honour it, so the icon it
        // draws and the distance moved agree.
        "seekbackward" to { details -> skip(-((details?.seekOffset as? Double) ?: SKIP_SECONDS)) },
        "seekforward" to { details -> skip((details?.seekOffset as? Double) ?: SKIP_SECONDS) },
        // Steering-wheel "previous" goes back 30 seconds rather than a whole episode: a
        // press by mistake should cost half a minute, not an episode.
        "previoustrack" to { _ -> skip(-SKIP_SECONDS) },
        "nexttrack" to { _ -> next() },
        "seekto" to { details -> (details?.seekTime as? Double)?.let { seek(it) } },
    )

    private fun installMediaSession() {
        val session = mediaSession ?: return
        for ((action, handler) in actions) {
            // A browser throws for an action it does not know. The others still count.
            runCatching { session.setActionHandler(action, handler) }
                .onFailure { Log.d("drive", "media session has no '$action' action") }
        }
    }

    private fun clearMediaSession() {
        val session = mediaSession ?: return
        for ((action, _) in actions) runCatching { session.setActionHandler(action, null) }
    }

    private fun updateMetadata(episode: Episode) {
        val session = mediaSession ?: return
        if (jsTypeOf(window.asDynamic().MediaMetadata) != "function") return
        val init: dynamic = json(
            "title" to S.seasonAndEpisode(episode.seasonNumber, episode.episodeNumber),
            "artist" to S.seriesTitle,
            "album" to S.driveMode,
            "artwork" to arrayOf(
                json(
                    // Absolute: the lock screen does not resolve against this page.
                    "src" to URL("${assetBase}icon-512.png", window.location.href).href,
                    "sizes" to "512x512",
                    "type" to "image/png",
                ),
            ),
        )
        runCatching { session.metadata = MediaMetadata(init) }
            .onFailure { Log.w("drive", "media session metadata rejected", it) }
    }

    /** Lets the system draw a moving progress bar. Throws if position exceeds duration. */
    private fun updatePositionState() {
        val session = mediaSession ?: return
        val total = video.duration
        if (!total.isFinite() || total <= 0 || jsTypeOf(session.setPositionState) != "function") return
        runCatching {
            session.setPositionState(
                json(
                    "duration" to total,
                    "playbackRate" to video.playbackRate,
                    "position" to min(max(video.currentTime, 0.0), total),
                ),
            )
        }
    }

    // ----------------------------------------------------------- wake lock

    /**
     * Keeps the screen on while the episode plays, so the phone does not lock in its
     * mount mid-episode. Released on pause. The browser releases it by itself whenever
     * the page is hidden, and it can only be asked for while the page is visible.
     */
    private fun acquireWakeLock() {
        if (wakeLock != null || wakeLockPending) return
        if (document.asDynamic().visibilityState != "visible") return
        val api = window.navigator.asDynamic().wakeLock ?: return
        wakeLockPending = true
        runCatching {
            api.request("screen").then(
                { lock: dynamic ->
                    wakeLockPending = false
                    if (playing) {
                        wakeLock = lock
                        lock.addEventListener("release", { _: dynamic -> if (wakeLock === lock) wakeLock = null })
                    } else {
                        // Paused while the request was in flight.
                        lock.release()
                    }
                },
                { error: dynamic ->
                    wakeLockPending = false
                    Log.w("drive", "screen wake lock refused: ${error?.name}")
                },
            )
        }.onFailure {
            wakeLockPending = false
            Log.w("drive", "screen wake lock unavailable", it)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { lock.release() }
    }

    /**
     * The same choice the web player makes: the quality the viewer picked if this episode
     * has it, otherwise the best rendition, capped at 720p on a phone. The episode's
     * default stream only when it lists no renditions.
     */
    private fun sourceFor(episode: Episode): String? {
        val sources = episode.sources
        val label = preferences.preferredQuality?.takeIf { sources.containsKey(it) }
            ?: defaultQualityLabel(sources, COMPACT_SCREEN_MAX_QUALITY.takeIf { isCompactScreen })
        return label?.let { sources[it] } ?: episode.videoUrl
    }
}
