package ge.dakalebi.domain.service

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
