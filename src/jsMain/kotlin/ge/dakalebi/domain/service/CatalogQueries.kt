package ge.dakalebi.domain.service

import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.WatchProgress

/**
 * The questions the screens ask about a catalog, as pure functions.
 *
 * These used to be computed properties on the state holder, which meant the
 * only way to check that "continue watching" picks the right episode was to
 * run the app, sign in, and look. They depend on nothing but their arguments,
 * so they are the part of this codebase most worth having tests for — and now
 * they can have them.
 */
object CatalogQueries {

    fun seasons(episodes: List<Episode>): List<Int> =
        episodes.map { it.seasonNumber }.distinct().sorted()

    fun season(episodes: List<Episode>, number: Int): List<Episode> =
        episodes.filter { it.seasonNumber == number }.sortedBy { it.episodeNumber }

    fun byId(episodes: List<Episode>, id: String): Episode? =
        episodes.firstOrNull { it.id == id }

    /** The episode after this one, crossing into the next season when needed. */
    fun next(episodes: List<Episode>, of: Episode): Episode? =
        episodes.filter { it.ordinal > of.ordinal }.minByOrNull { it.ordinal }

    /** The [count] episodes before this one, oldest first. */
    fun previous(episodes: List<Episode>, of: Episode, count: Int): List<Episode> =
        episodes.filter { it.ordinal < of.ordinal }
            .sortedByDescending { it.ordinal }
            .take(count)
            .reversed()

    fun upcoming(episodes: List<Episode>, of: Episode, count: Int): List<Episode> =
        episodes.filter { it.ordinal > of.ordinal }.sortedBy { it.ordinal }.take(count)

    /**
     * What the hero offers.
     *
     * The most recent episode left unfinished, falling back to the most recent
     * activity of any kind, then to the very first episode for a brand-new
     * account. The five-second floor on `progressSeconds` matters: without it,
     * a stray `timeupdate` at 0:02 from an episode someone opened by accident
     * outranks the one they actually watched last night.
     */
    fun continueWatching(
        episodes: List<Episode>,
        progress: Map<String, WatchProgress>,
    ): Episode? {
        val recent = progress.values.sortedByDescending { it.lastWatchedAtMillis }
        val unfinished = recent.firstOrNull { !it.isWatched && it.progressSeconds > 5 }
        val chosen = unfinished ?: recent.firstOrNull()
        return chosen?.let { byId(episodes, it.episodeId) }
            ?: episodes.minByOrNull { it.ordinal }
    }

    /**
     * Season the dashboard should open on: wherever the viewer last was,
     * falling back to the newest season.
     *
     * Skips progress rows whose episode is no longer in the catalog rather than
     * giving up at the first one — a refresh that drops an episode should not
     * send someone back to season 1.
     */
    fun defaultSeason(
        episodes: List<Episode>,
        progress: Map<String, WatchProgress>,
    ): Int? {
        val recent = progress.values.sortedByDescending { it.lastWatchedAtMillis }
        for (entry in recent) {
            val episode = byId(episodes, entry.episodeId)
            if (episode != null) return episode.seasonNumber
        }
        return seasons(episodes).lastOrNull()
    }
}

/** Headline numbers for the drawer. */
data class WatchStats(val watched: Int, val started: Int, val total: Int) {
    val percent: Int get() = if (total == 0) 0 else (watched * 100) / total

    companion object {
        fun of(episodes: List<Episode>, progress: Map<String, WatchProgress>) = WatchStats(
            watched = progress.values.count { it.isWatched },
            started = progress.values.count { it.isStarted },
            total = episodes.size,
        )
    }
}
