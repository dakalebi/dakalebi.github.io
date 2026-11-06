package ge.dakalebi.data.firebase

import ge.dakalebi.core.Log
import ge.dakalebi.data.CatalogRefresh
import ge.dakalebi.data.CatalogStorage
import ge.dakalebi.data.FormulaCounts
import ge.dakalebi.data.cavea.CaveaApi
import ge.dakalebi.data.firebase.externals.DocumentSnapshot
import ge.dakalebi.data.firebase.externals.QueryDocumentSnapshot
import ge.dakalebi.data.firebase.externals.collection
import ge.dakalebi.data.firebase.externals.doc
import ge.dakalebi.data.firebase.externals.getDoc
import ge.dakalebi.data.firebase.externals.getDocs
import ge.dakalebi.data.firebase.externals.setDoc
import ge.dakalebi.data.firebase.externals.writeBatch
import ge.dakalebi.data.formula.FormulaApi
import ge.dakalebi.data.formula.bestVideoUrl
import ge.dakalebi.data.formula.episodePageUrl
import ge.dakalebi.data.formula.qualitySources
import ge.dakalebi.data.formula.thumbnailUrl
import ge.dakalebi.data.joinCavea
import ge.dakalebi.data.local.CatalogCache
import ge.dakalebi.domain.model.CaveaEpisode
import ge.dakalebi.domain.model.Catalog
import ge.dakalebi.domain.model.CatalogMeta
import ge.dakalebi.domain.model.CatalogUnavailableException
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.repository.CatalogRepository
import ge.dakalebi.domain.service.CAVEA_ONLY_ID_PREFIX
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.await

/**
 * The shared episode catalog: Firestore for storage, Formula for the source of
 * truth, and Cavea Plus for the episodes Formula lacks.
 *
 * Both providers sit behind one repository because they are two halves of the
 * same fact. Splitting them would only push the "fetch from one, diff against
 * the other" logic up a layer, where it would need both anyway.
 *
 * Cavea's list is stored apart from the catalog, in the one document
 * `meta/cavea`, and joined with it on every load ([joinCavea]). Never in
 * `episodes`: every build of the app, production included, reads each document
 * there as a Formula episode, so a Cavea-only one would show up broken in a build
 * that knows nothing about Cavea. `meta/cavea` is read by this build only.
 */
