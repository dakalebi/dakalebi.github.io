import assert from 'node:assert/strict';
import { test } from 'node:test';
import worker, { MAX_ITEMS, MAX_TITLE_LENGTH, buildPlaylist } from '../src/index.js';

const BASE = 'https://dakalebi-vlc.example.workers.dev/playlist.m3u';
const S2E9 = 'https://cdn.formula.ge/tvseries/series_1/season_02/episode_9/48e8_9.mp4';
const S2E10 = 'https://cdn.formula.ge/tvseries/series_1/season_02/episode_10/4f6d_10.mp4';

/** A query of `u`/`t` pairs, plus `start`, `base` and `title` when given. */
function query(items, start, shared = {}) {
  const params = new URLSearchParams();
  if (start !== undefined) params.append('start', String(start));
  if (shared.base !== undefined) params.append('base', shared.base);
  if (shared.title !== undefined) params.append('title', shared.title);
  for (const [url, title] of items) {
    params.append('u', url);
    params.append('t', title);
  }
  return params;
}

function get(search, method = 'GET', path = '/playlist.m3u') {
  return worker.fetch(new Request(`https://dakalebi-vlc.example.workers.dev${path}?${search}`, { method }));
}

test('titles every item and starts only the first one late', () => {
  assert.equal(
    buildPlaylist(query([[S2E9, 'სეზონი 2 · სერია 9'], [S2E10, 'სეზონი 2 · სერია 10']], 60)),
    [
      '#EXTM3U',
      '#EXTINF:-1,სეზონი 2 · სერია 9',
      '#EXTVLCOPT:start-time=60',
      S2E9,
      '#EXTINF:-1,სეზონი 2 · სერია 10',
      S2E10,
      '',
    ].join('\n'),
  );
});

test('leaves the start out when it is missing or zero', () => {
  for (const start of [undefined, 0]) {
    assert.doesNotMatch(buildPlaylist(query([[S2E9, 'a']], start)), /start-time/);
  }
});

test('percent-encodes raw spaces and keeps what is already encoded', () => {
  const raw = 'https://cdn.formula.ge/tvseries/series_1/season_16/episode_01/78d9_CHCD SE16 E 1 HD.mp4';
  const encoded = 'https://cdn.formula.ge/tvseries/series_1/season_16/episode_01/78d9_CHCD%20SE16%20E%201%20HD.mp4';
  assert.equal(buildPlaylist(query([[raw, 'a']])).split('\n')[2], encoded);
  assert.equal(buildPlaylist(query([[encoded, 'a']])).split('\n')[2], encoded);
});

test('refuses every link that is not https on cdn.formula.ge', () => {
  const refused = [
    'http://cdn.formula.ge/a.mp4',
    'https://example.com/a.mp4',
    'https://cdn.formula.ge.example.com/a.mp4',
    'https://evil.example/cdn.formula.ge/a.mp4',
    'https://cdn.formula.ge@evil.example/a.mp4',
    'https://user@cdn.formula.ge/a.mp4',
    'https://cdn.formula.ge:8443/a.mp4',
    'javascript:alert(1)',
    '/tvseries/a.mp4',
    '',
  ];
  for (const url of refused) {
    assert.throws(() => buildPlaylist(query([[S2E9, 'ok'], [url, 'bad']])), /https|link/, url);
  }
});

test('a title or link cannot add lines to the playlist', () => {
  const text = buildPlaylist(query([[`${S2E9}\n#EXTVLCOPT:x=1`, 'one\r\n#EXTVLCOPT:x=1 two']]));
  assert.deepEqual(text.split('\n').filter((line) => line.startsWith('#EXTVLCOPT')), []);
  assert.equal(text.split('\n')[1], '#EXTINF:-1,one #EXTVLCOPT:x=1 two');
});

