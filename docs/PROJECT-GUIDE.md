# Project guide

Written for someone — or some agent — arriving with no context. It covers what
this is, what the owner asked for, which decisions were made and why, how each
piece works, and the traps that have already cost real time.

Companion documents:

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — the layering and the rule that holds it
- [`TEST-PLAN.md`](TEST-PLAN.md) — the manual, signed-in test plan
- [`BUG-REPORT.md`](BUG-REPORT.md), [`BUG-REPORT-2.md`](BUG-REPORT-2.md) — defects found and fixed, with root causes

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
- **Language must be switchable and must sync between devices.**
- **On Apple devices only**, a switch between the native player and the web
  player. Stored per-device in `localStorage`, **default on**.
- **Every user-facing string extracted** into something like Android's
  `strings.xml`, so other languages can be added.
- **Georgian set in Mtavruli** (uppercase Georgian: `ქართული` → `ᲥᲐᲠᲗᲣᲚᲘ`) for
  UI chrome, and a distinct font for times and numbers.
- **Application version, plus deployment date and time, visible in the drawer.**
- **Restrict zooming on small devices.**
- **Watch-screen action buttons full width on mobile.**
- **Tab title while watching**: `სეზონი X ⋅ სერია XX - ჩცდ`. (The owner reordered
  this after the first attempt put the initials first — keep their order.)

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

Five repositories and a use case per operation, wired by hand in
`di/AppGraph.kt`. A
container would add a dependency and a layer of indirection to save one page of
unambiguous constructor calls.

### Tests on Node, not in a browser

The domain layer has no browser in it, so `jsNodeTest` runs it in about a
second. Standing up Karma and a headless Chrome to assert things about sorting
would be slower and more fragile for no gain. The browser-facing code is tested
by hand — see §8.

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
2. If `FirebaseConfig.isConfigured` is false, render `SetupNotice()` and stop —
   touching Firebase before the config is filled in throws on init.
3. Build `AppGraph()`, which constructs every repository, use case and store.
4. `router.start()`, `preferences.start()`, `settings.applyCachedLanguage()`.
5. Seed `document.title` and `<html lang>` for the frames before the first
   composition; from then on `App` owns both.
6. `session.start()` — attaches the auth observer.
7. `renderComposable { graph.Provide { App() } }`.

`Provide` puts every store into a `staticCompositionLocalOf`. The locals have no
default, so a missing provider is a loud error at first read rather than a
second, silently-empty store.

### Auth and the session

`FirebaseAccountRepository` maps Firebase's user object down to a three-field
`Account`. The SDK type never leaves that file, which is what makes the
signed-in paths expressible in a test.

`SessionStore.loading` is true until the provider reports the restored session.
`App` waits for it before deciding anything — without that, a page load would
flash the login screen at an already-signed-in user.

Sign-in uses a **popup, not a redirect**: with the app on `*.github.io` and the
auth handler on `*.firebaseapp.com`, the redirect flow depends on third-party
storage access that browsers now block.

`ErrorMessages.signIn` maps Firebase codes to readable text. Firebase puts the
only actionable identifier in `code` and prose in `message`; `Log.codeOf` digs
the code out of the dynamic object.

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
must not clobber them.

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

### Choosing a player

```
nativePlayer = isAppleMobile && preferences.useNativePlayer
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

### Resume, and why it is a state machine

iOS silently ignores a seek issued too early. `applyResume` therefore retries
across `loadedmetadata → loadeddata → canplay → seeked` with a short backoff,
giving up after eight attempts.

It **never pauses the element while waiting** — pausing breaks the AirPlay
handshake and leaves the receiver playing while the phone reports 0:00.

### The AirPlay clock

iOS reports `currentTime` as 0 while playback is routed to an AirPlay target.
The local element's media clock is stopped even though the receiver is playing,
so the UI would sit at 0:00 and progress would never save.

`AirPlayClock` anchors the last believable position against wall-clock time and
extrapolates, falling back to the native value the moment it becomes sane again.
**Never verified on real hardware** — see §8.

### Language and settings

`SettingsStore` owns no language state of its own: the language *is*
`I18n.current`. It plumbs between that, `localStorage`, and the account
document.

The local copy is a cache, not a duplicate source of truth. It renders the first
frame in the right language instead of flashing Georgian while the network
answers, and it is the only copy that works offline or signed out. The server
wins whenever it has an opinion.

An account with nothing on record is **seeded from the device**, or the first
device to sign in would keep being asked and nothing would ever reach the
second. A *failed* read seeds nothing — that would push a possibly-stale local
value over the account's real setting because of one network blip.

A rejected write changes the setting locally anyway and toasts. Losing the sync
should not cost you the setting, but failing silently looks exactly like "my
other phone never updates".

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
    language, autoplayNext, updatedAtMillis

users/{uid}/progress/{episodeId}
    progressSeconds, durationSeconds, isWatched, lastWatchedAtMillis

admins/{uid}
    (existence is the whole payload; any fields are for humans)
```

