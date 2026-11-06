package ge.dakalebi.next.drive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.service.COMPACT_SCREEN_MAX_QUALITY
import ge.dakalebi.domain.service.withStartFragment
import ge.dakalebi.presentation.CatalogStore
import ge.dakalebi.presentation.PlaybackError
import ge.dakalebi.presentation.PlaybackViewModel
import ge.dakalebi.presentation.PlaybackViewModelFactory
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router
import ge.dakalebi.next.player.VideoPlayhead
import ge.dakalebi.next.player.isAppleMobile
import ge.dakalebi.next.player.isCompactScreen
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** How far the big skip buttons, and the steering wheel's previous-track button, move. */
const val SKIP_SECONDS = 30.0

/** Resuming a nearly finished episode stops this far short, so it does not end at once. */
private const val END_MARGIN = 5.0

/** Close enough to the saved position to count as there. */
private const val SEEK_TOLERANCE = 1.0

/** A stream that never accepts the resume seek plays from where it is, rather than fighting forever. */
private const val MAX_RESUME_ATTEMPTS = 8

/**
 * Driving mode's player: one native `<video>` for the whole visit, and the big controls
 * and system buttons that reach it.
 *
 * **The native player.** The element has `controls` and `playsinline`, the same as
 * the web page's player (`next/web/WebPlayer.kt`): on an iPhone that is Apple's own player,
 * with its fullscreen, AirPlay and picture-in-picture. The big buttons on the player
 * screen drive this same element, so the two never disagree.
 *
 * **One element, never rebuilt.** iOS lets an element start without a tap only after a
 * tap has started it once. The home screen's button starts it inside its tap, before
 * the player screen exists, and every later start, the steering wheel's next track
 * included, reuses that permission. A fresh element per episode would lose it. The
 * player screen only lends the element a place on the page ([attach]/[detach]).
 *
 * **No autoplay for now.** An episode that ends stays on its last frame. Next and
 * Previous are taps.
 *
 * **Not Compose.** The fields below are Compose state so the screen can draw them, but
 * nothing here waits for a recomposition. [next] and [previous] swap the source and
 * call `play()` themselves, and the route follows.
 *
 * **The rules are shared.** Each episode opened gets its own [PlaybackViewModel], the one
 * the web and TV watch screens use: it picks the file, saves (every seven seconds, on
 * pause, at the end), makes the one retry after an error and clears progress. What stays
 * here is this player's: the element and its resume, and when to call the media session
 * ([DriveMediaSession]), the wake lock ([ScreenWakeLock]) and full screen
 * ([enterVideoFullscreen]), which each have a file of their own. The resume stays
 * because the skip buttons count from the saved spot until it lands, and because a
 * finished episode starts over here rather than resuming near its end.
 */