test('caps titles by characters, not by UTF-16 units', () => {
  const title = buildPlaylist(query([[S2E9, 'ს'.repeat(MAX_TITLE_LENGTH + 10)]])).split('\n')[1];
  assert.equal(title, `#EXTINF:-1,${'ს'.repeat(MAX_TITLE_LENGTH)}`);
});

test('an empty title leaves the item unnamed', () => {
  assert.equal(buildPlaylist(query([[S2E9, '  ']])), `#EXTM3U\n${S2E9}\n`);
});

test('refuses a query it cannot pair or bound', () => {
  assert.throws(() => buildPlaylist(new URLSearchParams()), /no items/);
  assert.throws(() => buildPlaylist(new URLSearchParams(`u=${encodeURIComponent(S2E9)}`)), /one title/);
  const tooMany = Array.from({ length: MAX_ITEMS + 1 }, () => [S2E9, 'a']);
  assert.throws(() => buildPlaylist(query(tooMany)), /more than/);
  for (const start of ['-1', '1.5', 'abc', '86401', '1234567']) {
    assert.throws(() => buildPlaylist(query([[S2E9, 'a']], start)), /start/, start);
  }
});

test('puts the shared folder in front of every link and fills the shared title', () => {
  const folder = 'https://cdn.formula.ge/tvseries/series_1/season_02/';
  assert.equal(
    buildPlaylist(query([['episode_9/48e8_9.mp4', '9'], ['episode_10/4f6d_10.mp4', '10']], 60, {
      base: folder,
      title: 'სეზონი 2 · სერია {}',
    })),
    buildPlaylist(query([[S2E9, 'სეზონი 2 · სერია 9'], [S2E10, 'სეზონი 2 · სერია 10']], 60)),
  );
});

test('fills only the first {} and takes the part literally', () => {
  const titles = buildPlaylist(query([[S2E9, '$&'], [S2E10, '{}']], undefined, { title: 'a {} b {}' }))
    .split('\n')
    .filter((line) => line.startsWith('#EXTINF'));
  assert.deepEqual(titles, ['#EXTINF:-1,a $& b {}', '#EXTINF:-1,a {} b {}']);
});

test('refuses a shared title without {}', () => {
  assert.throws(() => buildPlaylist(query([[S2E9, '9']], undefined, { title: 'სეზონი 2' })), /title needs/);
});

test('checks each link after the shared folder is put in front', () => {
  const refused = [
    ['https://example.com/', 'a.mp4'],
    ['https://cdn.formula.ge', '.example.com/a.mp4'],
    ['https://cdn.formula.ge', '@evil.example/a.mp4'],
    ['http://cdn.formula.ge/', 'a.mp4'],
  ];
  for (const [base, url] of refused) {
    assert.throws(() => buildPlaylist(query([[url, 'a']], undefined, { base })), /https|link/, base + url);
  }
});

test('serves the playlist as audio/x-mpegurl, the type VLC was tested with', async () => {
  const response = await get(query([[S2E9, 'a']], 60));
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('content-type'), 'audio/x-mpegurl');
  assert.match(await response.text(), /^#EXTM3U\n/);
});

test('answers a HEAD without a body', async () => {
  const response = await get(query([[S2E9, 'a']]), 'HEAD');
  assert.equal(response.status, 200);
  assert.equal(await response.text(), '');
});

test('answers 400, 404 and 405 where it should', async () => {
  assert.equal((await get(query([['https://example.com/a.mp4', 'a']]))).status, 400);
  assert.equal((await get(query([[S2E9, 'a']]), 'GET', '/other.m3u')).status, 404);
  const post = await get(query([[S2E9, 'a']]), 'POST');
  assert.equal(post.status, 405);
  assert.equal(post.headers.get('allow'), 'GET, HEAD');
});

/**
 * The link the site builds, character for character, as pinned by
 * `the_link_the_worker_test_reads` in shared/src/commonTest/.../VlcPlaylistTest.kt.
 */
