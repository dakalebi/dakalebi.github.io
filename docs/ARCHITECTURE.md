# Architecture

The app is a static Kotlin/JS bundle on GitHub Pages talking straight to
Firebase from the browser. There is no server, so the only structure it has is
the one in the source tree.

## Modules

The layering below used to be a convention inside one module. It is now a set of
module boundaries, which the compiler enforces rather than a grep: one module per
layer, with the web app on top.

| Module | Holds | Why |
|---|---|---|
| `:core` | `commonMain`: logging and formatting policy. `jsMain`: the console and the clock, two `.js.kt` files | Depends on nothing. Every other module may use it |
| `:domain` | models, repository interfaces, a use case per operation, pure queries | Depends on no other module, not even `:core`, and on one library: kotlinx-coroutines, for `Flow`. So "the domain names no framework" is the compile classpath and not only a convention |
| `:data` | `commonMain`: the Formula API and DTOs, the settings sync (`SyncedSettingsRepository`), and the storage that is plain Kotlin over a `KeyValueStore`: the catalog cache, the settings device copy, guest progress, the guest choice, and `ViewerProgressRepository`, which routes progress between the account and the device. `jsMain`: `firebase` (Firestore and Auth through the JS SDK), `local` (the browser's `localStorage`), the system clock, and the bare GET | Every implementation of a domain repository. Depends on `:domain` and `:core` only, so it cannot name presentation, `i18n` or UI. Its caches are its own: no interface for them is in `:domain` |
| `:presentation` | `commonMain`: the stores and ViewModels, the `Route` type, error wording, `i18n`, the composition locals. `jsMain`: `HashRouter` | The screen state and the strings, so a second front end reuses them instead of reimplementing them. Depends on `:domain`, `:core`, Compose runtime and coroutines, and never on Compose HTML or the component library, so a front end with a different look reuses it as it is. Only its tests depend on `:data` |
| `:presentation-new` | `jsMain`: the redesign's UI (Compose HTML) for all three shells, and its design system: `next/tokens.css`, `next/base.css`, and the `/next/` pages. Also the engines both UIs use: `Shell`, `PageZoom`, TV focus and input, the TV and driving-mode players' state, and the media helpers | The redesign, built next to the old UI until the switch. Depends on `:presentation` and `:core`, and never on keel. The old UI in root imports the engines from here, so there is one copy of each. Its pages and sheets reach the distribution through the root's `jsProcessResources`, like keel's. Phase 1 draws a foundations screen on each page |
| `:testing` | the fakes the modules' tests share: a clock, in-memory storage, the account's settings document, progress storage, and builders for episodes and progress | Test code only. No module's main code depends on it, so none of it reaches the bundle. A module, because Kotlin Multiplatform gives one module's tests no way to use another module's test code |
| root | `ui` (Compose HTML) for all three shells (`/`, `/tv/`, `/drive/`), the composition root, `Main.kt` | The web app. It also owns the webpack build, so the GitHub Pages artifact path never moves |

The root project stays the application deliberately: renaming it to `:web` would
move the Pages artifact out of `build/dist/js/productionExecutable`, and
`upload-pages-artifact` succeeds on an empty path. That failure publishes a
working-looking 404.

`commonMain` has no `expect` for convenience. There are exactly three, and each
one exists because a shared library would break something documented: the
console (`platformLog`) and the clock (`nowMillis`, `dateParts`), both in
`:core`, and a bare GET in `:data` (`httpGetText`, where any HTTP library's
default headers would break Formula's CORS contract — see the KDoc on it).

## The dependency rule

Arrows point inwards. Nothing on an inner ring knows a name from an outer one.

```
              ┌───────────────────────────────────┐
              │  ui/          Compose HTML only   │
              │  ┌─────────────────────────────┐  │
              │  │ presentation/  screen state │  │
              │  │  ┌───────────────────────┐  │  │
              │  │  │ domain/   plain Kotlin│  │  │
              │  │  │  model · repository   │  │  │
              │  │  │  usecase · service    │  │  │
              │  │  └───────────────────────┘  │  │
              │  └─────────────────────────────┘  │
              └───────────────────────────────────┘
                 data/ implements domain/repository
                 di/   is the only place that knows both
```

| Package | May import | Contains |
|---|---|---|
| `domain` | Kotlin and kotlinx-coroutines | models, repository **interfaces**, a use case per operation, pure queries |
| `data` | `domain`, `core` | Firestore, the Formula API, `localStorage` |
| `presentation` | `domain`, `core`, `i18n` | stores and ViewModels, error→text mapping, the `Route` type |
| `ui` | `presentation`, `domain`, `core`, `i18n`, `di` | Compose HTML, and nothing else |
| `di` | everything | the composition locals, and in the root the composition root |
| `core` | nothing but Kotlin | logging and formatting policy, generated build info |

Two rules carry most of the weight:

- **`domain` names no framework.** No Compose, no Firebase, no DOM, no `i18n`.
  It compiles and runs under Node, which is why it can have tests.
- **`data` names no UI.** Repositories throw typed failures; turning those into
  Georgian or English sentences is `presentation/ErrorMessages`. Before the
  restructure every repository built its own message, so translating the app
  meant editing Firestore code.

Most of the rules are now module boundaries. `:domain` and `:core` depend on no
other module, `:data` and `:presentation` depend on those two only, and none of
the modules depends on the root, so a reference the wrong way round does not
compile. In `:domain`, a Compose, Firebase or `i18n` import does not resolve; in
`:data` neither does anything from `presentation` or `i18n`; and in
`:presentation` neither does Compose HTML, the component library or `:data`.

Coroutines are the one library `:domain` has, because a repository reports change
as a `Flow`: the signed-in account, the account's settings, and preference
changes made in another tab. There are no listener-registration methods. The
stores and ViewModels collect those flows unconfined (`presentation/Follow.kt`),
so a change still reaches their state in the same call that made it.

## State holders

Two kinds, each for its own sort of state (the end of this section says which).

- **Stores** (`CatalogStore`, `SettingsStore`, `PreferencesStore`, `ToastStore`,
  the `Router`) keep Compose `mutableStateOf` fields that a screen reads directly.
- **ViewModels** extend `presentation/ViewModel.kt`: one immutable state value in
  a `StateFlow`, changed only through `setState`, so no reader sees half of a
  change. A test reads `state.value` with no Compose at all; a screen reads it
  with `collectAsState()`. A ViewModel that is replaced while the screen stays,
  such as the playback one at the next episode, is read with `collectState()`:
  `collectAsState()` would show the old one's last state until the new one
  changes.

`SessionViewModel` is the first ViewModel, and the root one all three shells
share: who is using the app, plus the work every shell root used to repeat for
itself. Settings sync follows the account, the catalog is loaded for whoever
arrives and dropped when nobody is using the app. All of it runs in the app scope
as the state changes, not in a composition, so it waits for no frame and does not
depend on which shell is on screen. A new viewer stops the last one's load before
starting their own, and the retry buttons call `retryCatalogLoad`.
`CatalogStore.ensureLoaded` joins a load already running, so a second caller waits
for it instead of fetching everything twice, and a stopped load is not recorded as
a failed one.

`PlaybackViewModel` is one per episode played, made by `PlaybackViewModelFactory`.
A watch screen gets it with `rememberPlaybackViewModel`; driving mode's player makes
one for each episode it opens. It holds the playback rules: which file plays, when
a position is saved and what it means, the one retry after a media error, the
resume target, the next-episode card and leaving for the next episode. The player
tells it what the media element did and supplies the position through a
`Playhead`. What only one platform needs stays in that platform's player: on the
web, the element, the iOS resume retry loop, the AirPlay clock and the `#t=` start
fragment; on TV, the one resume seek; in driving mode, its own resume and the media
session. Saves run in the app scope, so a save already sent still lands after the
player has moved on; the resolve and the retry stop with it. All three players use
it: the web, TV and driving mode.

`DashboardViewModel` and `LoginViewModel` are one per visit to their screen, made
by a factory the same way. The dashboard's holds the picked season, the question
on screen and the busy flag, and makes the progress changes and the catalog
rebuild. The sign-in form's holds what was typed, the mode and the busy flag, and
sends the requests; the web, TV and driving mode share it through `LoginScreen`.
Both run their work in the app scope, so leaving the screen does not stop a change
halfway, and both say how it went through `ToastStore`, in the words of `S`.
Playback is the exception: each player words its own failures.

New state goes into a ViewModel. The stores stay for state the whole app shares
(the catalog, settings, preferences, toasts and the route), which is no one
screen's. What only the markup needs, such as a menu being open or where the
remote's focus was, stays in the screen.

A few things a build file cannot keep out, so they are still greps. Every one of
these returns nothing.

- **The browser APIs.** Kotlin/JS ships them with the standard library, to every
  module. The browser handles that `Router` and `Log` used to reach for now live
  in `jsMain` source sets (`HashRouter` in `:presentation`, the console and the
  clock in `:core`).
- **Compose HTML and the component library in `:presentation`.** An import does
  not resolve today, but only because its build file does not list them, so the
  grep reads the build file too.
- **keel in `:presentation-new`.** The same reason: keel is in the build, so
  only the module's build file keeps it out, and the grep reads that file too.
  A keel class written as a literal is caught by `tools/check-css-classes.py`,
  because the `/next/` pages link no keel sheet.
- **`ui/` naming `:data`.** The root module depends on `:data`, because the
  composition roots (`di/`, `Main.kt`) build the repositories. `di/` holds both
  `AppGraph` and the fixture graph. `ui/` reaches data through the domain
  interfaces only.

```bash
S=presentation/src/commonMain/kotlin/ge/dakalebi
grep -rn "kotlinx.browser\|org.w3c\|dynamic" domain/src/
grep -rn "kotlinx.browser\|org.w3c\|compose.web\|dynamic" core/src/commonMain/ data/src/commonMain/ $S
grep -rn "compose.web\|firebase" core/src/commonMain/ data/src/commonMain/ $S
grep -rn "keel\|compose.web" presentation/ --exclude-dir=build
grep -rn "io.github.bchmsl" presentation-new/ --exclude-dir=build
grep -rn "import ge.dakalebi.data" src/jsMain/kotlin/ge/dakalebi/ui
```

## Where a change goes

`domain/...` paths below are in `:domain` and `data/...` paths in `:data`. Other
paths are in `:presentation` unless they say root.

- **A new screen** → root `ui/`, reading stores and ViewModels from
  `di/Locals.kt`.
- **New screen or app state** → a ViewModel in `presentation/` (a
  `ViewModel<S>` over an immutable `S`), built in root `di/AppGraph.kt`, rather
  than another store.
- **A new thing to store** → a method on an interface in
  `domain/repository/Repositories.kt`, an implementation in `data/`, and one
  line in root `di/AppGraph.kt`.
- **A new setting** → one line in the `Setting` registry in
  `domain/model/UserSettings.kt`, which makes it sync across devices with no other
  code, plus its key and type in the `users/{uid}` allowlist in
  `firebase/firestore.rules` (publish the rules first). Only a preference that
  describes a screen rather than a person goes on `PreferencesRepository` instead.
- **A cache, or anything else about where data is kept** → `data/` only. Nothing
  above `:data` names the catalog cache or the settings device copy: the
  repository that owns one reads and writes it.
- **A new rule about existing data** → a use case in `domain/usecase/`, with a
  test. This is the case worth being strict about: rules that live in a
  repository or a composable can only be checked by running the app.
- **New user-facing words** → `i18n/Strings.kt` first; the compiler then
  refuses to build until every locale has them.
- **A new shell** (a page like `/tv/` or `/drive/`) → root
  `src/jsMain/resources/<name>/index.html` with `data-shell="<name>"` and its own
  stylesheet, a value in `Shell` and its branch in `Main.kt`, and the page and its
  root composable in `SHELL_PAGES` / `SHELL_ROOTS` in `tools/check-css-classes.py`.
  It is a folder in the one distribution, so the deploy needs no new step. Gate
  it on `sessionState().viewer` and file progress under its `viewerId`, so
  guests work there too. Settings sync, loading the catalog and dropping it on
  sign-out already follow the session.
- **Something a second platform would do differently** → an `expect` beside the
  three that already exist, and resist it. Everything else in `commonMain` is
  plain Kotlin, and that is why the tests can run in a second.

## Testing

Each module tests itself in its own `src/commonTest`, in the package of the code
under test, and `./gradlew allTests` runs them all on Node: no browser, no Karma.
`:data` also has one `jsTest`, for the bare GET. The fakes the modules share are in
`:testing` (see the table above). `:presentation`'s tests also run the real
settings sync from `:data` over those fakes, as a test dependency only. Together
they cover the watched-threshold and never-rewind-a-finished-episode rules, the "continue
watching" and next-to-play choices, episode ordering across season boundaries,
hash-route parsing, settings sync (the merge rules, `SyncedSettingsRepository`,
`SettingsStore`, the device copy), the session (guests, and what follows the
account: settings sync and dropping the catalog), guest storage, playback-failure classification,
the playback rules (`PlaybackViewModel`: the file, the stored links TV and driving
mode start from, saves, the one retry, resume and the next-episode card), the
quality default, the iOS resume fragment, the VLC links and playlist, TV seeking
and interface size, timestamp formatting, toast dismissal, and the catalog cache.

Two of those earn their place beyond the coverage they add:

- **`ErrorMessagesTest`** pins the Firebase **JS** error-code spellings. Nothing
  in the types requires them, so a second platform whose SDK says
  `PERMISSION_DENIED` would compile and then answer every failure with the same
  generic sentence. Deleting the mapping fails exactly two of its assertions,
  which is the property that makes it worth having.
- **`LocalCatalogCacheTest`** round-trips all thirteen `Episode` fields. A field
  the stored shape forgets would not fail to compile and would not fail to
  decode; it would just serve episodes with no runtime.

The root module has no tests. Compose HTML, the media element and Firestore all
need a real browser and a real session, and there is no fake for those here. Note
also that recomposition is driven by `requestAnimationFrame`: in a headless or
occluded browser it never runs, so the first paint is verifiable there and no
later state change is.

## Deliberate exceptions

- **`i18n.S` is a global.** It is a resource bundle behind an interface, already
  swappable through `I18n.use(tag)`. Routing it through a CompositionLocal
  would rewrite every line of UI text for no gain in substitutability.
- **Each player keeps its media state in one class.** The players are split by
  job, but the state machine at the centre of each (the element handles, the
  timers, the paint loop, the resume) stays whole, because the players are the one
  part of this app that cannot be exercised without a real browser and a playing
  video. None has a layering violation: the playback rules are in
  `PlaybackViewModel`.

  | Player | State and actions | Around it |
  |---|---|---|
  | Web, `ui/player/` | `CustomPlayerState` | `CustomVideoPlayer` (effects, layout), `CustomPlayerVideo` (the element and its events), `CustomPlayerControls` |
  | TV, `ui/tv/player/` and `next/tv/player/` | `TvPlayerState` | `TvVideoPlayer` (input layers, layout), `TvPlayerInput` (what each key does), `TvPlayerVideo`, `TvPlayerChrome` |
  | Driving mode, `next/drive/` | `DrivePlayer` (not Compose) | `DriveMediaSession`, `ScreenWakeLock`, `VideoFullscreen.kt` |

  `next/` is `:presentation-new`. The parts with no keel moved there so the
  redesign can use them: `TvPlayerState`, `TvPlayerInput`, `TvPlayerVideo` and the
  whole driving-mode player. The screens around them stay in root `ui/`.

  The two Compose players hold their state in a plain class kept with `remember`,
  so the same state objects live as long as the old single composable's did and
  the effects run in the same order. The TV focus path still needs no
  recomposition: the key handling in `TvPlayerInput` moves the ring with direct
  `SpatialNav` calls, as it did before the split.
- **`Firebase` is an object, not injected.** It is a lazily-initialised handle
  to the SDK, held inside `data/firebase` and named by nothing outside it. The
  repositories in front of it are what the rest of the app depends on.
- **`SpatialNav` is an object with one mutable subscriber, and the TV UI writes
  some classes straight to the DOM.** Both are the same exception: on the TV
  surface, focus must not depend on a recomposition. Compose HTML's frame clock is
  `requestAnimationFrame`, which a browser stops entirely when the page is hidden —
  an Android WebView without Android focus is exactly that — so anything on the
  focus path that needs a frame is something that silently stops working. Hence
  `SpatialNav.onFocusChanged`, and hence `TvApp` toggling the navigation rail's
  `open` class itself rather than deriving it from state. It also happens to be
  faster: sweeping a sixty-tile grid recomposes nothing.

## The one asymmetry in the TV focus engine

Worth calling out because it looks like a bug and is not. In
`next/tv/focus/SpatialNav.kt` (`:presentation-new`), a **rightward** press cannot
leave an `X` or `Grid` group, and a **leftward** press can:

```
$ grep -A4 'private fun mayLeave' presentation-new/src/jsMain/kotlin/ge/dakalebi/next/tv/focus/SpatialNav.kt
```

Running out of shelf on the right is a wall because there is genuinely nothing over
there, and letting a press escape produced the worst bug this engine had: reaching
the last tile of a row and being teleported into an unrelated band. On the left
there is always exactly one thing — the navigation rail — and reaching it from a
shelf is the entire interaction model of a left-navigation app.

`leaveGroup` drops the row-overlap requirement for horizontal moves for the same
reason. A rail's *container* spans the screen height but its items do not, so no
rail item shares a row with a shelf in the middle of the screen; requiring overlap
made the rail unenterable and unleaveable. `mayLeave` already guards the case
overlap was protecting, so it was belt over braces.

## Two things the ring cannot be trusted to survive on its own

The ring is a DOM attribute written outside the composition, which is what makes a
keypress cost no recomposition. The price is that two ordinary events take it away,
and neither is a navigation. `TvInput`'s focus guardian exists for both.

- **A recomposition of the focused control.** Compose HTML re-applies an element's
  attributes by clearing what is there first, so `data-tv-focus` and the roving
  `tabindex` go with the clear — from a node that is otherwise the same node.
  Nothing is added or removed, so a `childList` watch cannot see it; the guardian
  watches `data-tv-focus` itself as well, and `SpatialNav.lastFocused` is what lets
  it put the ring back where it was rather than choosing a new home for it.
- **A forward route change.** A same-document fragment navigation fires `popstate`
  before `hashchange`, so "an unexplained popstate is a Back press" was false for
  every forward move the app made. `TvInput` marks each history entry it stands on,
  and only a traversal onto a marked entry is Back; `HashRouter.replace` carries an
  entry's existing state across for the same reason.

## Interface size is a CSS custom property, not state

`tv.css` is written entirely in `rem` off one root font size, so the only way to
resize the whole television interface is to change what a `rem` is — and the root
element is outside the composition. `next/tv/TvScale.kt` (`:presentation-new`) writes `--tv-scale` onto
`document.documentElement` and the browser relays every length that hangs off it,
which is cheap enough to apply on each press while the viewer is choosing.

It is deliberately **not** account-synced. Language, autoplay, System player and
preferred quality follow the account to every device; a comfortable
size describes a screen, so syncing it would make choosing on the phone wrong on the
television. Hence `PreferencesRepository`, not `SettingsStore`. `--safe-x` and
`--safe-y` are excluded from the multiplier: overscan is a property of the panel.
