# Project guide

Written for someone — or some agent — arriving with no context. It covers what
this is, what the owner asked for, which decisions were made and why, how each
piece works, and the traps that have already cost real time.

Companion documents:

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — the layering and the rule that holds it
- [`TEST-PLAN.md`](TEST-PLAN.md) — the manual test plan
- [`TV-NATIVE-APP.md`](TV-NATIVE-APP.md), [`TV-NAVIGATION.md`](TV-NAVIGATION.md) — the Android TV app, and how the remote moves focus
- [`BUG-REPORT.md`](BUG-REPORT.md), [`BUG-REPORT-2.md`](BUG-REPORT-2.md) — defects found and fixed, with root causes
- [`../workers/vlc-playlist/README.md`](../workers/vlc-playlist/README.md) — the VLC playlist worker

---

## Contents

1. [What this is](#1-what-this-is)
2. [Requirements the owner set](#2-requirements-the-owner-set)
3. [Decisions, and why](#3-decisions-and-why)
4. [How it works, subsystem by subsystem](#4-how-it-works-subsystem-by-subsystem)
5. [Firestore: model, rules, quota](#5-firestore-model-rules-quota)
6. [Build, test, deploy](#6-build-test-deploy)
7. [Traps that have already cost time](#7-traps-that-have-already-cost-time)
8. [What is verified, and what is not](#8-what-is-verified-and-what-is-not)
9. [Current state and open work](#9-current-state-and-open-work)
10. [How the owner likes to work](#10-how-the-owner-likes-to-work)

---

## 1. What this is

A private dashboard for watching one Georgian TV series, **ჩემი ცოლის დაქალები**
("My Wife's Girlfriends"), which runs to 932 episodes across 18 seasons.
It tracks what you have watched and where you stopped, and plays the official
video.

It is one person's tool. There is no growth plan, no second tenant, and the
whole point of several decisions below is that it costs nothing to run.

Three front ends share one bundle:

| Path | For |
|---|---|
| `/` | phone and desktop browsers: dashboard, watch page, menu |
| `/tv/` | a television, driven by a remote. The Android TV app in `tv/` is a WebView on it |
| `/drive/` | driving mode: a phone in a car mount, read at a glance, pressed with one thumb |

You can sign in, so progress and settings follow you to every device, or continue
as a guest, whose progress and settings stay on that device.

**It is a rewrite, not a new idea.** The original is a TanStack Start + Supabase
app at `../ch-ts-d.lovable app`. That project is **not** modified — it is the
feature-parity reference. When a behaviour here is unclear ("how did resume
work?", "when does the next-episode prompt appear?"), read the corresponding
file there rather than guessing. Several of its quirks are deliberate bug fixes
carrying comments that explain them.

Live at <https://dakalebi.github.io/>.

---

## 2. Requirements the owner set

Explicit instructions, not inferences. Do not quietly revisit these.

### Framing the rewrite

- **Kotlin**, not TypeScript.
- **Deployable to GitHub Pages** — therefore fully static, no backend of any
  kind.
- **Firebase**, not Supabase.
- Everything else must **behave as the original does**.

### Product

- **Admin rights must not be a hardcoded email.** Their words: *"hardcoded email
  for admin is not much correct. How about we add a parameter or something in
  firestore that the user is admin or not."*
- **Autoplay must sync between devices**, hence Firestore rather than
  `localStorage`.
- **No autoplay on the web or in driving mode, for now.** Their words: *"Remove
  auto play, it's not smooth and we can think about it later."* TV keeps its
  autoplay and its switch.
- **Language must be switchable and must sync between devices.**
- **On Apple devices only**, a switch between the native player and the web
  player, **default on**. Originally per device in `localStorage`; it now
  follows the account like language and autoplay, so an iPhone and an iPad
  agree. No other device offers or reads it.
- **Every setting syncs between devices by default**, including ones added
  later. Preferred quality syncs too, by the owner's choice. Only what
  describes a screen rather than a person (TV interface size) stays on the
  device.
- **Every user-facing string extracted** into something like Android's
  `strings.xml`, so other languages can be added.
- **Georgian set in Mtavruli** (uppercase Georgian: `ქართული` → `ᲥᲐᲠᲗᲣᲚᲘ`) for
  UI chrome, and a distinct font for times and numbers.
- **Application version, plus deployment date and time, visible in the drawer.**
- **No zoom on any page.** First "restrict zooming on small devices", then
  widened to the whole website: `/`, `/drive/` and `/tv/`.
- **Watch-screen action buttons full width on mobile.**
- **Tab title while watching**: `სეზონი X ⋅ სერია XX - ჩცდ`. (The owner reordered
  this after the first attempt put the initials first — keep their order.)
- **Guest mode**: watch without an account, on the web and on TV. The catalog is
  publicly readable for it; guest progress stays on the device. Guests see a Guest
  badge and Sign in; members also get "Continue as guest" in the menu.
- **Driving mode plays video in the native player, with bigger buttons.** A first,
  audio-only version was rejected in their words: *"The episode should play in
  native player, just the buttons should be bigger."* They also asked for Mark as
  watched and Open in VLC there, and a way into it from the episode page.

### Design direction

- **Apple's UX**: rounded corners, capsule buttons, no underlined links, nothing
  "rough".
- **Netflix-like tiles**: bigger, with the hero smaller relative to them.
- **YouTube-like watch page**: smaller player, next episodes to its right,
  previous episodes below it.
- **Previous episodes**: exactly the last four, oldest to newest, left to right,
  **not scrollable**.

### Engineering

- **Log every caught error.** Their words: *"add logging to all error catching
  logic so that next time we will easily discover the issue if it appears."*
  This is why `runCatching` blocks here always log — several real bugs were
  found only because of it.
- **Clean Architecture**, and when offered a pragmatic middle option they chose
  the full version: use-case classes, no singletons, everything injected.

---

## 3. Decisions, and why

### Compose HTML, not Compose for Wasm

The app needs a real `<video>` element, and on iPhone/iPad it must hand playback
to Apple's own player. A canvas-rendered UI cannot do either. Compose HTML
renders to the DOM, so a `<video>` tag is just a `<video>` tag.

### No backend at all

GitHub Pages serves static files. Everything else — auth, data, the catalog
fetch — happens in the browser. Consequences that reach everywhere:

- **The Firestore rules are the entire security boundary.** There is no server
  to check anything. Treat `firebase/firestore.rules` as production code.
- **The Formula API is called straight from the browser.** This is only possible
  because it answers with `Access-Control-Allow-Origin: *` (verified, including
  the OPTIONS preflight). Send no custom headers: the original server-side
  client set `User-Agent`/`Origin`/`Referer`, all forbidden to scripts, and any
  non-safelisted header forces a preflight the API does not advertise
  `Access-Control-Allow-Headers` for.
- **Fresh episode URLs have five seconds.** The watch screen asks the Formula
  API for an episode's current URLs before it shows the player, and plays the
  stored URL when that fails. `fetchEpisode` aborts after
  `EPISODE_TIMEOUT_MILLIS`, so an API outage costs a few seconds rather than a
  player that never appears. The abort goes through an `AbortSignal`, which
  adds no header.
- **One exception: the VLC playlist.** On iPhone and iPad, "Open in VLC" can
  queue the rest of the season with titles and a start time, but only through an M3U
  served over https: VLC 3 reads no `data:` URL. `workers/vlc-playlist` is a
  stateless Cloudflare Worker that echoes the site's list back as M3U, for
  `cdn.formula.ge` links only. It stores nothing and never carries video. With
  `VLC_PLAYLIST_ENDPOINT` set to null, the button falls back to the single file,
  so the site still works without the worker. See
  [iOS playback and VLC](#ios-playback-and-vlc).
- **The catalog is public.** `episodes` and `meta` are readable without an
  account, so guests can browse. They hold a copy of what Formula's public API
  already serves. The cost is that reads no longer need an account, so they count
  against the shared daily quota without one; the catalog cache keeps a returning
  visitor to one read of `meta/catalog`.

### Hash routing

`#/watch/531`. GitHub Pages has no SPA rewrite, so this is the only scheme that
survives a hard refresh and a project sub-path.

### Firestore document ids are Formula's episode ids

The original Supabase schema used a random UUID with `formula_episode_id` as a
side column, which meant watch URLs changed whenever the catalog was rebuilt.
Keying on Formula's id makes `#/watch/531` permanent.

### An org user-site, not a project page

The site lives at `dakalebi.github.io` — a GitHub **organisation** named
`dakalebi` owning a repo called `dakalebi.github.io`. The owner wanted a clean
URL; a personal account can only have one user-site, and theirs was taken. An
org gives another one free.

### Admin rights in Firestore, not in the bundle

Originally an allowlist of UIDs and emails in `FirebaseConfig.kt`, duplicated
into the rules. Two problems: the owner objected on principle, and it had
already locked them out — the email branch required `email_verified`, and an
account created in the Firebase console is not verified, so moving off Google
sign-in silently removed their own admin rights.

The replacement is the existence of `admins/{uid}`: readable only by the account
it names, **writable by nobody**. That write-deny is the design. A flag on
`users/{uid}` cannot work, because that document must be writable by its owner
for their settings — so an `isAdmin` field on it is a field the user can set on
themselves.

A custom auth claim is the conventional answer, but setting one needs the Admin
SDK: a service account and a server this app deliberately does not have.

### Manual dependency injection

The repositories, and a use case per operation, wired by hand in
`di/AppGraph.kt`. A
container would add a dependency and a layer of indirection to save one page of
unambiguous constructor calls.

### Tests on Node, not in a browser

`:core`, `:domain`, `:presentation` and the `commonMain` half of `:data` have no browser
in them, so `./gradlew allTests` runs their tests on Node in about a second.
Standing up Karma and a headless Chrome to assert things about sorting would be
slower and more fragile for no gain. The browser-facing code is tested by hand —
see §8.

That split is now Gradle modules rather than a convention, so the property is
enforced by the compiler: no module depends on the root, `:domain` depends on no
other module and on one library (kotlinx-coroutines, for `Flow`), and `:data`
cannot see presentation or `i18n`. From `commonMain`,
the browser is reachable only through three `expect` functions. See
[`ARCHITECTURE.md`](ARCHITECTURE.md).

### The cache serialises a DTO, not the domain model

`data/local/LocalCatalogCache.kt` maps `Episode` to a `CachedEpisode`. Putting
`@Serializable` on the domain model would drag a framework into the one layer
documented as framework-free, and would tie the on-disk format to a model
rename — so renaming a field would silently invalidate, or worse mis-read,
every cache in the wild.

### i18n as a Kotlin interface

`Strings` is an interface; `Ka` and `En` implement it. The compiler then
enforces what an XML file cannot: a locale that forgets a string does not build,
and a string that no longer exists cannot linger in a translation. Anything
containing a number or a name is a function, not a template, so a translator can
put the placeholder where their grammar needs it.

---

## 4. How it works, subsystem by subsystem

### Startup

`Main.kt` is the only place that constructs anything:

1. `Log.installGlobalHandlers()` first, so a crash during the rest of startup is
   still reported.
2. With a fixture URL (`?ui=demo`, `?ui=tv-demo`, `?ui=drive-demo`), build
   `fixtureGraph()` instead and never touch Firebase.
3. Otherwise, if `FirebaseConfig.isConfigured` is false, render `SetupNotice()`
   and stop — touching Firebase before the config is filled in throws on init.
   If it is configured, build `AppGraph()`, which constructs every repository,
   use case and store.
4. `router.start()`, `preferences.start()`, `settings.applyCachedLanguage()`,
   and on `/tv/` the saved interface size, all before the first paint.
5. Seed `document.title` and `<html lang>` for the frames before the first
   composition; from then on the shell's root owns both.
6. `session.start()` — attaches the auth observer.
7. `renderComposable { graph.Provide { … } }` with the root for this page's
   shell: `App()`, `TvApp()` or `DriveApp()`.

Which shell a page is comes from `data-shell` on its root element, read by
`ui/Shell.kt`. A `?ui=` parameter can override it; names starting with `tv` or
`drive` pick those shells.

`Provide` puts every store into a `staticCompositionLocalOf`. The locals have no
default, so a missing provider is a loud error at first read rather than a
second, silently-empty store.

### Auth and the session

`FirebaseAccountRepository` maps Firebase's user object down to a three-field
`Account`. The SDK type never leaves that file, which is what makes the
signed-in paths expressible in a test.

`SessionViewModel`'s state has `loading` true until the provider reports the
restored session. `App` waits for it before deciding anything — without that, a
page load would flash the login screen at an already-signed-in user.

The same ViewModel does what every shell root used to repeat: settings sync
follows the account's uid, the catalog is loaded for whoever arrives, and it is
dropped when nobody is using the app. All of it runs in the app scope as the
session changes, not in a composition. A new viewer stops the last one's load,
and the retry buttons on the dashboard and in driving mode call
`retryCatalogLoad`.

**Email and password only.** Google sign-in was offered once and is gone: it was
never used after the account moved to the console (see the admin-rights note
above), it cannot work at all inside an Android TV WebView — Google has blocked
OAuth in embedded WebViews since 2023, and `signInWithRedirect` is no help there
because it is the same WebView — and keeping a second provider meant keeping the
whole popup flow for a button nobody pressed. `AccountRepository` therefore has
no provider method, and the sign-in error mapping has no popup branches.

Note that removing it from the app does not disable it in the project: the Google
provider has to be switched off in **Authentication → Sign-in method** as well,
or an account can still be created against it by other means.

`ErrorMessages.signIn` maps Firebase codes to readable text. Firebase puts the
only actionable identifier in `code` and prose in `message`; `Log.codeOf` digs
the code out of the dynamic object.

### Guest mode

Who is using the app is a `Viewer` (`domain/model/Viewer.kt`): `Member(account)`
or `Guest`. Every shell gates on the session state's `viewer` (null means the
sign-in screen) and files progress under its `viewerId`.

- **`uid` still means the account.** The session state's `uid` is null for a guest, and
  settings sync starts only with an account uid. That is the whole reason a
  guest's settings stay on the device: the settings repository changes the device
  copy and has no account to write to.
- **Guest progress never reaches Firestore.** `ViewerProgressRepository` routes
  each call by the owner id it names: `guest:device` goes to
  `LocalProgressRepository` (`localStorage` key `progress_v1:guest:device`),
  anything else to Firestore. A colon can never appear in a Firebase uid. Routing
  by the named owner, not by who is signed in now, keeps a late save from the
  watch screen with the viewer who was watching.
- **Two tabs add up.** `LocalProgressRepository` reads, changes and writes back on
  every call instead of overwriting from memory. If storage refuses, it keeps this
  page's copy, so only persistence is lost.
- **The guest choice is per device**, in `localStorage` key `guest_mode_v1`
  (`LocalGuestMode`). An account always wins over it, and signing in clears it,
  so a later sign-out returns to the sign-in screen.
- **Member to guest without a flash.** "Continue as guest" in a member's menu
  (`switchToGuest`) sets the guest choice before signing out, and takes it back
  if the sign-out fails.
- **`CatalogStore` clears progress when the viewer changes**, so an account's
  progress never shows for the guest, or the other way round.

Where to find it: the sign-in screen's "Continue as guest" (web, TV and driving
mode share `LoginScreen`), the web header's Guest badge and Sign in, the web
menu, and a Sign in row in TV settings.

### The catalog

`CatalogRepository.load()` reads `meta/catalog` **first**, because its
`lastRefreshAtMillis` is what says whether a cached catalog is still good. One
small document then stands in for 932.

Losing the metadata does not fail the load — it is also the drawer's "last
refreshed" line — but it does cost the cache, since without a stamp nothing can
be validated.

`listEpisodes()` has one non-obvious guard: an unreachable backend does not
reject. Firestore quietly answers from its (empty) local cache, and treating
that as success renders "there are no episodes yet", which tells the viewer to
wait for an admin who has nothing to do. So an empty result with
`snapshot.metadata.fromCache` throws `CatalogUnavailableException` instead.

### Refreshing the catalog (admin only)

Fetches every season and episode from Formula, diffs each against what is
stored, and writes back only what changed — `Episode.sameContentAs` compares
fields but ignores timestamps, which move on every fetch and would otherwise
make all 932 documents look changed.

Writes are chunked at 450; Firestore caps a batch at 500.

`durationSeconds` is deliberately **omitted** from the write. Formula does not
expose durations, so they are only ever learned from the player, and a refresh
must not clobber them. A runtime the player measures goes into this device's
catalog cache for everyone, and into Firestore where the rules allow it (admins):
both are `FirestoreCatalogRepository.recordDuration`.

### Watch progress

The rules about what a position *means* live in `SaveProgress`, tested:

- Past **90%** of the runtime counts as watched. The threshold matches the
  original so nobody's history shifts.
- **A finished episode never moves backwards** unless the caller explicitly
  allows it. Without this, a `timeupdate` firing at 0 while the element reloads
  silently un-watches a completed episode — which happened, and looked like the
  app losing history at random.

Saves happen at most every 7 seconds during playback, plus on pause, on
`visibilitychange` to hidden (mobile often never fires `beforeunload`), and on
unmount.

On all three players (web, TV and driving mode) these decisions are made by
`PlaybackViewModel` (`presentation/`), which has tests and no browser: which file
plays, when to save and with what flag, the one retry after a media error, where to
resume, when the next-episode card is due, and leaving for the next episode. Each
player keeps its element and its own workarounds: `WatchScreen` has the ones below
(the resume retry loop, the `#t=` fragment, the AirPlay clock), `TvWatchScreen`
resumes with one seek, and `DrivePlayer` keeps its own resume and the media
session. A save already sent still lands after the player has moved on, because
saves run in the app scope.

### Choosing a player

```
nativePlayer = isAppleMobile && settings[Setting.UseNativePlayer]
```

`isAppleMobile` must check touch points, not just the user agent: iPadOS 13+
reports itself as `MacIntel` with a desktop UA, so without
`maxTouchPoints > 1` every iPad falls through to the custom player. Desktop
Safari has 0 touch points and correctly keeps the custom UI.

Other `isAppleMobile` checks in `WatchScreen` — resume, seeking, the AirPlay
ticker — are about **device quirks** and hold whichever player is drawing the
controls. Do not fold them into `nativePlayer`.

There is no next-episode overlay over the native player: it draws its own
fullscreen surface and nothing survives on top of it.

### Leaving fullscreen on iOS

Closing Safari's fullscreen video player on an iPhone pauses the video. That is
the browser, not this app: a bare page with a `<video>` does the same. The pauses,
measured in the iOS Simulator:

- **The X button**: a pause just before `webkitendfullscreen`, then, if the video
  plays again, a second one as the player finishes closing (about 0.5 to 0.8 s
  after).
- **Swiping the player down**: `webkitendfullscreen` while still playing, then one
  pause 0.3 to 0.4 s later.

The app leaves that pause alone, by the owner's choice. A version that played the
video again after these pauses (`FullscreenExitResumer`, with its timing rule
`FullscreenExitResume`, in commit 82408dc) was taken out before it shipped. Bring
it back from there if that changes.

Desktop browsers do not pause on leaving fullscreen. TV has no fullscreen exit of
this kind.

### Resume, and why it is a state machine

iOS silently ignores a seek issued too early. `applyResume` therefore retries
across `loadedmetadata → loadeddata → canplay → seeked` with a short backoff,
giving up after eight attempts.

It **never pauses the element while waiting** — pausing breaks the AirPlay
handshake and leaves the receiver playing while the phone reports 0:00.

On iOS the first source also carries the saved position as a `#t=` media
fragment (`withStartFragment`), so the first load starts there instead of loading
0:00 and then seeking. When the element is already at the target before any seek
of ours, `applyResume` stops there. The fragment is fixed at the first source: the
saved position changes with every save, and a changed `src` reloads the video.

### Which quality an episode starts in

A quality the viewer picked on any device of the account
(`Setting.PreferredQuality`, synced; its device copy is `preferred_quality` in
`localStorage`) wins. That includes a pick made on a desktop or TV: once one
exists, a phone starts there too, not at its 720p default. Otherwise a phone-sized screen (`isCompactScreen`, shorter
side under 600 CSS px) starts at 720p and everything else at the best rendition
(`defaultQualityLabel`). iPads are not compact. The native iOS player has no
quality menu, so with it on, this default is an iPhone's only way off 1080p. TV
asks for no cap (`TvWatchScreen`), so it starts at the best rendition on any screen.

### The AirPlay clock

iOS reports `currentTime` as 0 while playback is routed to an AirPlay target.
The local element's media clock is stopped even though the receiver is playing,
so the UI would sit at 0:00 and progress would never save.

`AirPlayClock` anchors the last believable position against wall-clock time and
extrapolates, falling back to the native value the moment it becomes sane again.
**Never verified on real hardware** — see §8.

### iOS playback and VLC

**Season 2 does not play on iPhone or iPad.** It is stored as interlaced 1080i
H.264. Apple's decoder rejects it with `MediaError` code 4, and every browser on
iOS has to use that decoder. Desktop browsers and VLC bring their own and play it.

- **A failed video says so.** `playbackFailure` (`domain/service/PlaybackFailure.kt`)
  turns code 4 on an iPhone or iPad into "does not play on iPhone or iPad", and
  anything else into a general load failure. The watch screen first retries once
  with a fresh link (`retryUrl`); when there is no new link to try, it shows the
  error at once instead of waiting on a black screen.
- **"Open in VLC" on every episode, iPhone and iPad only**, in VLC's orange,
  not only after an error. Not on a computer: VLC registers no link scheme there,
  and the browser plays everything anyway.
- **The link** is VLC's x-callback `stream` action (`vlcStreamUrl`), which plays at
  once. The whole link is percent-encoded, because VLC decodes the `url` value only
  when it finds an encoded `://` near the start.
- **The label carries a time** ("Open in VLC · 12:34"): the saved spot until the
  page's player moves, then wherever it is.
- **A playlist, through the worker.** `vlcOpenUrl` links to
  `workers/vlc-playlist` (`VLC_PLAYLIST_ENDPOINT`), which answers with an M3U that
  VLC for iOS 3.7.3+ expands into its queue:
  - this episode first, starting at the label's time (`#EXTVLCOPT:start-time`);
  - then, when the caller passes them, the rest of its season
    (`CatalogQueries.restOfSeason`), so the queue stops at the season's end. VLC
    plays its queue through, which is autoplay, so for now both the web page and
    driving mode pass none and VLC gets this episode alone;
  - each titled "სეზონი N · სერია M" from the catalog's own numbers, never from
    the file path (season 15's "(5)" lives in `episode_54`);
  - the current episode uses the page's fresh link, the next ones their stored
    links in the quality being watched where they have it;
  - what all items share (`base`, a `title` template) goes once, so a whole
    season fits in a few kilobytes.
- **Fallback.** With no endpoint, or a current link off `cdn.formula.ge`, the
  button links to the single file, which starts at 0:00. The label's time is then
  the spot to seek to by hand.

Where the worker lives and how it ships: §6.

### Driving mode

`/drive/` is a third shell on the same bundle (`ui/drive/`). It is for a phone in
a car mount, so it has no browsing: the home screen offers one episode, and the
player screen is the native player with big controls around it.

- **Home**: one large Continue or Play button. Whatever the player already holds
  wins; otherwise the episode comes from `CatalogStore.nextToPlay`.
- **Player**: the browser's native `<video controls playsinline>`, the same
  attributes as `NativeVideoPlayer`. Around it, all large: back, season and
  episode, time and minutes left, Full screen, Mark as watched, Open in VLC
  (iPhone and iPad), −30 s, play/pause, +30 s, then Previous and Next episode side
  by side. A playback error sits over the picture, so it takes no room from the
  buttons.
- **Full screen** (`DrivePlayer.enterFullscreen`) uses the standard Fullscreen API
  on the video where the page may use it (desktop, Android, iPad). An iPhone has
  none for a page, so there it opens Safari's own player with
  `webkitEnterFullscreen`. The button stays disabled until `loadedmetadata`: an
  iPhone asked earlier opens nothing, and then ignores the next request too.
  Leaving is the browser's own X, swipe or Escape. On an iPhone that pauses the
  video (see "Leaving fullscreen on iOS" below).
- **One `<video>` for the whole visit** (`DrivePlayer`). iOS lets an element start
  without a tap only after a tap has started it once. The home button starts it
  inside its tap, before the player screen exists; the player screen only lends it
  a frame. Previous and Next swap the source on that element and reuse that first
  permission. Nothing moves on by itself at `ended`: the episode is marked watched
  and stays.
- **The web player's rules, the same code** (`PlaybackViewModel`, one per episode
  opened): progress saves every `SAVE_EVERY` seconds, one fresh link after a media
  error, and the picked quality or the best rendition capped at 720p on a phone.
  The resume is the car player's own: it retries the seek (up to eight tries), adds
  a `#t=` fragment on iOS, and starts a finished episode over.
- **Car and headphone buttons** through the Media Session API (play/pause, ∓30 s,
  next, seek), and a screen wake lock while playing.
- **Mark as watched** shows a green tick once done. Tapping the tick asks the same
  "Clear progress?" confirmation as the web page (`ConfirmDialog`). Confirming
  deletes the progress (`DrivePlayer.clearProgress`) and takes the picture back to
  0:00.
- **Open in VLC** builds the same playlist as the web page, from where the car
  player is, and pauses the car player first.
- **Getting there**: "Driving mode" in the web menu, and a Driving mode button on
  the episode page. That button saves the current spot first and waits up to two
  seconds (`DRIVE_SAVE_WAIT_MS`), because car mode is another page that reads the
  saved spot, and a save started while a page unloads may never arrive.
  `driveHref` is relative, so `/pr/<n>/` opens its own car mode.
- **Guests** get driving mode too. Quality is the synced setting here, so a
  member's choice matches every other device; a guest's stays on the device.

### Language and settings

Every setting that follows the account is one line in the `Setting` registry
(`domain/.../domain/model/UserSettings.kt`): language, autoplay, System player,
preferred quality.
Everything that moves settings around iterates that registry — the Firestore
document, the device copy (`LocalSettingsCache`), the sync
(`SyncedSettingsRepository`) and the fixtures — so **a new setting syncs with no other code change**. The one other
edit is the field allowlist in `firebase/firestore.rules`, deliberately by hand:
the rules are the security boundary. Publish them before the bundle ships, or
writes of the new key are rejected and the app toasts "not synced".

UI reads `settings[Setting.X]` and writes `settings.set(...)` or
`settings.toggle(...)`. The language is applied to `I18n.current` as it arrives.
`SettingsStore` is only that Compose state and the language; the sync described
below is `SyncedSettingsRepository` in `:data`, and the store follows its
`StateFlow`.

The device copy is a cache, not a second source of truth. It renders the first
frame with the right values instead of flashing defaults while the network
answers, and it is the only copy that works offline. It reuses the
`localStorage` keys the per-device preferences had, which is the whole local
migration: nothing set before this change is lost.

When the device and the account meet (`SettingsSync`), per setting:

1. **A change this device made that the account has not confirmed wins**, and
   is uploaded. Firestore here keeps pending writes in memory only, so a change
   made offline is otherwise lost when the page closes, and the next load would
   hand the switch back to the server's older value. The unconfirmed marks are
   stored per account, so one account's change never reaches the next one to
   sign in on the device.
2. **Otherwise the account wins** whenever it has a value.
3. **A value the account never had is uploaded from the device.** This is how
   System player and preferred quality, per device until now, reach the account on the first
   sign-in after the upgrade. A device that never chose uploads nothing, so a
   default never overwrites a choice made elsewhere.

A *failed* read uploads nothing — that would push a possibly-stale local value
over the account's real setting because of one network blip. The owed sync runs
when the live listener next hears from the server (a TV app that starts before
its Wi-Fi does). The listener itself only ever applies the account side of the
rules, so a snapshot can never trigger a write that triggers a snapshot.

A rejected write keeps the setting locally, stays marked as unconfirmed, and
toasts. Losing the sync should not cost you the setting, but failing silently
looks exactly like "my other phone never updates". The marks live in
`localStorage` under `settings_unconfirmed`, per account.

A guest has no account, so the settings sync never starts for one. Their
changes go to the device copy only, and nothing is marked or uploaded.

Each setting is uploaded in its own write. The rules check the whole document,
so one field they do not allow yet would refuse every setting sent with it, on
every start. A setting's write runs in the app's scope, never a screen's:
closing the menu before Firestore answers must not lose the answer, or the
change stays marked unconfirmed and the device stops following the account.

### Mtavruli casing

Georgian has a real uppercase alphabet and setting chrome in it is a normal
typographic choice there. **CSS cannot do it**: `text-transform: uppercase`
deliberately skips the Mkhedruli → Mtavruli mapping (measured — CSS leaves
`ქართული` at its Mkhedruli width, while `"ქართული".uppercase()` produces
`ᲥᲐᲠᲗᲣᲚᲘ`). So casing lives in the string layer, as `Strings.caps`, which
English leaves as identity because all-caps English is just shouting.

Tab titles are the exception and stay Mkhedruli — a title is not the UI chrome
the rule is about, and `index.html` has always carried the plain form.

### The tab title

Derived from the route in `App`, not pushed from the watch screen. One writer
cannot strand a stale title on the way out of the player, and it handles the
episode being unknown until the catalog loads — the title simply recomputes when
it arrives.

### Build identity

`build.gradle.kts` generates `core/BuildInfo.kt` before compilation:

- `BUILD_NUMBER` — `GITHUB_RUN_NUMBER`, or `"dev"` locally.
- `COMMIT` — short hash.
- `PUBLISHED_AT_MILLIS` — the build moment on CI; the **commit** time locally,
  because a wall clock would change the generated file on every invocation and
  force a full Kotlin/JS recompile for a line of footer text.
- `REPO_URL` — derived from the git remote, because this repo has already
  changed owners once.

Everything the task's `doLast` touches is copied into locals first: referring to
the build script's own properties from inside it captures the script object,
which the configuration cache cannot serialise.

---

## 5. Firestore: model, rules, quota

```
episodes/{formulaEpisodeId}
    formulaEpisodeId, formulaSeasonId, seasonNumber, episodeNumber,
    title, thumbnailUrl, videoUrl, sources{quality: url},
    durationSeconds, episodePageUrl, lastResolvedAtMillis, updatedAtMillis

meta/catalog
    lastRefreshAtMillis, seasonCount, episodeCount

users/{uid}
    language, autoplayNext, useNativePlayer, preferredQuality, updatedAtMillis
    (one field per `Setting`; the rules allowlist each by name and type)

users/{uid}/progress/{episodeId}
    progressSeconds, durationSeconds, isWatched, lastWatchedAtMillis

admins/{uid}
    (existence is the whole payload; any fields are for humans)
```

A guest has nothing here. Their data is in `localStorage` on the device:

| Key | Holds |
|---|---|
| `progress_v1:guest:device` | the guest's watch progress |
| `guest_mode_v1` | that this device chose guest mode |

The device copy of the settings (`LocalSettingsCache`) uses the same keys for
members and guests: `language`, `autoplay_next_episode`, `use_native_player`,
`preferred_quality`.

Rules, in `firebase/firestore.rules`:

| Path | Read | Write |
|---|---|---|
| `episodes/*` | anyone, no account needed | admin |
| `meta/*` | anyone, no account needed | admin |
| `admins/{uid}` | that uid only | **nobody** |
| `users/{uid}` | owner | owner, fields allowlisted |
| `users/{uid}/progress/*` | owner | owner |
| everything else | denied | denied |

### Publishing the rules

**Nothing deploys the rules.** No workflow touches Firebase; the rules are pasted
into **Firestore → Rules** in the console and published by hand. The live rules
were last published on 2026-11-05 and match `firebase/firestore.rules` on `master`.

- **Rules first, bundle second.** Publish a rules change before the bundle that
  depends on it reaches `master` (a new settings key, a newly public path). Until
  then the app's writes of that key are refused and it toasts "not synced".
- **Publish the whole file from `master`**, never a branch's copy. Two pull
  requests can change different blocks of the same file, and pasting one of them
  drops the other's change. #28 and #29 both changed it; publishing one branch's
  copy left the other's change out until a combined file was published.
- **Never go back to an older `users` rule.** Once a `users/{uid}` document holds
  `useNativePlayer` or `preferredQuality`, a rule whose allowlist lacks them
  refuses every write to that document, language included.
- Every `/pr/<n>/` preview uses the same Firebase project, so a pull request
  that needs new rules needs them published before its preview can work.

Two things worth knowing:

- **Rules do not cascade into subcollections.** `users/{userId}` and
  `users/{userId}/progress/{episodeId}` are matched independently; a rule on the
  parent grants nothing on the child.
- **The field allowlist on `users/{uid}` is the point of that rule**, not the
  ownership check. A client allowed to write its own document is otherwise
  allowed to write *anything* into it, on a free tier with a shared quota.

### Quota, and why the cache exists

The Spark tier allows **50,000 reads per day**. Reading the whole catalog costs
**932 reads per page load**. That exhausted the quota in a single afternoon of
testing (91,000 reads against a 50,000 cap), which is what broke the app and
prompted the cache.

With the cache a normal load costs **1 read**. Watch progress is deliberately
*not* cached: it changes every few seconds during playback, so a cache would be
wrong more often than right — and it is the next thing to bite if usage grows.

Realtime Database was considered and rejected: it bills on **bytes per month**,
not operations per day, and the arithmetic came out worse than the cache
already achieves (roughly 570 loads/day on RTDB versus thousands with the
cache).

---

## 6. Build, test, deploy

Use **JDK 17** locally (`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`). In
iCloud Drive, JDK 21's `Files.copy` uses `clonefile()`, which iCloud refuses, and
the TV build fails. CI runs Temurin 21, outside iCloud. The `keel` submodule must
be checked out (`git submodule update --init keel`), or Gradle fails at
configuration.

```bash
./gradlew allTests                # every module's unit tests, on Node, no browser
./gradlew jsBrowserDistribution   # production bundle -> build/dist/js/productionExecutable/
python3 tools/check-css-classes.py   # after the build: every emitted class is styled on its shell
```

The CSS check reads the built stylesheets of each shell (`/`, `/tv/`, `/drive/`)
and fails when a composable emits a class that a page drawing it does not load.
It runs in CI as `css-classes`, and `master` requires it. That job builds the
bundle and runs this check. The unit tests run beside it as `unit-tests`, and
`deploy.yml` and `redesign.yml` run both before they publish.

Local preview is a static server over the built output — see
`.claude/launch.json`. Do **not** start dev servers from a shell.

### Deploy

| Trigger | Publishes to | Workflow |
|---|---|---|
| Push to `master` | the site root, with `/tv/` and `/drive/` inside it | `deploy.yml` |
| Pull request from this repository: opened, pushed, reopened | `/pr/<number>/` | `checks.yml`, `preview` job |
| That pull request closed | removes `/pr/<number>/` | `checks.yml`, `remove-preview` job |
| Push to `redesign/develop` | `/redesign/`, with `/redesign/tv/` and `/redesign/drive/` inside it | `redesign.yml` |

`deploy.yml` and `redesign.yml` run the unit tests, build and run the CSS check;
the `preview` job takes the build `css-classes` made. All three then call the
reusable `publish.yml`; `remove-preview` calls it with no build:

- **`write`** runs `tools/publish-site.sh <folder> <build>`. The site's state is
  the `gh-pages` branch, kept as **one commit with no history**. The script
  clones it, replaces only its own folder (`.`, `pr/<n>` or `redesign`), writes
  `.nojekyll` and `robots.txt` (which blocks `/pr/` and `/redesign/`), and
  replaces the commit with `--force-with-lease`, retrying from the new tip if
  another writer got there first. A root publish keeps `pr/` and `redesign/` and
  drops any other top-level folder nothing owns. Don't push to `gh-pages` by hand: there is no history to review it in,
  and the next publish of that folder overwrites it anyway.
- **`deploy`** runs one at a time across the repository (concurrency group
  `pages-deploy`) and uploads whatever `gh-pages` holds when it starts. A queued
  deploy cancelled by a newer one loses nothing, because the newer one sees at
  least as much.
- A removal does not deploy on its own. A closed pull request's folder leaves the
  live site with the next deploy of anything.

Repository settings this depends on:

- **Settings → Pages → Source: GitHub Actions.** If it ever reverts to `legacy`,
  the workflow goes green while the site 404s. That has happened.
- **Settings → Environments → github-pages → Deployment branches**: `master`,
  `refs/pull/*/merge` and `redesign/develop`. Pull request runs are matched by
  their merge ref.
- **`master` is protected**: a pull request is required (no approvals), the
  `css-classes` check must pass, no force pushes or deletion, admins included. A
  direct push to `master` is refused. By convention, pull requests are squash-merged.

Other things worth knowing:

- **There is no shared preview branch.** The old `preview` branch and its
  `/preview/` folder are retired (#33). To try a branch on the real site, open a
  draft pull request and use `/pr/<n>/`.
- **`redesign/develop` is the one long-running branch with its own folder**,
  `/redesign/`, for the redesign. A push there publishes it; nothing removes the
  folder when the branch is deleted. Pull requests into it get `/pr/<n>/` as usual.
- **Every folder uses the production Firebase project**, because the config is
  compiled in. A preview is a staging surface for the interface, not an isolated
  environment: anything done there is real data.
- **Pull requests from forks** get the check only. Their token cannot write the
  site, so the preview jobs are skipped.
- **A pull request with merge conflicts gets no Checks run**, so its preview stops
  updating until the conflict is resolved.
- `deploy.yml` refuses a manual run from any branch but `master`.
- **A failed or stuck deploy can't be fixed by re-running it.** Pages drops a
  second deployment of a commit it has already seen, and a re-run job also
  finds the first attempt's `github-pages` artifact and stops. The build is
  already on `gh-pages`, so the next deploy of any new commit (a merge to
  `master`, or a push to any open pull request) puts production right. That
  is how a Pages stall on 2026-11-05 was recovered.

### The VLC playlist worker

`workers/vlc-playlist` is deployed by hand, not by CI, to the owner's Cloudflare
account (free plan). The site points at it through `VLC_PLAYLIST_ENDPOINT` in
`domain/.../domain/service/VlcLink.kt`. From that folder:

```bash
node --test          # its own tests; Node 18+, no dependencies
npx wrangler deploy  # after `npx wrangler login` once
```

CI does not run these tests. See
[`workers/vlc-playlist/README.md`](../workers/vlc-playlist/README.md).

### The TV app

Built on a machine with an Android SDK, never by CI. `./gradlew :tv:assembleDebug`
for the real app, `./gradlew :tv:assemblePreview -PpreviewPr=<n>` for one that opens
`/pr/<n>/tv/`. Details in [`TV-NATIVE-APP.md`](TV-NATIVE-APP.md).

---

## 7. Traps that have already cost time

Each of these produced a convincing false bug report, a wasted hour, or both.

### Compose HTML freezes in a background tab

Recomposition is driven by `requestAnimationFrame`, which browsers pause when
the tab is not selected or the window is occluded. The UI freezes on its last
paint — typically `იტვირთება...` — and looks exactly like a hung boot, including
on the deployed site. `document.visibilityState` is the tell.

**Bring the tab to the front before anything that depends on rendering.** In the
in-app Browser pane, opening a second tab backgrounds the first and silently
freezes it.

### `navigator.clipboard.writeText()` needs a real gesture

It never settles for a scripted `element.click()`, so the promise hangs and no
toast appears. Use a genuine click. (Do not call `clipboard.readText()` while
probing — it blocks on a permission prompt and times out CDP.)

### CSS selectors must match what Compose actually emits

A block written against `<i>`/`<b>`/`<em>` matched nothing, because Compose
emits plain `<div>`s. Eight selectors were silently dead, which is why the
switch had no knob and labels lost their weight. When styling, check the
emitted tag.

### `classes()` with an empty string throws

`classes("a", if (flag) "b" else "")` raises `SyntaxError: The token provided
must not be empty` from `DOMTokenList.add`, which aborts the whole composition.
Route optional classes through `classNames()` in `ui/Css.kt`.

### `ref {}` runs once; `DomSideEffect(key)` re-runs

`Icon()` used `ref {}` to set `innerHTML`, so the glyph never changed while its
`aria-label` did — the play button stayed a play button after pressing play.

### Calling dynamic JS functions

`dyn.remote?.addEventListener?.invoke(...)` throws. Call it directly. All six
iOS AirPlay listeners silently failed to register this way, found only because
of the error logging.

### Firestore does not preserve map key order

Quality sources come back in a different order than they went in — the same
document returned two different orders on consecutive reads. Never derive
"best quality" from map iteration order; use `orderedQualityLabels`.

### `ka-GE` silently falls back to `en-US`

Chrome has no Georgian locale data: `Intl.DateTimeFormat.supportedLocalesOf(['ka'])`
returns `[]` and `toLocaleString` quietly gives American formatting inside an
otherwise entirely Georgian interface. Timestamps are formatted by hand as
`dd.MM.yyyy, HH:mm`.

### Kotlin block comments nest

A `/*` inside a KDoc comment — writing a path like `episodes/` followed by a
star — swallows the rest of the file. This has happened twice.

### The `<video>` element must not carry `crossorigin`

`cdn.formula.ge` sends no CORS headers; adding the attribute breaks playback
outright.

### iOS Safari ignores `user-scalable=no`

It has since iOS 10. The tag still stops pinch zoom on Android and in desktop
device emulation, so all three pages carry it. On iOS, `touch-action:
manipulation` kills double-tap zoom, and 16px form fields stop the focus zoom that
used to leave you signing in on a sideways-scrolled page. A pinch is stopped by
cancelling WebKit's own `gesturestart` and `gesturechange` (`ui/PageZoom.kt`,
installed once in `main` for every shell). Not on a `<video>`: a pinch out there
is how iOS opens its fullscreen player.

### iOS reports code 4 for more than undecodable files

`MediaError` code 4 is what season 2's 1080i files produce, but Safari on iOS also
reports it for some network and TLS failures. A blocked network (an office proxy,
say) can therefore show "does not play on iPhone or iPad" for an episode that
plays fine elsewhere.

### The iOS Simulator does not enforce the tap rule

A real iPhone starts media only from a tap, or on an element a tap has already
started. The Simulator lets a script start it either way, so it cannot prove
anything about autoplay without a tap. Only TV autoplays today; if the web or
driving mode gets autoplay back, that needs a real device.

### iOS starts picture in picture by itself only from its fullscreen player

Swiping home while a video plays in Apple's fullscreen player opens picture in
picture. From inline playback it does not, on the web page and in driving mode
alike. It is iOS's rule, not something the page can change, and it is why picture
in picture seems to work "sometimes". The Simulator has no picture in picture at
all (`document.pictureInPictureEnabled` is false), so this needs a real iPhone.

### iCloud Drive and the build

This working tree lives in iCloud Drive. JDK 21 cannot build the TV app here (see
§6), iCloud drops `"… 2"` conflict copies into `build/` folders that then fail
the build as duplicates (`./gradlew :tv:clean`), and git has hung once reading a
file in `.git`. If a git command hangs, find what it is reading with `lsof` and
retry.

### The in-app Browser pane

- **There is no Firebase session in it**, so every signed-in screen is
  unreachable, and signing in is off limits.
- Its `desktop` preset is the pane's own width (~334px) — below the 720px mobile
  breakpoint, so it will confirm mobile styles while you think you are testing
  desktop. Pass explicit `width`/`height`.
- Its mobile preset resizes only; `(pointer: coarse)` stays false, so
  touch-gated CSS cannot be exercised there at all.

---

## 8. What is verified, and what is not

**Automated.** `./gradlew allTests`, on Node, in `:presentation` for now (258 tests
on 2026-11-06, covering `:core`, `:domain` and `:data` too), run by CI on every pull request and before every publish: the watched threshold, the never-rewind guard, "continue watching"
and the next episode to play, ordering across season boundaries and the rest of a
season, hash-route parsing, settings sync (the merge rules, the repository, the
store, the device copy), the session (guest choice, admin rights, and the
settings sync, catalog load and catalog reset that follow it), a catalog load
joined or stopped halfway, the dashboard's progress changes and rebuild, the
sign-in form, guest storage, playback-failure classification,
the playback rules all three players share (the file, the stored links TV and
driving mode start from, saves, the one retry, resume and the next-episode card), the quality default, the iOS resume fragment, the VLC links and playlist encoding,
TV seeking and interface size, timestamp formatting, toast dismissal, the catalog
cache's stamp rule, its thirteen-field round trip and its duration patch, the bare
GET, and the
Firebase error-code spellings.

The worker has its own tests (`node --test` in `workers/vlc-playlist`). One of them
decodes a link pinned by `VlcPlaylistTest` the way VLC does, so the two sides
cannot drift apart.

CI runs none of these tests, and nothing in the TV app. It only builds the bundle
and runs the CSS check.

**Fixtures.** `/?ui=demo`, `/tv/?ui=tv-demo` and `/drive/?ui=drive-demo` (plus
`&guest`) render every screen against fake data with no Firebase, which is how
UI changes are checked in a browser or the iOS Simulator. Their video links are
fake, so playback itself is never exercised there.

**Verified by hand, repeatedly.** The signed-out path — boot, redirect to
`#/login`, language switching, the drawer's layout, responsive behaviour at
375px — plus a full manual pass documented in `TEST-PLAN.md` and
`BUG-REPORT*.md`.

**On a real iPhone.** The VLC playlist (#32), from a real episode page on a pull
request preview: the rest of the season in VLC's queue, with titles, the start
time and autoplay.

**Never verified.**

- **Anything behind sign-in, in recent changes.** There is no Firebase session
  in the automated browser and signing in is not permitted, so dashboard,
  watch and playback changes are compile-checked, checked on fixtures, and
  reasoned about, not exercised against real data.
- **The AirPlay clock** on real hardware. It is written from the documented
  behaviour and the original app's workarounds.
- **Driving mode on a real iPhone**: real playback, Previous and Next, native
  fullscreen, picture in picture, Media Session buttons and the wake lock. All of
  it was checked only in the Simulator, which does not enforce the tap rule and
  has no picture in picture (§7).
- **Guest mode with a real TV remote**, reaching "Continue as guest".
- **Settings sync on two real devices** after the final #28 fix and the
  combined rules: no such check is recorded.
- **`CustomVideoPlayer` internals**, beyond rendering. It is 600 lines of media
  state machine that needs a playing video to exercise.

---

## 9. Current state and open work

### Code

Everything in this document is merged to `master` and deployed. The live
Firestore rules match `firebase/firestore.rules` on `master` (published
2026-11-05).

### Admin rights need a console entry

The catalog refresh control appears only for an account with an `admins/{uid}`
document, because `FirestoreAdminRepository` reads it and `isAdmin()` in the
rules calls `exists()` on it. If the owner does not see the refresh control,
create that document by hand in the Firebase console (fields are for humans
only). No redeploy is needed. `admins` is write-denied to every client, which is
the security property, not an obstacle to route around, and no Firebase or
gcloud CLI is installed on the owner's machine.

### Known limits

- **Season 2 does not play in any browser on iPhone or iPad.** VLC is the way
  (§4, iOS playback and VLC). Safari also reports some network failures as the
  same error (§7).
- **VLC reports nothing back.** Watching in VLC does not save progress or mark
  episodes watched on the site.
- **Only the current episode gets a fresh link** in the VLC playlist. The next
  ones use their stored links, which can go stale.
- **The worker is in the owner's Cloudflare account** and is deployed by hand.
  If it goes away, "Open in VLC" still works for one episode at a time once
  `VLC_PLAYLIST_ENDPOINT` is set to null.
- **A guest's progress is on one device**, in `localStorage`. Clearing site data
  loses it, and signing in does not move it to the account.
- **Public catalog reads count against the shared daily quota** without an
  account (§3).
- **Previews share production's Firebase project.** Anything done on `/pr/<n>/`
  is real data.
- **A pull request with merge conflicts gets no preview update**, and a closed
  pull request's folder leaves the live site only with the next deploy.
- **The TV app is only as good as the live site.** A broken deploy to `master`
  breaks it with no reinstall; try TV changes with the preview build first.

### Known gaps, unprioritised

- Watch progress is uncached and is the next quota bottleneck.
- `CustomVideoPlayer` is still one 600-line composable. It has no layering
  violations; splitting it blind is the risk.
- English exists and is complete but has never been proof-read by a speaker of
  the Georgian original for tone.

---

## 10. How the owner likes to work

Observed across the project, and worth matching.

- **Branch, verify, propose, fix, PR.** Their stated process for a batch of
  fixes: *"1. Create a new branch for all bug fixes 2. Check that bug really
  exists, 3. Propose a fixing plan, 4. Fix the bug, retest and commit a bug fix
  if it is fixed, 5. After all bugs are fixed, create pull request and request
  copilot for review."* Direct commits to `master` happen only when they ask for
  them explicitly.
- **Confirm a plan before large work.** For the QA sweep they said: *"Do not
  start doing it yet. To make sure you got everything right, tell me what you
  will do and only start working after my explicit approval."*
- **Say what was not verified.** Several real bugs here were false negatives
  from an environment quirk, and several false alarms were the same. Claims
  about what works should carry their evidence.
- **They review PR descriptions.** Write them for a reader deciding whether to
  merge: what changed, why, what was checked, what was not.