All of the above is on `main`. What is **not** yet true of the live Firebase
project is the `admins/{uid}` document and the published rules — see §9.

Rules, in `firebase/firestore.rules`:

| Path | Read | Write |
|---|---|---|
| `episodes/*` | any signed-in | admin |
| `meta/*` | any signed-in | admin |
| `admins/{uid}` | that uid only | **nobody** |
| `users/{uid}` | owner | owner, fields allowlisted |
| `users/{uid}/progress/*` | owner | owner |
| everything else | denied | denied |

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

```bash
./gradlew jsBrowserDistribution   # production bundle -> build/dist/js/productionExecutable/
./gradlew jsNodeTest              # domain tests, ~1s, no browser
```

Local preview is a static server over the built output — see
`.claude/launch.json`. Do **not** start dev servers from a shell.

Deployment is `.github/workflows/deploy.yml`: every push to `main` builds and
publishes to Pages. Pages must be set to **Source: GitHub Actions**; if it ever
reverts to `legacy`, the workflow goes green while the site 404s. That has
happened.

The workflow touches `.nojekyll`, because Pages runs Jekyll by default and Jekyll
drops files beginning with an underscore, which webpack can emit.

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

It has since iOS 10 and always permits a deliberate pinch. What *can* be stopped
there is the accidental kind: `touch-action: manipulation` kills double-tap
zoom, and 16px form fields stop the focus zoom that used to leave you signing in
on a sideways-scrolled page.

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

**Automated.** 35 domain tests on Node: the watched threshold, the
never-rewind guard, "continue watching" including its five-second floor,
ordering across season boundaries, hash-route parsing, the settings merge
semantics, and the admin lookup.

**Verified by hand, repeatedly.** The signed-out path — boot, redirect to
`#/login`, language switching, the drawer's layout, responsive behaviour at
375px — plus a full manual pass documented in `TEST-PLAN.md` and
`BUG-REPORT*.md`.

**Never verified.**

- **Anything behind sign-in, in recent changes.** There is no Firebase session
  in the automated browser and signing in is not permitted, so dashboard,
  watch and playback changes are compile-checked and reasoned about, not
  exercised. Where a signed-in screen genuinely had to be seen, a throwaway
  harness was used (a temporary catalog seeder plus an `App.kt` edit rendering
  the dashboard while signed out), committed nothing, and was reverted.
- **iOS Safari, on real hardware.** Both the native player path and the AirPlay
  wall-clock extrapolation are unexercised. They are written from the
  documented behaviour and the original app's workarounds.
- **`CustomVideoPlayer` internals**, beyond rendering. It is 600 lines of media
  state machine that needs a playing video to exercise.

---

## 9. Current state and open work

### Code

Everything in this document is merged to `main` and deployed.

### Two console steps still outstanding

The code for admin rights ships, but the Firebase project has not been set up
for it yet. Until both of these are done, **the catalog refresh control will
not appear**, because `FirestoreAdminRepository` reads `admins/{uid}` and the
published rules currently deny that path.

Nothing else is affected: language and autoplay sync, the catalog cache and
playback all work. The fix is recoverable at any time and needs no redeploy.

1. **Create `admins/PZ6HS4qhStUv8Ai1VxCGU72bW6G3`** in the Firebase console.
   Fields are for humans only.
2. **Publish `firebase/firestore.rules`** — Firestore → Rules → select all →
   paste → Publish.

**Order matters.** The new `isAdmin()` calls `exists()` on that document, so
publishing first denies catalog writes until the document appears.

Both must be done in the console. `admins` is write-denied to every client —
that is the security property, not an obstacle to route around — and no
Firebase or gcloud CLI is installed on the owner's machine.

A scheduled task, `dakalebi-finish-firestore-admin`, exists to walk these
steps and verify them.

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
  copilot for review."* Direct commits to `main` happen only when they ask for
  them explicitly.
- **Confirm a plan before large work.** For the QA sweep they said: *"Do not
  start doing it yet. To make sure you got everything right, tell me what you
  will do and only start working after my explicit approval."*
- **Say what was not verified.** Several real bugs here were false negatives
  from an environment quirk, and several false alarms were the same. Claims
  about what works should carry their evidence.
- **They review PR descriptions.** Write them for a reader deciding whether to
  merge: what changed, why, what was checked, what was not.