const SITE_LINK = 'vlc-x-callback://x-callback-url/stream?url=' +
  'https%3A%2F%2Fdakalebi-vlc.example.workers.dev%2Fplaylist.m3u%3Fstart%3D754' +
  '%26base%3Dhttps%253A%252F%252Fcdn.formula.ge%252Ftvseries%252Fseries_1%252Fseason_16%252F' +
  '%26title%3D%25E1%2583%25A1%25E1%2583%2594%25E1%2583%2596%25E1%2583%259D%25E1%2583%259C%25E1%2583%2598' +
  '%252016%2520%25C2%25B7%2520%25E1%2583%25A1%25E1%2583%2594%25E1%2583%25A0%25E1%2583%2598%25E1%2583%2590%2520%257B%257D' +
  '%26u%3Depisode_01%252F78d9_CHCD%2520SE16%2520E%25201%2520HD.mp4%26t%3D1' +
  '%26u%3Depisode_02%252Fa1_2.mp4%26t%3D2';

/**
 * The same playlist as the first site build sent it, every link and title in
 * full. A page still open from before keeps working.
 */
const FIRST_SITE_LINK = 'vlc-x-callback://x-callback-url/stream?url=' +
  'https%3A%2F%2Fdakalebi-vlc.example.workers.dev%2Fplaylist.m3u%3Fstart%3D754' +
  '%26u%3Dhttps%253A%252F%252Fcdn.formula.ge%252Ftvseries%252Fseries_1%252Fseason_16%252Fepisode_01' +
  '%252F78d9_CHCD%2520SE16%2520E%25201%2520HD.mp4' +
  '%26t%3D%25E1%2583%25A1%25E1%2583%2594%25E1%2583%2596%25E1%2583%259D%25E1%2583%259C%25E1%2583%2598' +
  '%252016%2520%25C2%25B7%2520%25E1%2583%25A1%25E1%2583%2594%25E1%2583%25A0%25E1%2583%2598%25E1%2583%2590%25201' +
  '%26u%3Dhttps%253A%252F%252Fcdn.formula.ge%252Ftvseries%252Fseries_1%252Fseason_16%252Fepisode_02%252Fa1_2.mp4' +
  '%26t%3D%25E1%2583%25A1%25E1%2583%2594%25E1%2583%2596%25E1%2583%259D%25E1%2583%259C%25E1%2583%2598' +
  '%252016%2520%25C2%25B7%2520%25E1%2583%25A1%25E1%2583%2594%25E1%2583%25A0%25E1%2583%2598%25E1%2583%2590%25202';

/**
 * VLC for iOS (URLHandler.swift): split its own query on raw `&` and `=`, take
 * the `url` value, and percent-decode it once because it starts with an encoded
 * `://`. Then fetch what that names.
 */
async function openInVlc(link) {
  const value = link.split('?').slice(1).join('?').split('&')
    .map((entry) => entry.split('='))
    .find(([key]) => key === 'url')[1];
  const playlistUrl = decodeURIComponent(value);
  assert.ok(playlistUrl.startsWith(`${BASE}?`), playlistUrl);
  const response = await worker.fetch(new Request(playlistUrl));
  assert.equal(response.status, 200);
  return response.text();
}

/** What both links must give. */
const EXPECTED = [
  '#EXTM3U',
  '#EXTINF:-1,სეზონი 16 · სერია 1',
  '#EXTVLCOPT:start-time=754',
  'https://cdn.formula.ge/tvseries/series_1/season_16/episode_01/78d9_CHCD%20SE16%20E%201%20HD.mp4',
  '#EXTINF:-1,სეზონი 16 · სერია 2',
  'https://cdn.formula.ge/tvseries/series_1/season_16/episode_02/a1_2.mp4',
  '',
].join('\n');

for (const [name, link] of [['site', SITE_LINK], ['first site build', FIRST_SITE_LINK]]) {
  test(`the ${name}'s link, opened the way VLC opens it, gives the expected playlist`, async () => {
    assert.equal(await openInVlc(link), EXPECTED);
  });
}
