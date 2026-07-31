# ჩემი ცოლის დაქალები — სანახავი დაფა

Kotlin/JS rewrite of the TV-series watch dashboard. Static bundle on GitHub Pages,
Firebase Auth + Firestore for accounts and data, episode catalog pulled straight
from Formula's public API in the browser.

| | |
|---|---|
| Language | Kotlin 2.3.20 (Kotlin/JS, IR) |
| UI | Compose HTML (Compose Multiplatform 1.11.1) |
| Backend | Firebase Auth + Cloud Firestore (no server) |
| Hosting | GitHub Pages (static), hash routing |
| Source of episodes | `https://mw-api.formula.ge/formula` (CORS-open, called from the browser) |

## Build

```bash
./gradlew jsBrowserDistribution
```

Output lands in `build/dist/js/productionExecutable/`. Local dev server:

```bash
./gradlew jsBrowserDevelopmentRun --continuous
```

## Setup checklist

These steps need the Firebase and GitHub consoles, so they're yours to do — the
code is already written against them.

### 1. Firebase project

1. Create a project at <https://console.firebase.google.com>.
2. **Authentication → Sign-in method** → enable **Email/Password** and **Google**.
3. **Authentication → Settings → Authorized domains** → add your Pages domain
   (`<username>.github.io`, plus any custom domain). Sign-in fails silently without this.
4. **Firestore Database** → create in production mode, pick a region near you.
5. **Project settings → General → Your apps → Web app** → register one and copy the config.
6. Paste those values into `src/jsMain/kotlin/ge/dakalebi/firebase/FirebaseConfig.kt`.
   These keys are public by design — they identify the project, they don't authorize
   anything. Access control lives entirely in the rules.

### 2. Firestore rules

Sign in once so your account exists, then copy your UID from
**Authentication → Users** into `firebase/firestore.rules` (replacing
`REPLACE_WITH_YOUR_FIREBASE_UID`) and publish the rules in
**Firestore → Rules**.

Only that UID can write the episode catalog; everyone signed in can read it and
write their own watch progress.

### 3. GitHub Pages

1. Push to `main`.
2. **Settings → Pages → Build and deployment → Source: GitHub Actions**.
3. The workflow in `.github/workflows/deploy.yml` builds and deploys on every push.

## Data model

```
episodes/{formulaEpisodeId}
  seasonNumber, episodeNumber, formulaSeasonId, title,
  thumbnailUrl, videoUrl, sources{}, durationSeconds,
  episodePageUrl, lastResolvedAt, updatedAt

users/{uid}/progress/{episodeId}
  progressSeconds, durationSeconds, isWatched, lastWatchedAt

meta/catalog
  lastRefreshAt, seasonCount, episodeCount
```

Document IDs are Formula's own episode ids, so watch URLs (`#/watch/531`) stay
stable across a full catalog rebuild.

## Notes

- Video is never rehosted. The app reads Formula's public JSON and plays the
  official MP4 URLs from `cdn.formula.ge` directly.
- The `<video>` element must **not** carry a `crossorigin` attribute — the CDN
  sends no CORS headers, and adding it would break playback.
- iPhone and iPad get Apple's native player; every other device gets the custom
  player.
