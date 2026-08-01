package ge.dakalebi.data.firebase

import ge.dakalebi.core.Log
import ge.dakalebi.data.firebase.externals.DocumentSnapshot
import ge.dakalebi.data.firebase.externals.QueryDocumentSnapshot
import ge.dakalebi.data.firebase.externals.collection
import ge.dakalebi.data.firebase.externals.doc
import ge.dakalebi.data.firebase.externals.getDoc
import ge.dakalebi.data.firebase.externals.getDocs
import ge.dakalebi.data.firebase.externals.setDoc
import ge.dakalebi.data.firebase.externals.writeBatch
import ge.dakalebi.data.formula.FormulaApi
import ge.dakalebi.data.formula.FormulaEpisode
import ge.dakalebi.data.formula.bestVideoUrl
import ge.dakalebi.data.formula.episodePageUrl
import ge.dakalebi.data.formula.qualitySources
import ge.dakalebi.data.formula.thumbnailUrl
import ge.dakalebi.domain.model.Catalog
import ge.dakalebi.domain.model.CatalogMeta
import ge.dakalebi.domain.model.CatalogUnavailableException
import ge.dakalebi.domain.model.Episode
import ge.dakalebi.domain.model.RefreshResult
import ge.dakalebi.domain.repository.CatalogCache
import ge.dakalebi.domain.repository.CatalogRepository
import kotlinx.coroutines.await

/**
 * The shared episode catalog: Firestore for storage, Formula for the source of
 * truth.
 *
 * Both providers sit behind one repository because they are two halves of the
 * same fact. Splitting them would only push the "fetch from one, diff against
 * the other" logic up a layer, where it would need both anyway.
 */
class FirestoreCatalogRepository(
    private val api: FormulaApi,
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
        Log.d("catalog", "from Firestore (${fresh.size} reads)")
        cache.write(stamp, fresh)
        return Catalog(episodes = fresh, meta = meta)
    }

    override suspend fun listEpisodes(): List<Episode> {
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
     * Pulls the whole catalog from Formula and writes back only what changed.
     *
     * Runs entirely in the browser — the Formula API is CORS-open, so no server
     * or Cloud Function is involved.
     */
    override suspend fun refresh(
        nowMillis: Double,
        onProgress: (done: Int, total: Int) -> Unit,
    ): RefreshResult {
        Log.d("refresh", "reading existing catalog")
        val existing = listEpisodes().associateBy { it.id }
        Log.d("refresh", "existing=${existing.size}, fetching seasons")
        val seasons = api.fetchSeasons()
        Log.d("refresh", "seasons=${seasons.size}")

        val fresh = mutableListOf<Episode>()
        seasons.forEachIndexed { index, season ->
            val episodes = api.fetchSeasonEpisodes(season.id)
            episodes.mapTo(fresh) { it.toEpisode(season.seasonNumber, season.id, nowMillis) }
            onProgress(index + 1, seasons.size)
        }

        val changed = fresh.filter { candidate ->
            existing[candidate.id]?.sameContentAs(candidate) != true
        }

        Log.d("refresh", "fetched=${fresh.size}, changed=${changed.size}, writing")
        for (chunk in changed.chunked(BATCH_LIMIT)) {
            val batch = writeBatch(Firebase.db)
            for (episode in chunk) {
                batch.set(doc(Firebase.db, EPISODES, episode.id), episode.toJs(), mergeOption())
            }
            batch.commit().await()
        }

        val meta = jsObject()
        meta.lastRefreshAtMillis = nowMillis
        meta.seasonCount = seasons.size
        meta.episodeCount = fresh.size
        setDoc(doc(Firebase.db, META, CATALOG), meta).await()

        val sorted = fresh.sortedBy { it.ordinal }
        cache.write(nowMillis, sorted)

        return RefreshResult(
            seasons = seasons.size,
            episodes = fresh.size,
            written = changed.size,
            withoutVideo = fresh.count { !it.hasVideo },
            catalog = sorted,
        )
    }

    /**
     * Fetches the current video URL for one episode straight from Formula.
     *
     * The original app cached this in the database for 24 hours purely to save
     * server round-trips. With the call happening in the browser there is no
     * such cost, so we always resolve fresh and fall back to the stored URL.
     */
    override suspend fun resolveVideo(episode: Episode): Episode = runCatching<Episode> {
        val fresh = api.fetchEpisode(episode.formulaEpisodeId)
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
     * Only admins may write the `episodes` collection, so `permission-denied`
     * here is the normal path for everyone else and stays at debug. Anything
     * else is worth seeing.
     */
    override suspend fun recordDuration(episodeId: String, durationSeconds: Int) {
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

    private fun FormulaEpisode.toEpisode(
        seasonNumber: Int,
        formulaSeasonId: Int,
        nowMillis: Double,
    ): Episode {
        val sources = qualitySources()
        val video = sources.values.firstOrNull()
        return Episode(
            id = id.toString(),
            formulaEpisodeId = id,
            formulaSeasonId = formulaSeasonId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            title = displayTitle,
            thumbnailUrl = thumbnailUrl(),
            videoUrl = video,
            sources = sources,
            durationSeconds = null,
            episodePageUrl = episodePageUrl(id),
            lastResolvedAtMillis = if (video != null) nowMillis else null,
            updatedAtMillis = nowMillis,
        )
    }

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

        /** Firestore hard limit; a full refresh is ~932 docs, so we chunk. */
        const val BATCH_LIMIT = 450
    }
}