class DrivePlayer(
    private val catalog: CatalogStore,
    private val router: Router,
    /** Makes the rules for each episode opened. */
    private val playback: PlaybackViewModelFactory,
) {
    /**
     * Lives as long as the page, like the graph's own scope. A composable's scope is
     * cancelled as the screen leaves, which is the moment the last save is made.
     */
    private val scope = MainScope()

    /** The open episode's rules. Replaced at each [open], dropped at [stop]. */
    private var viewModel: PlaybackViewModel? = null

    /** Applies what the open episode's retry decides. See [follow]. */
    private var following: Job? = null

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

    /**
     * The element knows its picture, so it can go full screen, playing or not. The big
     * Fullscreen button waits for this. Asked earlier, an iPhone does nothing: in the
     * Simulator, a call on an element with nothing loaded neither threw nor opened the
     * player, and the next call, on a loaded element, was ignored too.
     */
    var fullscreenReady: Boolean by mutableStateOf(false)
        private set

    /** Whole seconds, so the screen redraws once a second rather than four times. */
    var position: Int by mutableStateOf(0)
        private set
    var duration: Int? by mutableStateOf(null)
        private set

    /** Why the open episode did not play, which picks the message. Null while it can. */
    var failure: PlaybackError? by mutableStateOf(null)
        private set
    val failed: Boolean get() = failure != null

    /**
     * The open episode's video link, without the `#t=` resume fragment: what "Open in
     * VLC" hands over. Null when the episode has none.
     */
    var sourceUrl: String? by mutableStateOf(null)
        private set

    /** The rendition [sourceUrl] is, by label. Null when the episode lists none. */
    var quality: String? by mutableStateOf(null)
        private set

    /** Where the episode should start, until the element is confirmed to be there. */
    private var resumeTarget: Double? = null
    private var resumeAttempts = 0

    /** The viewer's intent, as opposed to the element's state: a reload after a failed load keeps it. */
    private var wantsToPlay = false

    /** A lock granted after a pause is let go at once. */
    private val wakeLock = ScreenWakeLock(stillWanted = { playing })

    private val mediaSession = DriveMediaSession(
        play = ::playNow,
        pause = ::pause,
        skip = ::skip,
        next = ::next,
        seek = ::seek,
    )

    private val current: Episode? get() = episodeId?.let { catalog.byId(it) }

    /** Read in composition, so the Next button follows the catalog. */
    val hasNext: Boolean get() = current?.let { catalog.next(it) } != null

    /** Read in composition, so the Previous button follows the catalog. */
    val hasPrevious: Boolean get() = current?.let { previousOf(it) } != null

    // ------------------------------------------------------------ lifecycle

    /** Attaches every listener. Returns the teardown. */
    fun install(): () -> Unit {
        val mediaEvents = listOf<Pair<String, (Event) -> Unit>>(
            // iOS drops a seek issued this early, so on iPhone the first attempt waits.
            "loadedmetadata" to {
                fullscreenReady = true
                onDurationKnown()
                if (!isAppleMobile) resume(confirming = false)
            },
            // A new source, or none: the element starts over from knowing nothing.
            "emptied" to { fullscreenReady = false },
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
            else if (playing) wakeLock.acquire()
        }
        val onPageHide: (Event) -> Unit = { persist() }
        document.addEventListener("visibilitychange", onVisibility)
        window.addEventListener("pagehide", onPageHide)

        mediaSession.install()

        return {
            persist()
            wantsToPlay = false
            video.pause()
            mediaEvents.forEach { (type, handler) -> video.removeEventListener(type, handler) }
            document.removeEventListener("visibilitychange", onVisibility)
            window.removeEventListener("pagehide", onPageHide)
            mediaSession.clear()
            wakeLock.release()
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
        release()

        val entry = catalog.progress[episode.id]
        // A finished episode starts over. Resuming it would land five seconds from the end.
        val saved = entry?.takeIf { !it.isWatched }?.progressSeconds ?: 0

        // The same choice the web player makes: the quality the viewer picked if this
        // episode has it, otherwise the best rendition, capped at 720p on a phone. The
        // episode's default stream only when it lists no renditions.
        val rules = playback.create(
            episodeId = episode.id,
            maxQuality = COMPACT_SCREEN_MAX_QUALITY.takeIf { isCompactScreen },
            appleMobile = isAppleMobile,
        )
        rules.attach(VideoPlayhead(video))
        rules.startFrom(episode)
        viewModel = rules

        episodeId = episode.id
        failure = null
        buffering = false
        position = saved
        duration = episode.durationSeconds ?: entry?.durationSeconds
        resumeTarget = saved.toDouble().takeIf { saved > 1 }
        resumeAttempts = 0
        // Timed saves count from the saved spot, not from 0:00.
        rules.jumpedTo(resumeTarget ?: 0.0)
        mediaSession.showEpisode(episode)

        val chosen = rules.state.value
        val url = chosen.videoUrl
        sourceUrl = url
        quality = chosen.quality
        if (url == null) {
            // Driving mode keeps one message for a missing link and a failed one.
            fail(PlaybackError.LoadFailed)
            wantsToPlay = false
            video.removeAttribute("src")
            video.load()
            return
        }
        // iOS resumes through the source itself, as the web player does (see
        // [withStartFragment]). [resume] stays as the fallback and finds it already there.
        video.src = if (isAppleMobile) withStartFragment(url, saved) else url
        follow(rules)
        if (play) playNow()
    }

    /**
     * Applies what [rules] decides after a media error: a fresh link to load, carrying on
     * from where the failed one stopped, or the failure to show.
     *
     * The first value is the link [open] already loaded, so it changes nothing. Only the
     * link and the error are read, so the time updates do not wake this.
     */
    private fun follow(rules: PlaybackViewModel) {
        following = scope.launch {
            rules.state.map { it.videoUrl to it.error }.distinctUntilChanged().collect { (url, error) ->
                if (rules !== viewModel) return@collect
                if (error != null) {
                    if (failure == null) fail(error)
                    return@collect
                }
                if (url == null || url == sourceUrl) return@collect
                sourceUrl = url
                video.src = url
                if (wantsToPlay) {
                    video.play().catch {
                        Log.w("drive", "play() after retry refused", it)
                        playing = false
                    }
                }
            }
        }
    }

    /** Stops following the open episode's rules. Saves already sent still land. */
    private fun release() {
        following?.cancel()
        following = null
        viewModel?.close()
        viewModel = null
    }

    /**
     * Marks the open episode watched, as the web page's button does, at the current
     * position. Watched is sticky in `SaveProgress`, so the saves that follow keep it.
     */
    fun markWatched() {
        viewModel?.markWatched()
    }

    /**
     * Undoes "watched" the way the web page's Clear does: the episode's progress is
     * deleted and the picture goes back to the start, so it looks never watched.
     * [done] says whether the delete went through.
     */
    fun clearProgress(done: (Boolean) -> Unit) {
        val id = episodeId ?: return
        val rules = viewModel ?: return
        scope.launch {
            val cleared = rules.clearProgress()
            if (cleared && episodeId == id) seek(0.0)
            done(cleared)
        }
    }

    fun toggle() {
        if (video.paused) playNow() else pause()
    }

    fun pause() {
        wantsToPlay = false
        video.pause()
    }

    /**
     * The picture, full screen, once [fullscreenReady]. Call from a tap: every browser
     * refuses otherwise. See [enterVideoFullscreen].
     */
    fun enterFullscreen() {
        if (!fullscreenReady) return
        enterVideoFullscreen(video)
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

    /** The episode before this one, crossing back into the previous season when needed. */
    fun previous() {
        val previous = current?.let { previousOf(it) } ?: return
        advanceTo(previous)
    }

    private fun previousOf(episode: Episode): Episode? = catalog.previous(episode, 1).firstOrNull()

    /** Signed out: nothing may keep playing, or saving, for an account that is gone. */
    fun stop() {
        wantsToPlay = false
        release()
        video.pause()
        video.removeAttribute("src")
        video.load()
        episodeId = null
        playing = false
        resumeTarget = null
        mediaSession.clearEpisode()
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
        viewModel?.jumpedTo(clamped)
        mediaSession.updatePosition(video)
    }

    private fun advanceTo(episode: Episode) {
        open(episode, play = true)
        // Only when the player is what is open. The steering wheel's next track, pressed
        // while the viewer is on the home screen, must not pull them onto another screen.
        if (router.current is Route.Watch) router.replace(Route.Watch(episode.id))
    }

    /**
     * Gives up on this episode until the next tap. A failed load can leave the element
     * reporting itself unpaused, and a `play` event may already have arrived, so the
     * screen would otherwise keep saying "Pause" over an episode that is not playing.
     */
    private fun fail(reason: PlaybackError) {
        failure = reason
        playing = false
        buffering = false
        wakeLock.release()
        mediaSession.setPlaybackState("paused")
    }

    // --------------------------------------------------------------- events

    private fun onDurationKnown() {
        duration = video.duration.takeIf { it.isFinite() && it > 0 }?.let { floor(it).toInt() }
        mediaSession.updatePosition(video)
    }

    private fun onTimeUpdate() {
        resume(confirming = true)
        if (resumeTarget != null) return
        val time = video.currentTime
        val whole = floor(time).toInt()
        if (whole != position) {
            position = whole
            mediaSession.updatePosition(video)
        }
        // Saves every seven seconds of playback.
        viewModel?.onTimeUpdate()
    }

    // The native controls start and stop the element without going through [playNow]
    // or [pause], so the viewer's intent is read back from the element's own events.
    private fun onPlay() {
        wantsToPlay = true
        playing = true
        wakeLock.acquire()
        mediaSession.setPlaybackState("playing")
    }

    private fun onPause() {
        // A load error can pause the element too. That is not the viewer stopping, and
        // the retry in [onError] should carry on playing.
        if (video.error == null) wantsToPlay = false
        playing = false
        persist()
        wakeLock.release()
        mediaSession.setPlaybackState("paused")
    }

    private fun onEnded() {
        persist(isWatched = true)
        wantsToPlay = false
    }

    private fun onError() {
        // Emptying the source on sign-out reports an error too. That one is not a failure.
        if (video.getAttribute("src").isNullOrEmpty()) return
        val rules = viewModel ?: return
        // On an iPhone, code 4 is a file Safari cannot decode (all of season 2), and the
        // message then points to VLC. The rules log it and pick the message.
        if (rules.onMediaError(video.error?.code?.toInt())) {
            // The stored link may have expired. The rules ask Formula for a fresh one,
            // once, and [follow] loads it. It carries on from where this one stopped.
            resumeTarget = resumeTarget ?: video.currentTime.takeIf { it > 1 }
            resumeAttempts = 0
        } else {
            // Already retried: give up until the next tap.
            rules.state.value.error?.let { fail(it) }
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
                viewModel?.jumpedTo(target)
                position = floor(target).toInt()
                mediaSession.updatePosition(video)
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

    /** Held while the resume is pending, unless it records a decision such as watched. */
    private fun persist(isWatched: Boolean? = null) {
        if (resumeTarget != null && isWatched == null) return
        viewModel?.save(isWatched)
    }
}
