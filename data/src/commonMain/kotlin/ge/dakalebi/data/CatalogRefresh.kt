package ge.dakalebi.data

import ge.dakalebi.core.Log
import ge.dakalebi.data.cavea.CaveaApi
import ge.dakalebi.data.formula.FormulaApi
import ge.dakalebi.data.formula.FormulaApiException
import ge.dakalebi.data.formula.toEpisode
import ge.dakalebi.data.local.CatalogCache
import ge.dakalebi.domain.model.CaveaEpisode
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.service.mergeCavea
import kotlin.coroutines.cancellation.CancellationException

/**
 * The admin refresh: Formula and Cavea Plus each fetched, and the catalog rebuilt from
 * whatever each one gave. Neither source can stop the other.
 *
 * - Formula unreachable, unreadable, or answering with nothing: the stored episodes
 *   stand for it, and none is written. Cavea Plus's list is still fetched, stored and
 *   joined to them.
 * - Cavea Plus the same: its stored list stands.
 * - Both: the refresh fails with Formula's error. Nothing is new, so nothing is
 *   written, the stamp included.
 *
 * A failure part way through Formula's seasons counts as Formula failing. Its answer
 * is used whole or not at all, so the stored catalog is never mixed with half of one.
 *
 * Runs entirely in the browser: both APIs are CORS-open, so no server or Cloud
 * Function is involved.
 */
internal class CatalogRefresh(
    private val formula: FormulaApi,
    private val cavea: CaveaApi,
    private val storage: CatalogStorage,
    private val cache: CatalogCache,
) {
    suspend operator fun invoke(nowMillis: Double, onProgress: (done: Int, total: Int) -> Unit): RefreshResult {
        Log.d("refresh", "reading existing catalog")
        val existing = storage.formulaEpisodes()
        Log.d("refresh", "existing=${existing.size}, fetching seasons")
        val formulaAnswer = fetchFormula(nowMillis, onProgress)
        val fresh = formulaAnswer.getOrNull()

        val stored = existing.associateBy { it.id }
        val changed = fresh?.episodes.orEmpty().filter { stored[it.id]?.sameContentAs(it) != true }
        if (fresh != null) {
            Log.d("refresh", "fetched=${fresh.episodes.size}, changed=${changed.size}, writing")
            storage.writeEpisodes(changed)
        }

        // After Formula's writes and before the stamp: the stamp is what tells every
        // device its cached catalog is stale, and the catalog includes Cavea's list.
        val caveaFetched = refreshCavea(nowMillis)
        val formulaError = formulaAnswer.exceptionOrNull()
        if (formulaError != null && caveaFetched == null) throw formulaError
        val caveaList = caveaFetched ?: storage.storedCavea()

        storage.markRefreshed(nowMillis, fresh?.let { FormulaCounts(it.seasons, it.episodes.size) })

        val catalog = joinCavea(afterWrite(existing, changed), caveaList)
        cache.write(nowMillis, catalog)

        val formulaNow = fresh?.episodes ?: existing
        return RefreshResult(
            seasons = fresh?.seasons ?: existing.distinctBy { it.seasonNumber }.size,
            episodes = formulaNow.size,
            written = if (fresh != null) changed.size else null,
            withoutVideo = formulaNow.count { !it.hasVideo },
            catalog = catalog,
            caveaEpisodes = caveaFetched?.size,
            caveaOnly = catalog.count { it.isCaveaOnly },
        )
    }

    /** Formula's whole catalog, or why it could not be had. Never fails the refresh on its own. */
    private suspend fun fetchFormula(
        nowMillis: Double,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Result<FormulaAnswer> = try {
        val seasons = formula.fetchSeasons()
        Log.d("refresh", "seasons=${seasons.size}")
        // The series has had seasons and episodes for years. None is a broken answer,
        // and taking it at its word would record a catalog of nothing.
        if (seasons.isEmpty()) throw FormulaApiException("Formula lists no seasons")
        val episodes = mutableListOf<Episode>()
        seasons.forEachIndexed { index, season ->
            formula.fetchSeasonEpisodes(season.id).mapTo(episodes) {
                it.toEpisode(season.seasonNumber, season.id, nowMillis)
            }
            onProgress(index + 1, seasons.size)
        }
        if (episodes.isEmpty()) throw FormulaApiException("Formula lists no episodes")
        Result.success(FormulaAnswer(seasons.size, episodes))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w("refresh", "Formula unavailable, keeping the stored episodes", e)
        Result.failure(e)
    }

    /**
     * Fetches Cavea Plus's list and stores it. Returns it, or null when it could not be
     * fetched or stored: the refresh then keeps the stored list.
     */
    private suspend fun refreshCavea(nowMillis: Double): List<CaveaEpisode>? {
        Log.d("refresh", "fetching Cavea Plus's list")
        val list = try {
            cavea.fetchEpisodes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("refresh", "Cavea Plus's list unavailable, keeping the stored one", e)
            return null
        }
        return try {
            storage.storeCavea(list, nowMillis)
            Log.d("refresh", "Cavea Plus: ${list.size} episodes stored")
            list
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("refresh", "could not store Cavea Plus's list, keeping the stored one", e)
            null
        }
    }

    /**
     * What the store holds once [changed] is written over [existing]: each written
     * episode keeps its stored runtime, and nothing is removed. So this device's
     * cached copy is the same catalog every other device reads.
     */
    private fun afterWrite(existing: List<Episode>, changed: List<Episode>): List<Episode> {
        val written = changed.associateBy { it.id }
        val updated = existing.map { old -> written[old.id]?.copy(durationSeconds = old.durationSeconds) ?: old }
        val known = existing.mapTo(HashSet()) { it.id }
        return updated + changed.filter { it.id !in known }
    }

    private class FormulaAnswer(val seasons: Int, val episodes: List<Episode>)
}

/** [mergeCavea], saying when Cavea lists the same numbers twice. */
internal fun joinCavea(formula: List<Episode>, cavea: List<CaveaEpisode>): List<Episode> {
    val repeats = cavea.size - cavea.distinctBy { it.seasonNumber to it.episodeNumber }.size
    if (repeats > 0) Log.w("catalog", "Cavea Plus lists $repeats episode numbers twice; the first of each is used")
    return mergeCavea(formula, cavea)
}
