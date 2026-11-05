# VLC playlist worker

A Cloudflare Worker that turns a query into an M3U playlist for "Open in VLC" on
iPhone and iPad. VLC for iOS 3.7.3+ expands the playlist into its queue, so the
viewer gets the current episode and the rest of its season, with titles,
starting at their current position.

```
GET /playlist.m3u?start=754&base=<folder>&title=<words {} words>&u=<mp4>&t=<title>…
→ 200 audio/x-mpegurl

#EXTM3U
#EXTINF:-1,სეზონი 2 · სერია 9
#EXTVLCOPT:start-time=754
https://cdn.formula.ge/…/9.mp4
#EXTINF:-1,სეზონი 2 · სერია 10
https://cdn.formula.ge/…/10.mp4
```

- **Stateless.** The site sends the whole list. Nothing is stored, and the video
  never passes through the worker.
- **Short links.** A season is dozens of items, so what they all share goes once.
  `base` goes in front of every `u`, and each `t` fills the `{}` in `title`. Both
  are optional, and a link without them still works. Up to 200 items.
- **Only `https://cdn.formula.ge` links.** Each link is checked after `base` is
  put in front. Anything else is a 400, so the worker cannot serve playlists of
  other sites' files.
- **Why it exists.** VLC 3 cannot read a `data:` URL, and GitHub Pages cannot
  build a file per request.

The site builds the link in
`shared/src/commonMain/kotlin/ge/dakalebi/domain/service/VlcLink.kt`.
`VLC_PLAYLIST_ENDPOINT` there points at the deployed worker,
`https://dakalebi-vlc.bachanamosulishvili.workers.dev/playlist.m3u`. With it set to
`null`, or for a link that is not on the CDN, "Open in VLC" falls back to the
single file, which starts at 0:00 and has no queue.

## Test

No dependencies. Needs Node 18+.

```bash
node --test
```

One test decodes the exact link that `VlcPlaylistTest` in `:shared` pins, so a
change to the link format has to pass on both sides. CI runs neither, so run both
by hand.

## Deploy

Nothing deploys the worker automatically. The live one runs on the free plan in
the owner's Cloudflare account, and a change here reaches it only when someone
with access deploys it. The first time, log in to your Cloudflare account in the
browser:

```bash
npx wrangler login
```

Then deploy from this folder:

```bash
npx wrangler deploy
```

From the owner's account this updates the live address. From another account it
prints a new one, `https://dakalebi-vlc.<your-subdomain>.workers.dev`; that
address plus `/playlist.m3u` would then have to go into `VLC_PLAYLIST_ENDPOINT`.
