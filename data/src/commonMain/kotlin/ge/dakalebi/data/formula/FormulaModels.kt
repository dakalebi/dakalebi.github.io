package ge.dakalebi.data.formula

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shapes returned by Formula's public middleware API. Only the fields we
 * actually use are declared; the payloads carry a lot more (ad campaigns,
 * nested series metadata, promos) and are ignored.
 */
@Serializable
data class FormulaSeason(
    val id: Int,
    /** Zero-padded season label, e.g. "01". Note this is NOT [id]. */
    val seasonId: String,
    val title: String? = null,
    val customTitle: String? = null,
    val imageURL: String? = null,
    val enabled: Boolean = true,
    val status: String? = null,
) {
    val seasonNumber: Int get() = seasonId.trimStart('0').toIntOrNull() ?: 0
}

@Serializable
data class FormulaSourceEntry(val key: String, val value: String)

@Serializable
data class FormulaEpisode(
    val id: Int,
    /** Episode label within the season, e.g. "14". */
    val episodeId: String,
    val title: String? = null,
    val customTitle: String? = null,
    val customFullTitle: String? = null,
    val videoFhdSrc: String? = null,
    val videoHdSrc: String? = null,
    val videoSdSrc: String? = null,
    val videoOriginalSrc: String? = null,
    @SerialName("videoSRC") val videoSrc: String? = null,
    val videoThumbnailSrc: String? = null,
    val imageURL: String? = null,
    val originalImageURL: String? = null,
    val sourceList: List<FormulaSourceEntry> = emptyList(),
    val enabled: Boolean = true,
    val status: String? = null,
) {
    // Deliberately NOT declared: `skipIntroSeconds`, `thumbnailSeconds`,
    // `orderId` and `viewsCount`. Formula types these inconsistently — some
    // episodes return skipIntroSeconds as a quoted decimal ("57.173437"),
    // which fails to parse as a number and aborts the whole season. They are
    // unused, so letting ignoreUnknownKeys drop them is both simpler and
    // immune to whatever type the API returns next.

    val episodeNumber: Int get() = episodeId.trimStart('0').toIntOrNull() ?: 0

    /** Best human-readable title, or null if Formula has none. */
    val displayTitle: String? get() = title?.ifBlank { null } ?: customTitle?.ifBlank { null }
}

/**
 * Playback qualities, best first.
 *
 * Verified against the live API: [FormulaEpisode.videoFhdSrc] is the 1080p
 * rendition, [FormulaEpisode.videoHdSrc] is 720p and [FormulaEpisode.videoSdSrc]
 * is 360p. The original TypeScript app preferred `videoHdSrc` over
 * `videoFhdSrc`, so it silently served 720p even where 1080p existed.
 */
enum class VideoQuality(val label: String) {
    FHD("1080p"),
    HD("720p"),
    SD("360p"),
}

/**
 * Quality label -> URL, best first, de-duplicated.
 *
 * Prefers [FormulaEpisode.sourceList] (which Formula labels explicitly) and
 * falls back to the flat `video*Src` fields for older seasons.
 */
fun FormulaEpisode.qualitySources(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val fromList = sourceList.associate { it.key to it.value }

    for (quality in VideoQuality.entries) {
        val url = fromList[quality.label] ?: when (quality) {
            VideoQuality.FHD -> videoFhdSrc
            VideoQuality.HD -> videoHdSrc
            VideoQuality.SD -> videoSdSrc
        }
        if (!url.isNullOrBlank()) out[quality.label] = url
    }

    // Anything Formula labelled that we don't have a canonical bucket for.
    for ((key, url) in fromList) {
        if (key !in out && url.isNotBlank()) out[key] = url
    }

    if (out.isEmpty()) {
        val fallback = videoOriginalSrc?.ifBlank { null } ?: videoSrc?.ifBlank { null }
        if (fallback != null) out["original"] = fallback
    }
    return out
}

/** Highest-quality playable URL, or null when Formula has no video for it. */
fun FormulaEpisode.bestVideoUrl(): String? = qualitySources().values.firstOrNull()

/**
 * Poster image, largest first.
 *
 * Surveyed across all 932 episodes: every one carries [imageURL], but it is a
 * 220x124 thumbnail. 28 also carry [originalImageURL] at 1280x720. So
 * [imageURL] is the right *fallback* — it is the one that is always there — and
 * the wrong first choice, which is what it used to be.
 *
 * [videoThumbnailSrc] is deliberately absent: despite the name it is an `.mp4`
 * (e.g. `cdn.formula.ge/trimmer/THUMBNAIL/.../….mp4`), so putting it in an
 * `<img>` chain only produces a broken image. It was unreachable before purely
 * because [imageURL] always won.
 *
 * The original app's HTML-scraping fallback stays unnecessary — and is
 * impossible from a browser anyway, since `tv.formula.ge` sends no CORS
 * headers.
 */
fun FormulaEpisode.thumbnailUrl(): String? =
    listOf(originalImageURL, imageURL).firstOrNull { !it.isNullOrBlank() }

/** Public watch page on Formula, used as the "open on Formula" fallback. */
fun episodePageUrl(formulaEpisodeId: Int): String =
    "https://tv.formula.ge/tvseries/player/$formulaEpisodeId/main"
