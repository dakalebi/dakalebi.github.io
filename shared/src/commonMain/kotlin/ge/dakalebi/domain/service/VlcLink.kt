package ge.dakalebi.domain.service

import ge.dakalebi.domain.model.Episode

/**
 * A link that opens [videoUrl] in VLC on an iPhone or iPad and starts playing it.
 *
 * VLC brings its own decoder, so it plays the files Safari's turns down: all of
 * season 2 is interlaced 1080i, which no browser on iOS can decode (see
 * [PlaybackFailure.UnsupportedOnAppleMobile]).
 *
 * The `stream` action plays at once. The plain `vlc://` form asks "download or
 * play?" first. VLC decodes the `url` value only when the whole link is
 * percent-encoded, which it detects by an encoded `://` near the start, so every
 * reserved character is encoded here, not just the ones that would break the query.
 */
fun vlcStreamUrl(videoUrl: String): String = "vlc-x-callback://x-callback-url/stream?url=${percentEncode(videoUrl)}"

/**
 * The playlist worker's `/playlist.m3u` address (`workers/vlc-playlist`), or null
 * while it is not deployed. Null keeps "Open in VLC" on the single-file link.
 */
val VLC_PLAYLIST_ENDPOINT: String? = "https://dakalebi-vlc.bachanamosulishvili.workers.dev/playlist.m3u"

/** The only host the playlist worker accepts links on. */
const val VLC_PLAYLIST_MEDIA_PREFIX = "https://cdn.formula.ge/"

/** How many episodes VLC queues after the one it was opened from. */
const val VLC_QUEUE_NEXT = 5

/** One entry of the playlist VLC is given. */
data class VlcPlaylistItem(val url: String, val title: String)

/**
 * What "Open in VLC" links to.
 *
 * With the worker configured, a playlist: [current] at [currentUrl], starting at
 * [startSeconds], then the next [VLC_QUEUE_NEXT] episodes of [upcoming] that have
 * a video, each named by [title]. VLC for iOS expands it into its play queue and
 * plays on by itself. Without the worker, or when [currentUrl] is somewhere the
 * worker would refuse, the plain link to [currentUrl], which always starts at 0:00.
 *
 * [upcoming] is the catalog in order after [current], so the titles carry the
 * catalog's own numbers, never ones read from a file path (season 15's "(5)" sits
 * in `episode_54`). Pass an empty list for the current episode alone. The next
 * episodes play in [quality] where they have it, else their stored best.
 */
fun vlcOpenUrl(
    current: Episode,
    currentUrl: String,
    upcoming: List<Episode>,
    quality: String?,
    startSeconds: Int,
    title: (Episode) -> String,
    endpoint: String? = VLC_PLAYLIST_ENDPOINT,
): String {
    if (endpoint == null || !currentUrl.startsWith(VLC_PLAYLIST_MEDIA_PREFIX)) return vlcStreamUrl(currentUrl)
    val next = upcoming.asSequence()
        .mapNotNull { episode ->
            // A link the worker would refuse fails the whole playlist, so it is left out.
            val url = quality?.let { episode.sources[it] } ?: episode.videoUrl
            url?.takeIf { it.startsWith(VLC_PLAYLIST_MEDIA_PREFIX) }?.let { VlcPlaylistItem(it, title(episode)) }
        }
        .take(VLC_QUEUE_NEXT)
        .toList()
    return vlcPlaylistUrl(endpoint, listOf(VlcPlaylistItem(currentUrl, title(current))) + next, startSeconds)
}

/**
 * The worker's playlist for [items], opened in VLC through [vlcStreamUrl].
 *
 * `u` and `t` repeat in pairs, which the worker reads back in order, and `start`
 * applies to the first item only. Every value is percent-encoded, raw spaces in
 * Formula's file names included, so the worker's own link survives being encoded
 * again inside VLC's.
 */
fun vlcPlaylistUrl(endpoint: String, items: List<VlcPlaylistItem>, startSeconds: Int): String {
    val query = buildList {
        if (startSeconds >= 1) add("start=$startSeconds")
        for (item in items) {
            add("u=${percentEncode(item.url)}")
            add("t=${percentEncode(item.title)}")
        }
    }.joinToString("&")
    return vlcStreamUrl("$endpoint?$query")
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
private const val HEX = "0123456789ABCDEF"

/** RFC 3986 percent-encoding of everything outside the unreserved set, as UTF-8. */
private fun percentEncode(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        if (value < 0x80 && char in UNRESERVED) {
            append(char)
        } else {
            append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
    }
}
