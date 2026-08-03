# ჩემი ცოლის დაქალები — სანახავი დაფა

A private watch dashboard for one 932-episode Georgian TV series. Static bundle
on GitHub Pages, Firebase for accounts and data, episodes pulled straight from
Formula's public API in the browser. No backend.

Live at <https://dakalebi.github.io/>.

| | |
|---|---|
| Language | Kotlin 2.3.20 (Kotlin/JS, IR) |
| UI | Compose HTML (Compose Multiplatform 1.11.1) |
| Backend | Firebase Auth + Cloud Firestore — no server |
| Hosting | GitHub Pages, hash routing |
| Episodes | `https://mw-api.formula.ge/formula` (CORS-open, called from the browser) |

## Start here

**[`docs/PROJECT-GUIDE.md`](docs/PROJECT-GUIDE.md)** — what this is, which
decisions were made and why, how each subsystem works, and the traps that have
already cost time. Read it before changing behaviour.

| | |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | the layering, and the rule that keeps it |
| [`docs/TEST-PLAN.md`](docs/TEST-PLAN.md) | the manual, signed-in test plan |
| [`docs/BUG-REPORT.md`](docs/BUG-REPORT.md) | defects found and fixed, with root causes |

## Build and test

```bash
./gradlew jsBrowserDistribution
```

Output lands in `build/dist/js/productionExecutable/`.

```bash
./gradlew jsNodeTest
```

35 domain tests, about a second, no browser. The domain layer is plain Kotlin —
no Compose, no Firebase, no DOM — which is what makes that possible.

## Layout

```
src/jsMain/kotlin/ge/dakalebi/
  domain/        models, repository interfaces, use cases, pure queries
  data/          Firestore, the Formula API, localStorage
  presentation/  Compose state holders, error-to-text mapping
  ui/            Compose HTML only
  di/            the composition root
  core/          logging, formatting, generated build info
  i18n/          every user-facing string, one file per language
```

Dependencies point inwards and `domain` names no framework at all. The greps
that check this are in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Data model

```
episodes/{formulaEpisodeId}   the shared catalog, admin-writable
meta/catalog                  lastRefreshAtMillis, seasonCount, episodeCount
users/{uid}                   language, autoplayNext — settings that follow the person
users/{uid}/progress/{id}     progressSeconds, durationSeconds, isWatched, lastWatchedAtMillis
admins/{uid}                  existence grants catalog-write; writable by nobody
```

Document ids are Formula's own episode ids, so `#/watch/531` survives a full
catalog rebuild.

`meta/catalog.lastRefreshAtMillis` is bumped by exactly the operation that
changes the catalog, which makes it the cache validator: one small read says
whether the other 932 are still current. Without it a page load cost 932 reads
and exhausted the free tier's daily 50,000 in an afternoon.

## Setup

The consoles are yours; the code is already written against them.

### Firebase

1. Create a project at <https://console.firebase.google.com>.
2. **Authentication → Sign-in method** → enable **Email/Password** and **Google**.
3. **Authentication → Settings → Authorized domains** → add the Pages domain.
   Sign-in fails silently without this.
4. **Firestore Database** → create in production mode.
5. **Project settings → General → Your apps → Web app** → register one and copy
   the config into
   `src/jsMain/kotlin/ge/dakalebi/data/firebase/FirebaseConfig.kt`. Those keys
   are public by design: they identify the project, they authorize nothing.
6. **Firestore → Rules** → paste `firebase/firestore.rules` and publish. These
   rules are the entire security boundary — there is no server behind them.
7. To grant catalog-refresh rights, create `admins/{uid}` by hand in the
   console. That collection is write-denied to every client, which is the
   point — a flag the user could write is a flag they could grant themselves.

### GitHub Pages

**Settings → Pages → Source: GitHub Actions.** `.github/workflows/deploy.yml`
builds and deploys on every push to `main`. If the source ever reverts to
`legacy`, the workflow goes green while the site 404s.

## Notes

- Video is never rehosted — the official MP4 URLs from `cdn.formula.ge` play
  directly.
- The `<video>` element must **not** carry `crossorigin`: the CDN sends no CORS
  headers and the attribute breaks playback.
- iPhone and iPad default to Apple's native player, with a switch in the drawer
  for the web player. Everywhere else gets the web player, which is the only one
  there is.
- Georgian UI chrome is set in Mtavruli from Kotlin, not CSS —
  `text-transform: uppercase` deliberately skips that mapping.
