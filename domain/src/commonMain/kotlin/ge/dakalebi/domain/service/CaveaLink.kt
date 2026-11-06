package ge.dakalebi.domain.service

/**
 * Cavea Plus's watch page for the series, as the site itself links to an episode.
 *
 * `/ka/` always: it is the path the site uses, and whether `/en/` exists cannot be
 * told from outside (the site answers any path). The apostrophe in the slug is
 * encoded, so the link survives being pasted or read off a QR code.
 */
const val CAVEA_WATCH_PAGE = "https://cavea.plus/ka/watch/my-wife%27s-girlfriends"

/**
 * The link that opens [caveaEpisodeId] on Cavea Plus.
 *
 * A link and nothing more: Cavea's streams are DRM-protected and play only on its
 * own site, after its own sign-in. The episode is free there with an account.
 */
fun caveaWatchUrl(caveaEpisodeId: String): String = "$CAVEA_WATCH_PAGE?episodeId=${percentEncode(caveaEpisodeId)}"