class FirestoreCatalogRepository(
    private val api: FormulaApi,
    private val cavea: CaveaApi,
    private val cache: CatalogCache,
) : CatalogRepository {

    /**
     * Metadata first, because its refresh stamp is what says whether the
     * cached catalog is still current. One document read then stands in for
     * 932 whenever nothing has changed.
     *
     * Losing the metadata must not fail the load — it is also the drawer's
     * "last refreshed" line — but it should say so: a permission error here
     * means the rules are wrong for `meta/catalog` too. It does cost the
     * cache, since without a stamp nothing can be validated.
     */
    override suspend fun load(): Catalog {
        val meta = runCatching { getMeta() }
            .onFailure { Log.w("catalog", "metadata unavailable", it) }
            .getOrNull()
        val stamp = meta?.lastRefreshAtMillis

        cache.read(stamp)?.let {
            Log.d("catalog", "from cache (${it.size} episodes, 1 read)")
            return Catalog(episodes = it, meta = meta)
        }

        val fresh = listEpisodes()
        Log.d("catalog", "from Firestore (${fresh.size} episodes)")
        cache.write(stamp, fresh)
        return Catalog(episodes = fresh, meta = meta)
    }

    /** Formula's episodes from `episodes`, with Cavea Plus's stored list joined in. */
    override suspend fun listEpisodes(): List<Episode> = joinCavea(formulaEpisodes(), storedCavea())

    /** What `episodes` holds: Formula's catalog alone. */
    private suspend fun formulaEpisodes(): List<Episode> {
        val snapshot = getDocs(collection(Firebase.db, EPISODES)).await()
        // An unreachable backend does not reject — Firestore quietly answers
        // from its (empty) cache. Treated as success that renders the "no
        // episodes yet" screen, which tells a viewer to wait for an admin who
        // has nothing to do.
        if (snapshot.empty && snapshot.metadata.fromCache) throw CatalogUnavailableException()
        return snapshot.docs.map { it.toEpisode() }.sortedBy { it.ordinal }
    }

    override suspend fun getMeta(): CatalogMeta? {
        val snapshot = getDoc(doc(Firebase.db, META, CATALOG)).await()
        if (!snapshot.exists()) return null
        val data = snapshot.data()
        return CatalogMeta(
            lastRefreshAtMillis = dynDouble(data.lastRefreshAtMillis),
            seasonCount = dynInt(data.seasonCount) ?: 0,
            episodeCount = dynInt(data.episodeCount) ?: 0,
        )
    }

    /**
     * Rebuilds the catalog from Formula and Cavea Plus and writes back only what
     * changed. Either source failing leaves its stored part standing; see
     * [CatalogRefresh] for the rules.
     */
    override suspend fun refresh(
        nowMillis: Double,
        onProgress: (done: Int, total: Int) -> Unit,
    ): RefreshResult = CatalogRefresh(api, cavea, storage, cache)(nowMillis, onProgress)

    /** The refresh's view of Firestore. Not the repository itself, so none of it is public. */
    private val storage = object : CatalogStorage {
        override suspend fun formulaEpisodes() = this@FirestoreCatalogRepository.formulaEpisodes()

        override suspend fun storedCavea() = this@FirestoreCatalogRepository.storedCavea()

        override suspend fun writeEpisodes(episodes: List<Episode>) {
            for (chunk in episodes.chunked(BATCH_LIMIT)) {
                val batch = writeBatch(Firebase.db)
                for (episode in chunk) {
                    batch.set(doc(Firebase.db, EPISODES, episode.id), episode.toJs(), mergeOption())
                }
                batch.commit().await()
            }
        }

        override suspend fun storeCavea(episodes: List<CaveaEpisode>, nowMillis: Double) {
            setDoc(doc(Firebase.db, META, CAVEA), episodes.toCaveaDocument(nowMillis)).await()
        }

        /**
         * Formula's counts, as before: what an old build shows from this document. Kept
         * as stored when Formula was not read, by writing the stamp alone into it.
         */
        override suspend fun markRefreshed(nowMillis: Double, formula: FormulaCounts?) {
            val meta = jsObject()
            meta.lastRefreshAtMillis = nowMillis
            if (formula == null) {
                setDoc(doc(Firebase.db, META, CATALOG), meta, mergeOption()).await()
                return
            }
            meta.seasonCount = formula.seasons
            meta.episodeCount = formula.episodes
            setDoc(doc(Firebase.db, META, CATALOG), meta).await()
        }
    }

    // -------------------------------------------------------------- Cavea Plus

    /**
     * The Cavea Plus list the last refresh stored. Empty when there is none yet, or it
     * cannot be read: the catalog is then Formula's alone, which is what it was before
     * Cavea, so this never fails a load.
     */
    private suspend fun storedCavea(): List<CaveaEpisode> = try {
        val snapshot = getDoc(doc(Firebase.db, META, CAVEA)).await()
        if (snapshot.exists()) readCaveaDocument(snapshot.data()) else emptyList()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w("catalog", "Cavea Plus's stored list unavailable", e)
        emptyList()
    }

    private fun List<CaveaEpisode>.toCaveaDocument(nowMillis: Double): dynamic {
        val out = jsObject()
        out.fetchedAtMillis = nowMillis
        out.episodes = map { episode ->
            val entry = jsObject()
            entry.id = episode.id
            entry.season = episode.seasonNumber
            entry.episode = episode.episodeNumber
            entry.thumbnail = episode.thumbnailUrl
            entry
        }.toTypedArray()
        return out
    }

    private fun readCaveaDocument(data: dynamic): List<CaveaEpisode> {
        val raw: Any? = data.episodes
        if (raw !is Array<*>) return emptyList()
        return raw.unsafeCast<Array<dynamic>>().mapNotNull { entry ->
            val id = dynString(entry.id) ?: return@mapNotNull null
            val season = dynInt(entry.season) ?: return@mapNotNull null
            val number = dynInt(entry.episode) ?: return@mapNotNull null
            CaveaEpisode(id = id, seasonNumber = season, episodeNumber = number, thumbnailUrl = dynString(entry.thumbnail))
        }
    }

    /**
     * Fetches the current video URL for one episode straight from Formula.
     *
     * The original app cached this in the database for 24 hours purely to save
     * server round-trips. With the call happening in the browser there is no
     * such cost, so we always resolve fresh and fall back to the stored URL.
     */
    override suspend fun resolveVideo(episode: Episode): Episode {
        // Only on Cavea Plus: there is nothing to ask Formula about.
        val formulaId = episode.formulaEpisodeId ?: return episode
        return resolveFromFormula(episode, formulaId)
    }

    private suspend fun resolveFromFormula(episode: Episode, formulaId: Int): Episode = runCatching<Episode> {
        val fresh = api.fetchEpisode(formulaId)
        val sources = fresh.qualitySources()
        val url = fresh.bestVideoUrl() ?: return@runCatching episode
        episode.copy(
            videoUrl = url,
            sources = sources.ifEmpty { episode.sources },
            title = fresh.displayTitle ?: episode.title,
            thumbnailUrl = fresh.thumbnailUrl() ?: episode.thumbnailUrl,
        )
    }.onFailure {
        // Not fatal — the stored URL usually still plays — but it is the first
        // thing to check when an episode suddenly refuses to load.
        Log.w("episodes", "live resolve failed for ${episode.id}, using stored URL", it)
    }.getOrDefault(episode)

    /**
     * This device's cache first, for everyone: it is what the next page load
     * reads, and it costs nothing.
     *
     * Only admins may write the `episodes` collection, so `permission-denied`
     * here is the normal path for everyone else and stays at debug. Anything
     * else is worth seeing.
     */
    override suspend fun recordDuration(episodeId: String, durationSeconds: Int) {
        cache.recordDuration(episodeId, durationSeconds)
        // An `episodes` document for a Cavea-only episode would reach production as a
        // broken Formula episode. RecordEpisodeDuration does not ask; this holds anyway.
        if (episodeId.startsWith(CAVEA_ONLY_ID_PREFIX)) return
        val payload = jsObject()
        payload.durationSeconds = durationSeconds
        runCatching {
            setDoc(doc(Firebase.db, EPISODES, episodeId), payload, mergeOption()).await()
        }.onFailure {
            val expected = Log.codeOf(it) == "permission-denied"
            val message = "duration write skipped for $episodeId"
            if (expected) Log.d("episodes", "$message (not an admin)")
            else Log.w("episodes", message, it)
        }
    }

    // ------------------------------------------------------------- mapping

    private fun QueryDocumentSnapshot.toEpisode(): Episode = readEpisode(id, data())

    private fun DocumentSnapshot.toEpisode(): Episode = readEpisode(id, data())

    private fun readEpisode(docId: String, data: dynamic): Episode = Episode(
        id = docId,
        formulaEpisodeId = dynInt(data.formulaEpisodeId) ?: docId.toIntOrNull() ?: 0,
        formulaSeasonId = dynInt(data.formulaSeasonId) ?: 0,
        seasonNumber = dynInt(data.seasonNumber) ?: 0,
        episodeNumber = dynInt(data.episodeNumber) ?: 0,
        title = dynString(data.title),
        thumbnailUrl = dynString(data.thumbnailUrl),
        videoUrl = dynString(data.videoUrl),
        sources = dynStringMap(data.sources),
        durationSeconds = dynInt(data.durationSeconds),
        episodePageUrl = dynString(data.episodePageUrl)
            ?: episodePageUrl(docId.toIntOrNull() ?: 0),
        lastResolvedAtMillis = dynDouble(data.lastResolvedAtMillis),
        updatedAtMillis = dynDouble(data.updatedAtMillis),
    )

    private fun Episode.toJs(): dynamic {
        val out = jsObject()
        out.formulaEpisodeId = formulaEpisodeId
        out.formulaSeasonId = formulaSeasonId
        out.seasonNumber = seasonNumber
        out.episodeNumber = episodeNumber
        out.title = title
        out.thumbnailUrl = thumbnailUrl
        out.videoUrl = videoUrl
        out.episodePageUrl = episodePageUrl
        out.lastResolvedAtMillis = lastResolvedAtMillis
        out.updatedAtMillis = updatedAtMillis

        val sourceMap = jsObject()
        for ((quality, url) in sources) sourceMap[quality] = url
        out.sources = sourceMap

        // durationSeconds is deliberately omitted: Formula does not expose it,
        // so it is only ever learned from the player and must not be clobbered.
        return out
    }

    private companion object {
        const val EPISODES = "episodes"
        const val META = "meta"
        const val CATALOG = "catalog"

        /** Cavea Plus's list, in `meta`: public read, admin write, like `meta/catalog`. */
        const val CAVEA = "cavea"

        /** Firestore hard limit; a full refresh is ~932 docs, so we chunk. */
        const val BATCH_LIMIT = 450
    }
}
