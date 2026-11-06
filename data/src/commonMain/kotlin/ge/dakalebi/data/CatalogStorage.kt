package ge.dakalebi.data

import ge.dakalebi.domain.model.CaveaEpisode
import ge.dakalebi.domain.model.Episode

/**
 * Where the shared catalog is stored, as the admin refresh sees it. Firestore in the
 * app, a map in the tests.
 *
 * Nothing above `:data` sees this. They see the catalog repository, which runs
 * [CatalogRefresh] over it.
 */
internal interface CatalogStorage {
    /** Formula's catalog as stored. Throws when it cannot be read. */
    suspend fun formulaEpisodes(): List<Episode>

    /** Cavea Plus's list as the last refresh stored it. Empty when there is none or it cannot be read. */
    suspend fun storedCavea(): List<CaveaEpisode>

    /** Writes each of [episodes] over its stored copy, keeping the stored runtime. */
    suspend fun writeEpisodes(episodes: List<Episode>)

    /** Replaces the stored Cavea Plus list with [episodes]. */
    suspend fun storeCavea(episodes: List<CaveaEpisode>, nowMillis: Double)

    /**
     * Marks the catalog refreshed at [nowMillis]: the stamp every device's cached
     * catalog is checked against. [formula] is Formula's new size, or null when
     * Formula was not read this time and the stored size stands.
     */
    suspend fun markRefreshed(nowMillis: Double, formula: FormulaCounts?)
}

/** How big Formula's catalog is: what an older build shows from the refresh stamp's document. */
internal data class FormulaCounts(val seasons: Int, val episodes: Int)
