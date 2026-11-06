package ge.dakalebi.data.cavea

import ge.dakalebi.domain.model.CaveaEpisode
import kotlinx.serialization.Serializable

/**
 * What the refresh reads of Cavea Plus's series answer, and nothing more.
 *
 * The answer also carries the cast, ratings, prices and per-episode intro marks;
 * none of it is read, and unknown keys are ignored, so Cavea adding a field cannot
 * break the refresh.
 */
@Serializable
internal data class CaveaSeries(
    val id: String = "",
    val episodes: List<CaveaEpisodeDto> = emptyList(),
)

@Serializable
internal data class CaveaEpisodeDto(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    /** "S1E1" for most episodes. Checked, never relied on. */
    val name: String? = null,
    /** A site path such as `/static/images/....jpg`. */
    val thumbnail: String? = null,
) {
    fun toDomain() = CaveaEpisode(
        id = id,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        thumbnailUrl = thumbnail?.takeIf { it.isNotBlank() }?.let(::absoluteCaveaUrl),
    )

    /** Whether the name agrees with the numbers, as "S{season}E{episode}". */
    val nameMatchesNumbers: Boolean get() = name?.trim() == "S${seasonNumber}E$episodeNumber"
}

/** Cavea answers with site paths; a link from this app's origin needs the host. */
internal fun absoluteCaveaUrl(path: String): String =
    if (path.startsWith("https://") || path.startsWith("http://")) path else CAVEA_ORIGIN + "/" + path.trimStart('/')

internal const val CAVEA_ORIGIN = "https://cavea.plus"
