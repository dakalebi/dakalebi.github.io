package ge.dakalebi.web.firebase

import ge.dakalebi.core.Log
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
import kotlin.js.get

/**
 * The shared episode catalog on wasmJs: Firestore for storage, Formula for the source of
 * truth.
 *
 * The wasm twin of the root app's `FirestoreCatalogRepository` — same collections, same
 * Firebase project, same rules — so the 2.0 app and the live app read and write one catalog.
 * Both providers sit behind one repository because they are two halves of the same fact:
 * splitting them would only push "fetch from one, diff against the other" up a layer, where
 * it would need both anyway.
 */
class WasmCatalogRepository(
    private val api: FormulaApi,
    private val cache: CatalogCache,
) : CatalogRepository {

    /**
     * Metadata first, because its refresh stamp is what says whether the cached catalog is
     * still current. One document read then stands in for 932 whenever nothing has changed.
     *
     * Losing the metadata must not fail the load — it is also the drawer's "last refreshed"
     * line — but it should say so: a permission error here means the rules are wrong for
     * `meta/catalog` too. It does cost the cache, since without a stamp nothing can be
     * validated.
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
        val snapshot = getDocs(collection(FirebaseWasm.db, EPISODES)).await()
        // An unreachable backend does not reject — Firestore quietly answers from its
        // (empty) cache. Treated as success that renders the "no episodes yet" screen,
        // which tells a viewer to wait for an admin who has nothing to do.
        if (snapshot.empty && snapshot.metadata.fromCache) throw CatalogUnavailableException()

        val docs = snapshot.docs
        val episodes = ArrayList<Episode>(docs.length)
        for (index in 0 until docs.length) {
            val document = docs[index] ?: continue
            episodes += readEpisode(document.id, document.data())
        }
        return episodes.sortedBy { it.ordinal }
    }

    override suspend fun getMeta(): CatalogMeta? {
        val snapshot = getDoc(doc(FirebaseWasm.db, "$META/$CATALOG")).await()
        val data = snapshot.data().takeIf { snapshot.exists() } ?: return null
        return CatalogMeta(
            lastRefreshAtMillis = readDouble(data, "lastRefreshAtMillis"),
            seasonCount = readInt(data, "seasonCount") ?: 0,
            episodeCount = readInt(data, "episodeCount") ?: 0,
        )
    }

    /**
     * Pulls the whole catalog from Formula and writes back only what changed.
     *
     * Runs entirely in the browser — the Formula API is CORS-open, so no server or Cloud
     * Function is involved.
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
            val batch = writeBatch(FirebaseWasm.db)
            for (episode in chunk) {
                batch.set(episodeRef(episode.id), episode.toJs(), setOptions(merge = true))
            }
            batch.commit().await()
        }

        val meta = newObject()
        putDouble(meta, "lastRefreshAtMillis", nowMillis)
        putInt(meta, "seasonCount", seasons.size)
        putInt(meta, "episodeCount", fresh.size)
        setDoc(doc(FirebaseWasm.db, "$META/$CATALOG"), meta, setOptions(merge = false)).await()

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
     * The original app cached this in the database for 24 hours purely to save server
     * round-trips. With the call happening in the browser there is no such cost, so we
     * always resolve fresh and fall back to the stored URL.
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
        // Not fatal — the stored URL usually still plays — but it is the first thing to
        // check when an episode suddenly refuses to load.
        Log.w("episodes", "live resolve failed for ${episode.id}, using stored URL", it)
    }.getOrDefault(episode)

    /**
     * Only admins may write the `episodes` collection, so `permission-denied` here is the
     * normal path for everyone else and stays at debug. Anything else is worth seeing.
     */
    override suspend fun recordDuration(episodeId: String, durationSeconds: Int) {
        val payload = newObject()
        putInt(payload, "durationSeconds", durationSeconds)
        runCatching {
            setDoc(episodeRef(episodeId), payload, setOptions(merge = true)).await()
        }.onFailure {
            val expected = Log.codeOf(it) == "permission-denied"
            val message = "duration write skipped for $episodeId"
            if (expected) Log.d("episodes", "$message (not an admin)")
            else Log.w("episodes", message, it)
        }
    }

    // ------------------------------------------------------------------------ mapping

    private fun episodeRef(episodeId: String) = doc(FirebaseWasm.db, "$EPISODES/$episodeId")

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

    /**
     * A document that does not exist reads as an episode with no fields rather than being
     * skipped, matching the js(IR) reader: `listEpisodes` only ever passes real query
     * documents, and the ordinal keeps an empty one out of the way.
     */
    private fun readEpisode(docId: String, data: JsAny?): Episode = Episode(
        id = docId,
        formulaEpisodeId = data?.let { readInt(it, "formulaEpisodeId") }
            ?: docId.toIntOrNull() ?: 0,
        formulaSeasonId = data?.let { readInt(it, "formulaSeasonId") } ?: 0,
        seasonNumber = data?.let { readInt(it, "seasonNumber") } ?: 0,
        episodeNumber = data?.let { readInt(it, "episodeNumber") } ?: 0,
        title = data?.let { readString(it, "title") },
        thumbnailUrl = data?.let { readString(it, "thumbnailUrl") },
        videoUrl = data?.let { readString(it, "videoUrl") },
        sources = data?.let { readStringMap(it, "sources") } ?: emptyMap(),
        durationSeconds = data?.let { readInt(it, "durationSeconds") },
        episodePageUrl = data?.let { readString(it, "episodePageUrl") }
            ?: episodePageUrl(docId.toIntOrNull() ?: 0),
        lastResolvedAtMillis = data?.let { readDouble(it, "lastResolvedAtMillis") },
        updatedAtMillis = data?.let { readDouble(it, "updatedAtMillis") },
    )

    private fun Episode.toJs(): JsAny {
        val out = newObject()
        putInt(out, "formulaEpisodeId", formulaEpisodeId)
        putInt(out, "formulaSeasonId", formulaSeasonId)
        putInt(out, "seasonNumber", seasonNumber)
        putInt(out, "episodeNumber", episodeNumber)
        putStringOrNull(out, "title", title)
        putStringOrNull(out, "thumbnailUrl", thumbnailUrl)
        putStringOrNull(out, "videoUrl", videoUrl)
        putString(out, "episodePageUrl", episodePageUrl)
        putDoubleOrNull(out, "lastResolvedAtMillis", lastResolvedAtMillis)
        putDoubleOrNull(out, "updatedAtMillis", updatedAtMillis)

        val sourceMap = newObject()
        for ((quality, url) in sources) putString(sourceMap, quality, url)
        putObject(out, "sources", sourceMap)

        // durationSeconds is deliberately omitted: Formula does not expose it, so it is
        // only ever learned from the player and must not be clobbered.
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
