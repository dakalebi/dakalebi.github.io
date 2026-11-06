# ჩემი ცოლის დაქალები — სანახავი დაფა

A private watch dashboard for one 932-episode Georgian TV series. Static bundle
on GitHub Pages, Firebase for accounts and data, episodes pulled straight from
Formula's public API in the browser. No backend.

Sign in to keep your progress and settings on every device, or continue as a
guest: guests watch the same catalog, and their progress stays on that device.

Live at <https://dakalebi.github.io/>.

| | |
|---|---|
| Language | Kotlin 2.3.20 (Kotlin/JS, IR) |
| UI | Compose HTML (Compose Multiplatform 1.11.1) |
| Backend | Firebase Auth + Cloud Firestore — no server |
| Hosting | GitHub Pages, hash routing |
| Episodes | `https://mw-api.formula.ge/formula` (CORS-open, called from the browser) |
| VLC playlist | [`workers/vlc-playlist`](workers/vlc-playlist), a stateless Cloudflare Worker (optional) |

## Start here

**[`docs/PROJECT-GUIDE.md`](docs/PROJECT-GUIDE.md)** — what this is, which
decisions were made and why, how each subsystem works, and the traps that have
already cost time. Read it before changing behaviour.

| | |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | the layering, and the rule that keeps it |
| [`docs/TEST-PLAN.md`](docs/TEST-PLAN.md) | the manual test plan |
| [`docs/TV-NATIVE-APP.md`](docs/TV-NATIVE-APP.md) | the Android TV app: input, build, device results |
| [`docs/TV-NAVIGATION.md`](docs/TV-NAVIGATION.md) | how the remote moves focus on `/tv/`, and why |
| [`docs/BUG-REPORT.md`](docs/BUG-REPORT.md), [`docs/BUG-REPORT-2.md`](docs/BUG-REPORT-2.md) | defects found and fixed, with root causes |
| [`workers/vlc-playlist/README.md`](workers/vlc-playlist/README.md) | the VLC playlist worker: what it accepts, how to deploy it |

## Three front ends, one bundle

| Path | For | Fixture |
|---|---|---|
| `/` | phone and desktop browsers | `/?ui=demo` |
| `/tv/` | a television: the Android TV app, or any browser at 960×540 | `/tv/?ui=tv-demo` |
| `/drive/` | driving mode: a phone in a car mount, big buttons, the native player | `/drive/?ui=drive-demo` |

Each folder has its own `index.html` and stylesheet, and all three load the same
`app.js`. The page says which UI it is with `data-shell` on its root element
(`Shell.kt` in `:presentation-new`), so the choice never depends on sniffing the device.

A fixture URL swaps Firebase for fake data, so every screen can be looked at
without signing in. Add `&guest` to start as a guest. Fixture video links are fake,
so nothing plays there.

## Build and test

Use **JDK 17** locally. In iCloud Drive, JDK 21 fails the TV build (see
[`docs/TV-NATIVE-APP.md`](docs/TV-NATIVE-APP.md) §5); CI uses Temurin 21 and is
not affected.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

```bash
./gradlew allTests
```

Each module has its own unit tests, in its `src/commonTest`, and the fakes they
share are in the test-only `:testing` module. None of that needs a browser (no Compose HTML,
no Firebase, no DOM), so the tests run on Node. `allTests` runs every module's
tests. CI runs it on every pull request (`unit-tests`) and before every publish.

```bash
./gradlew jsBrowserDistribution
```

The production bundle lands in `build/dist/js/productionExecutable/`. Serve that
folder to try it locally (`.claude/launch.json` runs `python3 -m http.server 4173`
on it).

```bash
python3 tools/check-css-classes.py
```

Run it after the build: it reads the built stylesheets. It fails when a
composable emits a CSS class that the shell drawing it does not load. It is the
`css-classes` check every pull request must pass.

## TV app

`tv/` is the Android TV / Google TV shell: a full-screen WebView on `/tv/` that
bundles no web code, so every deploy is live on the television. It is built only
when an Android SDK is visible (`local.properties` or `ANDROID_HOME`).

```bash
./gradlew :tv:assembleDebug
```

```bash
adb install -r tv/build/outputs/apk/debug/tv-debug.apk
```

