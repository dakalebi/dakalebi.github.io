# Architecture

The app is a static Kotlin/JS bundle on GitHub Pages talking straight to
Firebase from the browser. There is no server, so the only structure it has is
the one in the source tree.

## Two modules

The layering below used to be a convention inside one module. It is now a module
boundary, which the compiler enforces rather than a grep.

| Module | Holds | Why |
|---|---|---|
| `:shared` | `commonMain`: `domain`, `presentation`, `i18n`, `core`, the composition locals, the Formula DTOs, the catalog cache. `jsMain`: three `.js.kt` files | Everything that is not about being a web page, so a second front end reuses it instead of reimplementing it |
| root | `ui` (Compose HTML), `data/firebase`, `data/local`, the composition root, `Main.kt` | The web app. It also owns the webpack build, so the GitHub Pages artifact path never moves |

The root project stays the application deliberately: renaming it to `:web` would
move the Pages artifact out of `build/dist/js/productionExecutable`, and
`upload-pages-artifact` succeeds on an empty path. That failure publishes a
working-looking 404.

`commonMain` has no `expect` for convenience. There are exactly three, and each
one exists because a shared library would break something documented: the
console (`platformLog`), the clock (`nowMillis`, `dateParts`), and a bare GET
(`httpGetText`, where any HTTP library's default headers would break Formula's
CORS contract — see the KDoc on it).

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
| `domain` | nothing but Kotlin | models, repository **interfaces**, a use case per operation, pure queries |
| `data` | `domain`, `core` | Firestore, the Formula API, `localStorage` |
| `presentation` | `domain`, `core`, `i18n` | Compose state holders, error→text mapping, the `Route` type |
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

Checking the rules holds is a grep, not a ceremony. Every one of these returns
nothing, including for `core` and `presentation` — the browser handles that
`Router` and `Log` used to reach for now live in the root module and in
`shared/src/jsMain`:

```bash
S=shared/src/commonMain/kotlin/ge/dakalebi
grep -rn "androidx.compose\|firebase\|kotlinx.browser\|i18n" $S/domain/
grep -rn "kotlinx.browser\|org.w3c\|compose.web\|dynamic" $S/core/ $S/presentation/ $S/data/
grep -rn "compose.web\|firebase" $S
```

The one thing a grep cannot check is the module boundary itself, and it does not
need to: `:shared` has no dependency on the root, so a reference the wrong way
round does not compile.

## Where a change goes

Paths below are in `:shared` unless they say root.

- **A new screen** → root `ui/`, reading stores from `di/Locals.kt`.
- **A new thing to store** → a method on an interface in
  `domain/repository/Repositories.kt`, an implementation in root `data/`, and one
  line in root `di/AppGraph.kt`.
- **A new rule about existing data** → a use case in `domain/usecase/`, with a
  test. This is the case worth being strict about: rules that live in a
  repository or a composable can only be checked by running the app.
- **New user-facing words** → `i18n/Strings.kt` first; the compiler then
  refuses to build until every locale has them.
- **Something a second platform would do differently** → an `expect` beside the
  three that already exist, and resist it. Everything else in `commonMain` is
  plain Kotlin, and that is why the tests can run in a second.

## Testing

`shared/src/commonTest` runs on Node via `./gradlew jsNodeTest` — no browser, no
Karma, about a second. 60 tests over: the watched-threshold and
never-rewind-a-finished-episode rules, the "continue watching" choice, episode
ordering across season boundaries, hash-route parsing, the cross-device settings
merge, the admin lookup, timestamp formatting, toast dismissal, and the catalog
cache.

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
- **`ui/player/CustomVideoPlayer.kt` is still one 600-line composable.** It has
  no layering violations — it takes a URL and a callback bundle and touches
  nothing but the DOM. Splitting it means cutting a dense media state machine
  apart, and it is the one part of this app that cannot be exercised without a
  signed-in session and a playing video.
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
`ui/tv/focus/SpatialNav.kt`, a **rightward** press cannot leave an `X` or `Grid`
group, and a **leftward** press can:

```
$ grep -A4 'private fun mayLeave' src/jsMain/kotlin/ge/dakalebi/ui/tv/focus/SpatialNav.kt
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
