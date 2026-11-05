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

/** The most items the playlist worker accepts. A longer list would get no playlist at all. */
const val VLC_PLAYLIST_MAX_ITEMS = 200

/** One entry of the playlist VLC is given. */
data class VlcPlaylistItem(val url: String, val title: String)

/**
 * What "Open in VLC" links to.
 *
 * With the worker configured, a playlist: [current] at [currentUrl], starting at
 * [startSeconds], then every episode of [upcoming] that has a video, each named by
 * [title]. VLC for iOS expands it into its play queue and plays on by itself.
 * Without the worker, or when [currentUrl] is somewhere the worker would refuse,
 * the plain link to [currentUrl], which always starts at 0:00.
 *
 * [upcoming] comes from the catalog in order (the watch page passes the rest of
 * the season), so the titles carry the catalog's own numbers, never ones read from
 * a file path (season 15's "(5)" sits in `episode_54`). Pass an empty list for the
 * current episode alone. The next episodes play in [quality] where they have it,
 * else their stored best.
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
        .take(VLC_PLAYLIST_MAX_ITEMS - 1)
        .toList()
    return vlcPlaylistUrl(endpoint, listOf(VlcPlaylistItem(currentUrl, title(current))) + next, startSeconds)
}

/**
 * The worker's playlist for [items], opened in VLC through [vlcStreamUrl].
 *
 * `u` and `t` repeat in pairs, which the worker reads back in order, and `start`
 * applies to the first item only. A season is dozens of items, so what they all
 * share goes once: `base`, the folder every link starts with, and `title`, the
 * words every title shares around a `{}` that each `t` fills. That keeps a whole
 * season to a few kilobytes instead of twenty. Every value is percent-encoded,
 * raw spaces in Formula's file names included, so the worker's own link survives
 * being encoded again inside VLC's.
 */
fun vlcPlaylistUrl(endpoint: String, items: List<VlcPlaylistItem>, startSeconds: Int): String {
    val base = sharedFolder(items.map { it.url })
    val titles = sharedTitles(items.map { it.title })
    val query = buildList {
        if (startSeconds >= 1) add("start=$startSeconds")
        if (base != null) add("base=${percentEncode(base)}")
        if (titles != null) add("title=${percentEncode(titles.template)}")
        items.forEachIndexed { index, item ->
            add("u=${percentEncode(item.url.removePrefix(base.orEmpty()))}")
            add("t=${percentEncode(titles?.parts?.get(index) ?: item.title)}")
        }
    }.joinToString("&")
    return vlcStreamUrl("$endpoint?$query")
}

/** The folder all of [urls] start with, ending in `/`, or null when there is less than a host to share. */
private fun sharedFolder(urls: List<String>): String? {
    if (urls.size < 2) return null
    val prefix = urls.reduce { shared, url -> shared.commonPrefixWith(url) }
    val folder = prefix.substring(0, prefix.lastIndexOf('/') + 1)
    // At least `https://host/`, so a link is never cut inside its scheme or host.
    return folder.takeIf { it.count { char -> char == '/' } >= 3 }
}

/** The worker's `{}`: where each `t` goes in the shared title. */
private const val TITLE_SLOT = "{}"

/** Titles as one [template] around [TITLE_SLOT], plus the [parts] that fill it in turn. */
private class SharedTitles(val template: String, val parts: List<String>)

/**
 * "სეზონი 2 · სერია {}" with "9", "10", "11". Null when the titles share nothing,
 * or when one already holds a `{}`. `commonPrefixWith` and `commonSuffixWith`
 * never split a surrogate pair.
 */
private fun sharedTitles(titles: List<String>): SharedTitles? {
    if (titles.size < 2 || titles.any { TITLE_SLOT in it }) return null
    val prefix = titles.reduce { shared, title -> shared.commonPrefixWith(title) }
    val rests = titles.map { it.substring(prefix.length) }
    val suffix = rests.reduce { shared, rest -> shared.commonSuffixWith(rest) }
    if (prefix.isEmpty() && suffix.isEmpty()) return null
    return SharedTitles(prefix + TITLE_SLOT + suffix, rests.map { it.dropLast(suffix.length) })
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