The preview build opens one pull request's copy of the site, `/pr/<number>/tv/`,
and installs beside the real app. It needs the number, and stops without one:

```bash
./gradlew :tv:assemblePreview -PpreviewPr=<number>
```

Both builds can be inspected from `chrome://inspect`. In iCloud Drive, run
`./gradlew :tv:clean` if the build trips over `"… 2"` duplicate files. How the
remote reaches the page, and what was verified:
[`docs/TV-NATIVE-APP.md`](docs/TV-NATIVE-APP.md).

## Layout

Five Gradle modules, one per layer. The root is the web app. `:core` and
`:domain` depend on no other module, `:data` implements the domain's
repositories, and `:presentation` holds the screen state and strings, with no
Compose HTML in it, so another front end can reuse it.

```
core/src/commonMain/kotlin/ge/dakalebi/core/
                 logging and formatting policy
core/src/jsMain/kotlin/ge/dakalebi/core/
  *.js.kt        two platform halves: console, clock
domain/src/commonMain/kotlin/ge/dakalebi/domain/
                 models, repository interfaces, use cases, pure queries
data/src/commonMain/kotlin/ge/dakalebi/data/
                 the settings sync, and progress routed by viewer
  formula/       the Formula API and its DTOs
  cavea/         Cavea Plus's public episode list, read by the admin refresh
  local/         the catalog cache and device storage over a `KeyValueStore`
                 (settings copy, guest progress, guest choice)
data/src/jsMain/kotlin/ge/dakalebi/data/
  firebase/      the Firestore and Auth SDK, behind the domain interfaces
  local/         localStorage
  formula/       the third platform half: a bare fetch
presentation/src/commonMain/kotlin/ge/dakalebi/
  presentation/  stores and ViewModels, error-to-text mapping, routes
  di/            the composition locals
  i18n/          every user-facing string, one file per language
presentation/src/jsMain/kotlin/ge/dakalebi/presentation/
  HashRouter.kt  the browser's router: the location hash
testing/src/commonMain/kotlin/ge/dakalebi/testing/
                 test-only: the fakes the modules' tests share
presentation-new/src/jsMain/kotlin/ge/dakalebi/next/
                 the UI, Compose HTML, for all three pages:
  Shell.kt       which UI a page is; PageZoom.kt stops pinch zoom
  components/    the components and the 27 icons, for all three shells
  web/           the web screens: sign-in, Home, Seasons, Settings, the tab bar and
                 side menu around them, and the player (mini-player, sheet, panel)
  gallery/       every component in every state, on any page with ?gallery
  tv/            the TV screens: sign-in, Home, Seasons, Settings, and the rail
                 around them
  tv/focus/      D-pad navigation — geometry within a band, memory across bands
  tv/input/      one window listener and a layer stack, for the whole TV UI
  tv/player/     the TV player: its state, keys, video element, chrome and shelf
  drive/         driving mode: the one-button Home, the player screen, and the
                 player, its media session and wake lock
  player/        media helpers: the AirPlay clock, device checks, player keys
presentation-new/src/jsMain/resources/
  index.html, web.css    the web page, with the shared tokens and component sheets
  tv/, drive/            the other two pages, one folder each

src/jsMain/kotlin/ge/dakalebi/
  di/            the composition roots that name the implementations: AppGraph,
                 and the fixture graph behind `?ui=demo`
  Main.kt        the entry point
src/jsMain/resources/    the icons and the logo, at the site root
workers/vlc-playlist/    the Cloudflare Worker behind "Open in VLC"
firebase/firestore.rules the whole security boundary
tools/                   the CSS check and the site publish script
```

Dependencies point inwards, and no `commonMain` names a framework at all.
`:domain` cannot: nothing but Kotlin and kotlinx-coroutines is on its classpath. The greps that check the
rest are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Data model

```
episodes/{formulaEpisodeId}   the shared catalog; anyone reads, admins write
meta/catalog                  lastRefreshAtMillis, seasonCount, episodeCount
meta/cavea                    fetchedAtMillis, episodes[{id, season, episode, thumbnail}]
users/{uid}                   language, autoplayNext, useNativePlayer,
                              preferredQuality, updatedAtMillis
users/{uid}/progress/{id}     progressSeconds, durationSeconds, isWatched, lastWatchedAtMillis
admins/{uid}                  existence grants catalog-write; writable by nobody
```

