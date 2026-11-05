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
`shared/src/commonMain/kotlin/ge/dakalebi/domain/service/VlcLink.kt`. Until
`VLC_PLAYLIST_ENDPOINT` there is set, "Open in VLC" links to the single file.

## Test

No dependencies. Needs Node 18+.

```bash
node --test
```

## Deploy

Free plan. The first time, log in to your own Cloudflare account in the browser:

```bash
npx wrangler login
```

Then deploy from this folder:

```bash
npx wrangler deploy
```

The address it prints, for example
`https://dakalebi-vlc.<your-subdomain>.workers.dev`, plus `/playlist.m3u`, goes into
`VLC_PLAYLIST_ENDPOINT`.
