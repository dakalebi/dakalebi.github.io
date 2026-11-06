package ge.dakalebi.presentation

import ge.dakalebi.core.Log
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.service.PlaybackFailure
import ge.dakalebi.domain.service.defaultQualityLabel
import ge.dakalebi.domain.service.playbackFailure
import ge.dakalebi.domain.service.retryUrl
import ge.dakalebi.domain.usecase.ResolveEpisodeVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Save at most this often during continuous playback. */
const val SAVE_EVERY = 7.0

/** Seconds before the end at which the next-episode card appears. */
const val PROMPT_WINDOW = 180.0

/**
 * How far short of the end a resume lands, so resuming a nearly finished episode
 * does not fire `ended` at once.
 */
private const val RESUME_END_MARGIN = 5.0

/** Share of the episode after which leaving for the next one counts as finishing this one. */
private const val NEAR_END = 0.9

/**
 * Where the player is, in seconds.
 *
 * The screen supplies it, because only the screen can read its media element.
 * It may correct what the element reports, as the web does while iOS plays
 * through AirPlay and reports 0:00.
 */
interface Playhead {
    val position: Double

    /** Null until the element knows it. */
    val duration: Double?
}

/** Why a video is not playing. The words are [ErrorMessages.playback]'s job. */
enum class PlaybackError {
    /** No link to play, even after asking Formula for a fresh one. */
    NoLink,

    /** See [PlaybackFailure.UnsupportedOnAppleMobile]. */
    UnsupportedOnAppleMobile,

    /** See [PlaybackFailure.LoadFailed]. */
    LoadFailed,
}

/** What the watch screen draws. */
data class PlaybackUiState(
    /** True until the first resolve has answered. */
    val resolving: Boolean = true,
    /** The renditions the quality menu offers, and the only map the label is read from. */
    val sources: Map<String, String> = emptyMap(),
    val quality: String? = null,
    val videoUrl: String? = null,
    val error: PlaybackError? = null,
    val ended: Boolean = false,
    val duration: Double? = null,
    val remaining: Double? = null,
    /**
     * Where the player is, in whole seconds, so a `timeupdate` four times a second
     * changes this at most once. Null until the resume has landed, or the player
     * has passed the saved spot on its own.
     */
    val position: Int? = null,
    val promptDismissed: Boolean = false,
) {
    /**
     * Whether the next-episode card is due. The screen adds what this cannot know:
     * that there is a next episode, and that its player can draw an overlay at all.
     */
    val promptDue: Boolean
        get() = duration != null &&
            remaining != null &&
            remaining <= PROMPT_WINDOW &&
            !ended &&
            !promptDismissed
}

/**
 * One episode's playback: which file plays, where the viewer is, and what that
 * position means.
 *
 * This holds the rules, not the workarounds. The resume retry loops for iOS, the
 * AirPlay clock, the `#t=` start fragment and the media element itself belong to
 * the player of the platform that needs them. The player tells this what the
 * element did (`onTimeUpdate`, `onPause`, `onEnded`, ...) and reads the answer
 * from [state].
 *
 * All three players use it: the web watch screen, the TV watch screen and
 * driving mode's player. They differ in what they call, not in the rules:
 * - The web asks Formula before it plays ([resolve]). TV plays the stored link at
 *   once and swaps in the fresh one ([startFrom], then [resolve]). Driving mode
 *   plays the stored link and asks Formula only after an error ([startFrom]).
 * - Only the web remembers whether the viewer left an episode playing or paused
 *   ([onPlay], [onPause], [onEnded]). TV and driving mode save through [save] and
 *   [markWatched], which leave that alone.
 * - The web and TV resume through [resumeTarget]. Driving mode keeps its own
 *   resume, because its skip buttons count from the saved spot until it lands, and
 *   tells this where it moved the element ([jumpedTo]).
 *
 * One per episode, from [PlaybackViewModelFactory]. Saves run in the app scope, so
 * a save in flight still lands when the player moves on. The resolve and the retry
 * run in a child scope that [close] cancels.
 *
 * Toasts stay with the player: the methods that end in one say so in their
 * result, and each platform decides what it tells the viewer.
 */