A guest has no Firestore data. Their progress is in `localStorage`
(`progress_v1:guest:device`), and their settings stay in the device copy.

Document ids are Formula's own episode ids, so `#/watch/531` survives a full
catalog rebuild.

`episodes` holds Formula's catalog only, as a build that knows nothing of Cavea
Plus expects. Cavea's list sits beside it in `meta/cavea` and is joined in on
load by season and episode number. An episode only Cavea has is shown as
`#/watch/cavea-<Cavea's id>` and links to Cavea's own page: its streams are
DRM-protected, so the app never plays them.

`meta/catalog.lastRefreshAtMillis` is bumped by exactly the operation that
changes the catalog, which makes it the cache validator: one small read says
whether the other 932 are still current. Without it a page load cost 932 reads
and exhausted the free tier's daily 50,000 in an afternoon.

## Setup

The consoles are yours; the code is already written against them.

### Firebase

1. Create a project at <https://console.firebase.google.com>.
2. **Authentication → Sign-in method** → enable **Email/Password**, and leave every
   other provider disabled. The app offers no other way in.
3. **Authentication → Settings → Authorized domains** → add the Pages domain.
   Sign-in fails silently without this.
4. **Firestore Database** → create in production mode.
5. **Project settings → General → Your apps → Web app** → register one and copy
   the config into
   `data/src/jsMain/kotlin/ge/dakalebi/data/firebase/FirebaseConfig.kt`. Those keys
   are public by design: they identify the project, they authorize nothing.
6. **Firestore → Rules** → paste `firebase/firestore.rules` and publish. These
   rules are the entire security boundary — there is no server behind them.
   Nothing deploys them for you: they are published by hand, and a change to
   them must be live **before** the bundle that depends on it ships.
7. To grant catalog-refresh rights, create `admins/{uid}` by hand in the
   console. That collection is write-denied to every client, which is the
   point — a flag the user could write is a flag they could grant themselves.

### GitHub Pages

**Settings → Pages → Source: GitHub Actions.** If the source ever reverts to
`legacy`, the workflow goes green while the site 404s.

| What | Where it publishes | Workflow |
|---|---|---|
| A push to `master` | the site root, `/` | `deploy.yml` |
| A pull request from this repository | `/pr/<number>/`, rebuilt on each push, removed when it closes | `checks.yml` |
| A push to `redesign/develop` | `/redesign/`, rebuilt on each push | `redesign.yml` |

To try a branch on the real site, open a draft pull request. The one long-running
branch with a folder of its own is `redesign/develop`, for the redesign.

- **`gh-pages` is the site's state, kept as one commit.**
  `tools/publish-site.sh` rewrites one folder and replaces that commit; the
  reusable `publish.yml` writes it and then deploys whatever `gh-pages` holds.
  Don't push to `gh-pages` by hand.
- **`master` is protected.** Changes land through a pull request (no approvals
  needed) whose `css-classes` check passes. No force pushes or deletion, and
  admins are included. By convention, pull requests are squash-merged.
- **Settings → Environments → github-pages → Deployment branches** must allow
  `master`, `refs/pull/*/merge` and `redesign/develop`. Pull request runs are
  matched by their merge ref, not their branch name, so without the second one
  every preview fails at the deploy step.

Every folder, `/pr/<n>/` and `/redesign/` included, talks to the **same Firebase project** as
production, because the config is compiled in. Anything done on a preview is real
data.

## Notes

- Video is never rehosted — the official MP4 URLs from `cdn.formula.ge` play
  directly.
- The `<video>` element must **not** carry `crossorigin`: the CDN sends no CORS
  headers and the attribute breaks playback.
- iPhone and iPad default to Apple's native player, with a switch in the drawer
  for the web player. Everywhere else gets the web player, which is the only one
  there is.
- Season 2 is interlaced 1080i, which no browser on iPhone or iPad can decode.
  Those devices get an orange **Open in VLC** button on every episode. It opens a
  VLC playlist of the rest of the season, starting where you are, through the
  worker in `workers/vlc-playlist`.
- Georgian UI chrome is set in Mtavruli from Kotlin, not CSS —
  `text-transform: uppercase` deliberately skips that mapping.
