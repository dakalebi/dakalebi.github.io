/**
 * Serves the playlist that "Open in VLC" hands to VLC on an iPhone or iPad.
 *
 * VLC for iOS (3.7.3 and later) expands a playlist into its play queue, so one
 * link can queue the episode being watched and the ones after it, each under its
 * own title, with the first starting where the viewer is. VLC 3 cannot read a
 * `data:` URL, so the playlist has to come from an https address. This is that
 * address, and nothing more:
 *
 *   GET /playlist.m3u?start=754&u=<mp4>&t=<title>&u=<mp4>&t=<title>…
 *
 * It keeps no state. The site puts the whole list in the query and this answers
 * with the same list as M3U, in the shape tested on a real iPhone: `#EXTINF`
 * for the title and `#EXTVLCOPT:start-time` on the first item only.
 *
 * Only links on cdn.formula.ge are accepted, so it cannot be used to serve a
 * playlist of anyone else's files. It never touches the video itself.
 */

export const PLAYLIST_PATH = '/playlist.m3u';
export const MEDIA_HOST = 'cdn.formula.ge';
export const MAX_ITEMS = 20;
export const MAX_TITLE_LENGTH = 120;
/** A day. No episode comes close; anything longer is a broken link. */
export const MAX_START_SECONDS = 86_400;

/** Input the playlist cannot be built from. Answered with a 400. */
export class PlaylistError extends Error {}

/**
 * The M3U text for a query, or a thrown `PlaylistError`.
 *
 * `u` and `t` repeat in pairs: the n-th title names the n-th link. `start` is
 * whole seconds and applies to the first item only.
 */
export function buildPlaylist(params) {
  const urls = params.getAll('u');
  const titles = params.getAll('t');
  if (urls.length === 0) throw new PlaylistError('no items');
  if (urls.length > MAX_ITEMS) throw new PlaylistError(`more than ${MAX_ITEMS} items`);
  if (titles.length !== urls.length) throw new PlaylistError('each link needs one title');
  const start = startSeconds(params.get('start'));

  const lines = ['#EXTM3U'];
  urls.forEach((raw, index) => {
    const title = cleanTitle(titles[index]);
    if (title) lines.push(`#EXTINF:-1,${title}`);
    if (index === 0 && start > 0) lines.push(`#EXTVLCOPT:start-time=${start}`);
    lines.push(mediaUrl(raw));
  });
  return `${lines.join('\n')}\n`;
}

/**
 * The link as VLC should fetch it, or a thrown `PlaylistError` when it is not an
 * https link on `MEDIA_HOST`.
 *
 * Re-serialising through `URL` percent-encodes what Formula leaves raw, such as
 * the spaces in some season 16 file names, keeps what is already encoded, and
 * drops tabs and line breaks, so a link cannot add lines to the playlist.
 */
function mediaUrl(raw) {
  let url;
  try {
    url = new URL(raw);
  } catch {
    throw new PlaylistError('not a link');
  }
  const allowed = url.protocol === 'https:' &&
    url.hostname === MEDIA_HOST &&
    url.port === '' &&
    url.username === '' &&
    url.password === '';
  if (!allowed) throw new PlaylistError(`only https links on ${MEDIA_HOST}`);
  url.hash = '';
  return url.href;
}

/** One line of plain text: no control characters, so no extra playlist lines. */
function cleanTitle(raw) {
  const flat = raw.replace(/[\p{Cc}\p{Zl}\p{Zp}]/gu, ' ').replace(/\s+/g, ' ').trim();
  return Array.from(flat).slice(0, MAX_TITLE_LENGTH).join('');
}

function startSeconds(raw) {
  if (raw === null || raw === '') return 0;
  if (!/^\d{1,6}$/.test(raw)) throw new PlaylistError('start must be whole seconds');
  const seconds = Number(raw);
  if (seconds > MAX_START_SECONDS) throw new PlaylistError('start is too large');
  return seconds;
}

function plain(status, message, headers = {}) {
  return new Response(`${message}\n`, {
    status,
    headers: { 'content-type': 'text/plain; charset=utf-8', ...headers },
  });
}

export default {
  async fetch(request) {
    const { pathname, searchParams } = new URL(request.url);
    if (pathname !== PLAYLIST_PATH) return plain(404, 'not found');
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return plain(405, 'method not allowed', { allow: 'GET, HEAD' });
    }

    let body;
    try {
      body = buildPlaylist(searchParams);
    } catch (error) {
      if (error instanceof PlaylistError) return plain(400, error.message);
      throw error;
    }

    return new Response(request.method === 'HEAD' ? null : body, {
      headers: {
        // What GitHub Pages served for the .m3u that passed the iPhone test. VLC
        // reads it as UTF-8 because the text is valid UTF-8.
        'content-type': 'audio/x-mpegurl',
        // The answer depends on nothing but the URL.
        'cache-control': 'public, max-age=600',
        'x-content-type-options': 'nosniff',
      },
    });
  },
};