class PlaybackViewModel(
    private val episodeId: String,
    /** Read at each save: whose progress this is, an account's or a guest's. */
    private val viewerId: () -> String?,
    private val catalog: CatalogStore,
    private val settings: SettingsStore,
    private val preferences: PreferencesStore,
    private val resolveEpisodeVideo: ResolveEpisodeVideo,
    private val router: Router,
    /** The highest quality this screen starts in when the viewer has not picked one. */
    private val maxQuality: Int?,
    /** Decides which failure a rejected source is. */
    private val appleMobile: Boolean,
    private val scope: CoroutineScope,
) : ViewModel<PlaybackUiState>(PlaybackUiState()) {

    private val jobs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private var playhead: Playhead? = null
    private var lastSaved = 0.0
    private var retried = false
    private var navigated = false

    /** True once the resume has landed, or there was nothing to resume. */
    private var resumed = false

    /** Whether the player starts playing. Read once: it is how the viewer left this episode. */
    val autoplay: Boolean = preferences.playIntent(episodeId) != "paused"

    private val savedSeconds: Int get() = catalog.progress[episodeId]?.progressSeconds ?: 0

    /*
     * The quality the viewer picked, read once per episode. The preference syncs,
     * and a pick made on another device must not relabel, or restart, the stream
     * already playing here.
     */
    private var preferredRead = false
    private var preferred: String? = null

    private fun preferredQuality(): String? {
        if (!preferredRead) {
            preferred = settings[Setting.PreferredQuality]
            preferredRead = true
        }
        return preferred
    }

    /** The element appeared or went. Without one there is nothing to save. */
    fun attach(playhead: Playhead?) {
        this.playhead = playhead
    }

    // --------------------------------------------------------- resolution

    /**
     * Picks the file from the links the catalog already holds, without asking
     * Formula, so playback can start at once. The same choice as [resolve].
     * [PlaybackUiState.resolving] stays as it was.
     */
    fun startFrom(episode: Episode) {
        val (quality, url) = choose(episode.sources, episode.videoUrl)
        setState { copy(sources = episode.sources, quality = quality, videoUrl = url) }
    }

    /**
     * Asks Formula for fresh links and picks the file to play.
     *
     * A quality picked on any device of this account wins. Otherwise the best one
     * at or under [maxQuality]. The URL follows the label, so the file that plays
     * is always the one the quality menu names.
     */
    suspend fun resolve(episode: Episode) {
        setState { copy(resolving = true, error = null) }
        val resolved = resolveEpisodeVideo(episode)
        if (resolved !== episode) catalog.putEpisode(resolved)

        val sources = resolved.sources.ifEmpty { episode.sources }
        val (quality, url) = choose(sources, resolved.videoUrl)
        setState {
            copy(
                sources = sources,
                quality = quality,
                videoUrl = url,
                error = if (url == null) PlaybackError.NoLink else error,
                resolving = false,
            )
        }
    }

    /** The label and the file it names. [fallbackUrl] only when [sources] has no label to offer. */
    private fun choose(sources: Map<String, String>, fallbackUrl: String?): Pair<String?, String?> {
        val quality = preferredQuality()?.takeIf { sources.containsKey(it) }
            ?: defaultQualityLabel(sources, maxQuality)
        return quality to (quality?.let { sources[it] } ?: fallbackUrl)
    }

    /**
     * The viewer picked a rendition. It becomes the account's preference, and
     * [onNotSynced] fires if that write is rejected.
     */
    fun selectQuality(label: String, onNotSynced: () -> Unit) {
        preferred = label
        preferredRead = true
        setState { copy(quality = label) }
        settings.set(Setting.PreferredQuality, label, onNotSynced)
        // A new source, so an error about the old one no longer applies.
        state.value.sources[label]?.let { url -> setState { copy(videoUrl = url, error = null) } }
    }

    /**
     * The element could not play its source. Returns true when this retries with a
     * fresh link, which happens once: the quality that was playing, not the fresh
     * best one, which would undo the viewer's pick and a phone's lower start.
     */
    fun onMediaError(code: Int?): Boolean {
        val episode = catalog.byId(episodeId) ?: return false
        Log.e("player", "media error for ${episode.id} (code=$code, src=${state.value.videoUrl})")
        val failure = when (playbackFailure(code, appleMobile)) {
            PlaybackFailure.UnsupportedOnAppleMobile -> PlaybackError.UnsupportedOnAppleMobile
            PlaybackFailure.LoadFailed -> PlaybackError.LoadFailed
        }
        if (retried) {
            setState { copy(error = failure) }
            return false
        }
        retried = true
        jobs.launch {
            val resolved = resolveEpisodeVideo(episode)
            catalog.putEpisode(resolved)
            val fresh = resolved.sources.ifEmpty { episode.sources }
            val current = state.value
            val next = retryUrl(
                failedUrl = current.videoUrl,
                freshUrl = current.quality?.let { fresh[it] } ?: resolved.videoUrl,
            )
            // No new link means no new load, so say so now instead of waiting.
            setState { if (next != null) copy(videoUrl = next) else copy(error = failure) }
        }
        return true
    }

    // ------------------------------------------------------------- resume

    /**
     * Where to seek to resume, or null when there is nothing to do: the catalog
     * is still loading, the duration is not known, the resume already landed, or
     * the saved spot is the start. Clamped short of the end.
     *
     * Called again at each media event until [resumeLanded], because how many
     * attempts a seek needs is the platform's business.
     */
    fun resumeTarget(): Double? {
        if (catalog.loading || resumed) return null
        val duration = playhead?.duration ?: return null
        if (duration <= 0) return null

        val target = min(savedSeconds.toDouble(), max(0.0, duration - RESUME_END_MARGIN))
        if (target <= 1.0) {
            resumed = true
            setState { copy(duration = duration) }
            return null
        }
        lastSaved = target
        setState { copy(duration = duration, remaining = max(0.0, duration - target)) }
        return target
    }

    /** The resume seek landed, or the platform gave up on it. */
    fun resumeLanded() {
        resumed = true
    }

    /**
     * The player moved the element to [seconds] itself: a seek the viewer asked
     * for, or a resume it landed without [resumeTarget]. The next timed save counts
     * from there, so a skip forward does not save at once and a skip back does not
     * wait to pass the old spot.
     */
    fun jumpedTo(seconds: Double) {
        lastSaved = seconds
    }

    // -------------------------------------------------------- media events

    fun onDurationKnown() {
        playhead?.duration?.let { duration -> setState { copy(duration = duration) } }
    }

    /** Several times a second while playing. Saves every [SAVE_EVERY] seconds. */
    fun onTimeUpdate() {
        val head = playhead ?: return
        val time = head.position
        trackPosition(time)
        if (time - lastSaved >= SAVE_EVERY) {
            lastSaved = time
            save()
        }
        val duration = head.duration
        if (duration != null && duration > 0) {
            val left = max(0.0, duration - time)
            setState {
                copy(
                    duration = duration,
                    remaining = left,
                    // Re-arm the card if the viewer seeks back out of the window.
                    promptDismissed = promptDismissed && left <= PROMPT_WINDOW,
                )
            }
        }
    }

    /**
     * For a platform whose element stops reporting time while it plays, such as
     * iOS through AirPlay: keeps the position and the time left moving, and saves
     * nothing.
     */
    fun onTick() {
        val head = playhead ?: return
        trackPosition(head.position)
        val duration = head.duration
        if (duration != null && duration > 0) {
            setState { copy(duration = duration, remaining = max(0.0, duration - head.position)) }
        }
    }

    fun onPlay() {
        preferences.setPlayIntent(episodeId, "playing")
    }

    fun onPause() {
        preferences.setPlayIntent(episodeId, "paused")
        save()
    }

    /** Played to the end: watched. No autoplay into the next episode from here. */
    fun onEnded() {
        preferences.setPlayIntent(episodeId, "paused")
        save(isWatched = true)
        setState { copy(ended = true) }
    }

    /**
     * Ignored until the resume seek has landed, or the player has passed the saved
     * spot on its own: before that the element reports the 0:00 it loaded at, and
     * a label would flash it over the saved time.
     */
    private fun trackPosition(time: Double) {
        if (resumed || time >= savedSeconds) {
            val whole = floor(time).toInt()
            setState { copy(position = whole) }
        }
    }

    // ------------------------------------------------------------- actions

    fun dismissPrompt() {
        setState { copy(promptDismissed = true) }
    }

    /** The screen has moved the element back to 0:00. */
    fun watchFromStart() {
        lastSaved = 0.0
        setState { copy(ended = false) }
        save(allowReset = true)
    }

    fun markWatched() {
        save(isWatched = true)
    }

    /**
     * Leaves for the next episode, once. Counts this one as watched when the
     * viewer leaves near its end, or after it ended.
     */
    fun goToNext() {
        val next = leaveForNext() ?: return
        router.go(Route.Watch(next))
    }

    /**
     * Saves this episode on the way to the next one, and returns the next one's id
     * for the caller to play. Once: null after the first call, and when there is no
     * next episode. Counts this one as watched when the viewer leaves near its end,
     * or after it ended.
     *
     * [goToNext] for a player that is a page of its own; the now-playing holder,
     * which swaps the episode in place, calls this.
     */
    fun leaveForNext(): String? {
        val next = catalog.byId(episodeId)?.let { catalog.next(it) } ?: return null
        if (navigated) return null
        navigated = true
        val head = playhead
        val duration = head?.duration
        val nearEnd = head != null && duration != null && duration > 0 && head.position / duration >= NEAR_END
        save(isWatched = if (nearEnd || state.value.ended) true else null)
        return next.id
    }

    /**
     * Forgets this episode's progress. False when it failed, or there is no viewer
     * to forget it for. The screen moves the element back to 0:00.
     */
    suspend fun clearProgress(): Boolean {
        val viewer = viewerId() ?: return false
        return runCatching { catalog.clearProgress(viewer, episodeId) }
            .onSuccess {
                lastSaved = 0.0
                setState { copy(ended = false) }
            }
            .onFailure { Log.e("progress", "clear failed for $episodeId", it) }
            .isSuccess
    }

    /**
     * Records where the player is. Null when nothing was saved: no element, no
     * viewer, or under a second in with nothing else to record.
     *
     * Returns the save so a caller about to leave the page can wait for it.
     */
    fun save(isWatched: Boolean? = null, allowReset: Boolean = false): Job? {
        val head = playhead ?: return null
        if (catalog.byId(episodeId) == null) return null
        val viewer = viewerId() ?: return null
        val seconds = floor(head.position).toInt()
        if (seconds < 1 && isWatched == null && !allowReset) return null
        val duration = head.duration?.let { floor(it).toInt() }

        return scope.launch {
            runCatching {
                catalog.saveProgress(
                    uid = viewer,
                    episodeId = episodeId,
                    progressSeconds = if (allowReset) 0 else seconds,
                    durationSeconds = duration,
                    isWatched = isWatched,
                    allowReset = allowReset,
                )
            }.onFailure {
                // Logged: a silent rules or network problem looks exactly like
                // "the app forgot where I was".
                Log.e("progress", "save failed for $episodeId at ${seconds}s", it)
            }
        }
    }

    /** The screen is gone. Cancels the resolve and the retry; saves in flight still land. */
    fun close() {
        jobs.cancel()
    }
}

/** Makes the [PlaybackViewModel] for one watch screen. The graph knows the dependencies. */
fun interface PlaybackViewModelFactory {
    fun create(episodeId: String, maxQuality: Int?, appleMobile: Boolean): PlaybackViewModel
}
