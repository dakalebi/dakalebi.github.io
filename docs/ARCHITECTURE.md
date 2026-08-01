# Architecture

The app is a static Kotlin/JS bundle on GitHub Pages talking straight to
Firebase from the browser. There is no server, so the only structure it has is
the one in the source tree.

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
| `presentation` | `domain`, `core`, `i18n` | Compose state holders, error→text mapping |
| `ui` | `presentation`, `domain`, `core`, `i18n`, `di` | Compose HTML, and nothing else |
| `di` | everything | the composition root |
| `core` | nothing but Kotlin | logging, formatting, generated build info |

Two rules carry most of the weight:

- **`domain` names no framework.** No Compose, no Firebase, no DOM, no `i18n`.
  It compiles and runs under Node, which is why it can have tests.
- **`data` names no UI.** Repositories throw typed failures; turning those into
  Georgian or English sentences is `presentation/ErrorMessages`. Before the
  restructure every repository built its own message, so translating the app
  meant editing Firestore code.

Checking the rules holds is a grep, not a ceremony:

```bash
grep -rn "androidx.compose\|firebase\|kotlinx.browser\|i18n" src/jsMain/kotlin/ge/dakalebi/domain/
```

## Where a change goes

- **A new screen** → `ui/`, reading stores from `di/Locals.kt`.
- **A new thing to store** → a method on an interface in
  `domain/repository/Repositories.kt`, an implementation in `data/`, and one
  line in `di/AppGraph.kt`.
- **A new rule about existing data** → a use case in `domain/usecase/`, with a
  test. This is the case worth being strict about: rules that live in a
  repository or a composable can only be checked by running the app.
- **New user-facing words** → `i18n/Strings.kt` first; the compiler then
  refuses to build until every locale has them.

## Testing

`src/jsTest` runs on Node via `./gradlew jsNodeTest` — no browser, no Karma,
about a second. It covers the domain: the watched-threshold and
never-rewind-a-finished-episode rules, the "continue watching" choice, episode
ordering across season boundaries, and hash-route parsing.

Everything above the domain is still verified by hand. Compose HTML, the media
element and Firestore all need a real browser and a real session, and there is
no fake for those here.

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
