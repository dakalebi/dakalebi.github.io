package ge.dakalebi.presentation

import ge.dakalebi.domain.model.Episode

/** How many episodes the queue lists after the one playing. */
const val QUEUE_LENGTH = 10

data class NowPlayingUiState(
    /** The episode loaded into the player, or null when nothing is. */
    val episodeId: String? = null,
    /** That episode's playback. Null exactly when [episodeId] is. */
    val playback: PlaybackViewModel? = null,
)

/**
 * The episode the web app plays, on every screen.
 *
 * The web app keeps playing under a mini-player while the viewer browses, so the
 * episode and its [PlaybackViewModel] live here, as long as the app, not as long as
 * a screen. (The TV and driving-mode players are pages, and do not use this.)
 *
 * Whether the full player is open is the route's business: a watch route is open,
 * anything else shows the mini-player. One source of truth, so Back closes the
 * player and a copied link opens it. The screen tells this which episode a watch
 * route names through [play]. When this moves to another episode while the player
 * is open ([skipToNext], [skipToPrevious], a queue row), it replaces the route, so
 * the address names what plays and Back still closes the player rather than
 * stepping through episodes.
 *
 * One per viewer, from `rememberNowPlaying`: the playback saves under whoever is
 * watching, so a new viewer gets a new holder.
 */
class NowPlayingViewModel(
    private val catalog: CatalogStore,
    private val router: Router,
    private val createPlayback: (episodeId: String) -> PlaybackViewModel,
) : ViewModel<NowPlayingUiState>(NowPlayingUiState()) {

    /** The episode playing, once the catalog knows it. */
    fun episode(state: NowPlayingUiState): Episode? = state.episodeId?.let(catalog::byId)

    /** What plays after the episode playing, in order, across seasons. */
    fun queue(state: NowPlayingUiState): List<Episode> =
        episode(state)?.let { catalog.upcoming(it, QUEUE_LENGTH) }.orEmpty()

    /**
     * Makes [episodeId] the episode playing. The same episode again keeps its
     * playback, so a route that names it twice does not restart it. Another one
     * first saves where the viewer was in the last one.
     */
    fun play(episodeId: String) = switchTo(episodeId, saveLeaving = true)

    /**
     * Moves to the next episode, across seasons. Counts the one playing as watched
     * when the viewer leaves near its end, or after it ended. False when there is
     * no next episode, or nothing is playing.
     */
    fun skipToNext(): Boolean {
        // Saves the one playing itself, with what leaving for the next one means.
        val next = state.value.playback?.leaveForNext() ?: return false
        switchTo(next, saveLeaving = false)
        return true
    }

    /**
     * Moves to the episode before the one playing, keeping the spot in this one.
     * False on the first episode, or when nothing is playing. Whether a press of
     * "previous" restarts the episode first is the player's call.
     */
    fun skipToPrevious(): Boolean {
        val current = episode(state.value) ?: return false
        val previous = catalog.previous(current, 1).firstOrNull() ?: return false
        play(previous.id)
        return true
    }

    /**
     * Closes the player: saves where the viewer was and lets the episode go. If the
     * full player is open, the route leaves it for the dashboard, since a watch
     * route with nothing loaded would draw an empty player.
     */
    fun stop() {
        val leaving = state.value.playback ?: return
        release(leaving, save = true)
        setState { NowPlayingUiState() }
        if (router.current is Route.Watch) router.replace(Route.Dashboard)
    }

    /**
     * Lets the episode go without saving, for when the viewer has changed: a
     * playback reads the viewer at each save, so a save now would land under the
     * new one. The route is left alone; the new viewer's holder reads it.
     */
    fun close() {
        state.value.playback?.let { release(it, save = false) }
        setState { NowPlayingUiState() }
    }

    private fun switchTo(episodeId: String, saveLeaving: Boolean) {
        val current = state.value
        if (current.episodeId == episodeId) return
        current.playback?.let { release(it, save = saveLeaving) }
        val playback = createPlayback(episodeId)
        setState { NowPlayingUiState(episodeId = episodeId, playback = playback) }
        val route = router.current
        if (route is Route.Watch && route.episodeId != episodeId) router.replace(Route.Watch(episodeId))
    }

    /**
     * Saves first, while the element still shows this episode, then detaches it:
     * the element stays and loads the next episode, and a late `timeupdate` must
     * not save that one's time under this one.
     */
    private fun release(playback: PlaybackViewModel, save: Boolean) {
        if (save) playback.save()
        playback.attach(null)
        playback.close()
    }
}
