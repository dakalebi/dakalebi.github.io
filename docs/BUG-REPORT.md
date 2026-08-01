# Bug report — signed-in QA sweep

Executed against [`docs/TEST-PLAN.md`](TEST-PLAN.md) on a local production build
(`http://localhost:4173`) in Chrome, signed in as the admin account. The
iPhone and iPad paths were exercised by overriding the platform signals
`isAppleMobile` reads, before it is first evaluated.

**13 defects found, 13 fixed and re-verified.** Two further symptoms were
investigated and dismissed as harness artefacts; both are recorded below,
because a sweep that only lists hits is not falsifiable.

| ID | Sev | Area | Summary | Status |
|---|---|---|---|---|
| B1 | High | F24/F25 | Quality menu is built from a different map than the quality label — order is arbitrary and worst-first | Fixed |
| B2 | High | L3/L4 | `Enter` and `Space` are stolen from every focused button on the watch screen | Fixed |
| B8 | High | K8 | The hero is wider than the viewport on every phone under ~453px | Fixed |
| B9 | High | I | AirPlay listeners are never registered on iOS — all six calls throw | Fixed |
| B3 | Medium | F16/L7 | The scrub `<input type=range>` value never tracks playback | Fixed |
| B4 | Medium | M1 | The autoplay switch has no knob — CSS targets `<i>`, markup renders `<div>` | Fixed |
| B6 | Medium | A8 | Dates render as `8/1/2026, 1:27:35 AM` in a Georgian-only UI | Fixed |
| B7 | Medium | C13 | 1280×720 stills are ignored in favour of 220×124 ones; an `.mp4` sits in the `<img>` fallback chain | Fixed |
| B11 | Medium | J1/J2 | A failed catalog load latches — no retry is possible without a page reload | Fixed |
| B13 | Medium | J1 | An unreachable backend renders as "the database is empty" | Fixed |
| B5 | Low | M1 | Toggle label never gets its heading style — CSS targets `<b>`, markup renders `<div>` | Fixed |
| B10 | Low | L5 | `Escape` does not close the menu sheet or any dialog | Fixed |
| B12 | Low | D8 | The permission error tells you to add a UID to rules that are keyed on email | Fixed |

Severity is about what a viewer actually loses: **High** breaks a documented
requirement or a whole interaction mode, **Medium** degrades the experience,
**Low** is cosmetic or advisory.

---

## B1 — Quality menu is built from a different map than the label

**Area** F24, F25 · **Severity** High

`WatchScreen` resolved fresh sources from Formula and derived the selected
quality from them, but handed the *stored* copy to the player:

```kotlin
val sources = resolved.sources                        // live, best-first
quality = preferred ?: sources.keys.firstOrNull()     // -> "1080p"
...
CustomVideoPlayer(sources = episode.sources, ...)     // <- Firestore copy
```

**Observed** on `#/watch/608` (season 1, episode 49 — one of the episodes with
all three renditions): the button read `1080p`, the menu listed
`360p · 720p · 1080p ✓`. Worst first, and the two disagreed about ranking.

**Root cause.** `Episode.sources` is a `Map` whose *order carried meaning* —
"best first" is what the default-quality pick relied on — and that order does
not survive a Firestore map field. The same document came back two different
ways on two consecutive reads:

```
raw REST body : "1080p", "720p", "360p"
parsed object : "360p",  "720p", "1080p"
```

**Fix.** Order is computed from the label's leading digits (`qualityRank`), so
it no longer depends on how the map was built or stored, and an unfamiliar
label from Formula sorts last instead of first. The resolved map is the only
one passed downstream.

**Re-verified.** Button `1080p`, menu `1080p ✓ · 720p · 360p`.

## B2 — `Enter` and `Space` are stolen from every focused button

**Area** L3, L4 · **Severity** High

The player's global `keydown` handler skipped the shortcut for text fields but
not for buttons or links, and calls `event.preventDefault()` before acting.
Those two keys are how a keyboard user activates whatever is focused.

**Observed.** Focus the quality button, press `Enter` → the menu does not open
and the video starts playing. Focus `ნანახად მონიშვნა`, press `Enter` → nothing
is marked, no toast, video starts playing. `Space` behaves the same. **Every
control on the watch screen was unusable by keyboard.**

**Fix.** `Space` and `Enter` are only claimed when focus is somewhere inert.
The arrow keys and `f`/`m` are untouched — they are not activation keys.

**Re-verified.** `Enter` on the quality button now opens the menu and leaves
playback alone; with focus on `<body>`, `Space` still toggles play and `→`
still seeks +10s.

## B8 — The hero is wider than the viewport on phones

**Area** K8 · **Severity** High

```css
.hero { min-height: 300px; aspect-ratio: 21 / 9; }
@media (max-width: 720px) { .hero { aspect-ratio: 4 / 3; min-height: 340px; } }
```

No `width`. With only an aspect ratio and a clamped height, the browser derives
the **width from the height**: 340 × 4/3 = 453px. Any viewport narrower than
that gets a horizontally scrolling page with the hero cut off — which is every
common phone (375, 390, 414).

**Observed** at a 385px content width: `document.scrollWidth` 453.

**Fix.** `width: 100%`, so the width is definite and the ratio drives the
height instead.

**Re-verified** at the same width: `scrollWidth` 385, hero 385×340, no overflow
on the dashboard or the watch screen.

## B9 — AirPlay listeners are never registered on iOS

**Area** I · **Severity** High

```kotlin
dyn.remote?.addEventListener?.invoke("connect", syncAirPlay)
```

On a `dynamic` value Kotlin emits `.invoke(...)` literally, and a JS function
has no `invoke` property. All six calls — three to add listeners, three to
remove them — threw `addEventListener.invoke is not a function`, so none of the
Remote Playback events were ever wired up and iOS AirPlay state was left to the
webkit-prefixed event alone.

This was completely silent until the logging commit; it is the clearest example
of what that pass was for.

**Fix.** Call the listener directly on the dynamic object.

**Re-verified.** Two full mount/teardown cycles of the native player with an
iPhone user agent produce zero `invoke` errors, where previously each one threw
on both mount and dispose.

## B3 — The scrub input's value never tracks playback

**Area** F16, L7 · **Severity** Medium

The visible bar is painted imperatively from a `requestAnimationFrame` loop,
which is what keeps 60fps recomposition off the table. The transparent
`<input type=range>` layered on top for interaction was never updated.

**Observed.** At 291s of 2219s the input still read `value="50"` against an
expected 131/1000.

Clicking the track still worked — the browser derives the value from the click
position — but arrow-key seeking stepped from the stale value, and the element
carries `aria-label="დროის ხაზი"`, so assistive technology announced a position
that was simply wrong.

**Fix.** Written from the same `paintBars()` pass that moves the fill, and
skipped while scrubbing so it cannot fight an in-progress drag.

**Re-verified.** `value` 64 against an expected 64 during playback.

## B4 — The autoplay switch has no knob

**Area** M1 · **Severity** Medium

`.switch i` and `.switch.on i` describe the white circle and its travel, but
the markup emits a `<div>`. Neither rule matched, so the control was a plain
grey pill with nothing inside it. Only the background colour changed on toggle,
which is the sole reason it read as a switch at all.

**Fix.** Selector changed to `.switch > div` to match what Compose emits.

**Re-verified.** Knob is `position: absolute`, 17px, white.

## B5 — Toggle label never gets its heading style

**Area** M1 · **Severity** Low

Same mistake one element over: `.toggle-row .lab b` sets 13.5px/600, markup
renders a `Div`, so the label fell back to body text and did not read as the
heading for the description under it.

**Re-verified.** Label computes to `600` at `13.5px`.

## B6 — Dates render in US format in a Georgian-only UI

**Area** A8 · **Severity** Medium

**Observed.** The menu footer read `ბოლო განახლება: 8/1/2026, 1:27:35 AM`.

**Root cause.** `toLocaleString("ka-GE")` does not fail when the locale is
absent, it silently falls back. This Chrome returns `[]` from
`Intl.DateTimeFormat.supportedLocalesOf(['ka'])` and resolves `ka-GE` to
`en-US`. `de-DE` formats correctly on the same browser, so ICU is present and
Georgian specifically is missing.

**Fix.** Formatted by hand as `dd.MM.yyyy, HH:mm` — day-first and 24-hour, and
identical on every runtime.

**Re-verified.** `ბოლო განახლება: 01.08.2026, 01:27`.

## B7 — The largest available still is never used

**Area** C13 · **Severity** Medium

```kotlin
listOf(imageURL, originalImageURL, videoThumbnailSrc).firstOrNull { … }
```

Two problems in one expression.

**Wrong preference.** Surveying all 932 episodes: every one has `imageURL`, and
it is **220×124**. **28** also carry `originalImageURL` at **1280×720**. Because
`imageURL` is first and never null, the larger image was unreachable. It is the
right *fallback* — the field that is always there — and the wrong first choice.

**A video in the image chain.** `videoThumbnailSrc` is an `.mp4`, e.g.
`cdn.formula.ge/trimmer/THUMBNAIL/28042021/9ea36339-….mp4`. 100 episodes carry
it. It was unreachable only because `imageURL` always won — so reordering alone
would have turned it into a broken `<img>` on the 24 episodes that have both.

**Fix.** Prefer `originalImageURL`, fall back to `imageURL`, drop
`videoThumbnailSrc` entirely.

**Re-verified.** After visiting episode 739, its tile renders at a natural
1280×720 instead of 220×124.

> Stored documents keep their old thumbnail URL until the next catalog refresh;
> the live resolve picks the better one immediately, which is how this was
> verified. No refresh was run against production data for this pass.

## B11 — A failed catalog load can never be retried

**Area** J1, J2 · **Severity** Medium

```kotlin
if (loadedFor == uid && !loading) return
```

That is also true after a failure, so one failed read latched for the lifetime
of the page: `loadError` stayed on screen and nothing could call the load again.
There was no retry affordance either, so the only way out was a manual reload.

**Fix.** The guard re-enters while an error is set, and the error state now has
a `თავიდან ცდა` button.

**Re-verified** with Firestore blocked at the transport layer: retry while still
blocked stays on the error screen; lifting the block and pressing retry loads
the full dashboard — 18 chips, 42 tiles — with no page reload.

## B13 — An unreachable backend renders as "the database is empty"

**Area** J1 · **Severity** Medium

Found while trying to reach B11's error path, by blocking
`firestore.googleapis.com`. No error appeared at all — which was itself the bug.

Firestore does not reject an offline read; it resolves from its cache, which is
empty here because persistence is off. `listEpisodes` returned an empty list,
the dashboard treated that as success, and the offline state rendered as
`ბაზაში სერიები ჯერ არ არის` — telling a viewer to wait for an admin who has
nothing to fix, and inviting the admin to run a refresh that cannot work either.

**Fix.** `snapshot.metadata.fromCache` is the only signal separating "the server
says there is nothing" from "we never reached the server". An empty result that
never reached the server is now an error and lands on the retry screen.

**Re-verified.** Offline load now shows `ჩატვირთვა ვერ მოხერხდა` with the retry
button.

## B10 — `Escape` does not close overlays

**Area** L5 · **Severity** Low

Every modal was dismissible only by clicking the scrim — the one thing a
keyboard user cannot reach once focus is inside the dialog.

**Fix.** A shared `DismissOnEscape` used by the confirm dialogs and the menu
sheet, holding the callback through `rememberUpdatedState` so a re-rendered
parent cannot leave a stale closure behind.

**Re-verified.** `Escape` closes the menu sheet.

## B12 — Permission error names an allowlist that no longer exists

**Area** D8 · **Severity** Low

A refresh rejected for permissions said
`საჭიროა ადმინის UID წესებში` — add the admin's UID to the rules. The allowlist
has been keyed on the **verified email** since the project was wired up, so that
sent whoever hit it to the wrong place. Network failures also fell through to
the generic "refresh failed".

**Fix.** Correct wording for the email allowlist, plus a distinct message for
network failures, matched on the Firebase `code` as well as the message text.

---

## The logging pass, in practice

The first commit routed all 25 swallowed `catch`/`runCatching` sites through a
`Log` helper with graded severity, and added `window.onerror` /
`unhandledrejection` handlers.

It paid for itself twice in this sweep. A forced refresh failure now prints a
stage-by-stage trail instead of a three-second toast:

```
dakalebi/refresh reading existing catalog
dakalebi/refresh existing=932, fetching seasons
dakalebi/library catalog refresh failed  TypeError: Failed to fetch
```

And **B9 was found purely because the logging made it visible** — six calls that
had been throwing on every iOS player mount and teardown, silently, since the
file was written.

## Verified as correct

- **Requirement — always-visible progress bar.** The thin bar appears exactly
  when the control bar hides and tracks position; the buffered bar correctly
  leads the playhead.
- **Requirement — next-episode suggestion with a countdown.** Card appears at
  180s remaining with the next episode's still, title and season/episode; the
  bar read 46.48% at 96s remaining against an expected 46.67%. With autoplay on,
  playback advanced 608 → 609 at the end on its own.
- **Requirement — native player on iPhone and iPad.** Both get `<video controls>`
  with no custom chrome, no quality button and no next-episode overlay. The iPad
  case correctly relies on `MacIntel` + touch points, not the user agent.
- **Progress.** Saves on pause, on navigating away, and on reaching the end;
  resumes exactly (302s restored of 1931s); marks watched at 90%; mark-season,
  reset-season and reset-all each verified against Firestore.
- **Quality switch** preserves position and play state (602s → 607s, still
  playing) and persists the preference.
- **Routing.** Unknown episode id, `#/watch` with no id, and unknown routes all
  degrade correctly; the last episode of season 18 offers no next episode.
- **Catalog** loads 932 episodes across 18 seasons; grid header, chip ticks and
  continue-rail counts all agree with the tiles.
- **1080p is genuinely selected** where it exists. Episodes showing `720p`
  (such as `#/watch/29`) have no FHD rendition in the API at all.
- **Focus is visible** — 2px outline with a 2px offset.

## Not verifiable in this environment

- **I10 — the AirPlay wall-clock workaround.** Needs a real Apple TV. B9 fixes
  the listener registration that feeds it, but the extrapolation itself remains
  untested — not "passing".
- **Real iOS Safari.** The iPhone/iPad paths were verified by overriding the
  platform signals in Chrome, which exercises the substitution logic but not
  WebKit's own fullscreen and AirPlay behaviour.

## Investigated and dismissed

- **"The app hangs on `იტვირთება...` after a reload."** Reproduced on the
  deployed site too, which made it look like a shipped regression. It was the
  harness: Chrome was occluded, `document.visibilityState` was `hidden`, and
  Compose HTML drives recomposition from `requestAnimationFrame`, which browsers
  pause in hidden tabs. Foregrounding Chrome resumed it instantly. Everything
  afterwards was run with the window frontmost.
- **"The copy-link button gives no feedback."** `navigator.clipboard.writeText`
  hangs without a real user gesture, so a scripted `.click()` never settled the
  promise. With a genuine click the toast appears and the tile does not
  navigate.

## Observation, not filed as a bug

The hero is full-bleed, and for 904 of 932 episodes the only still Formula
offers is 220×124 — upscaled roughly 7× at desktop width. B7 fixes the 28
episodes where a 1280×720 image exists; the rest are an upstream limit, not a
defect. Worth knowing if the hero treatment is ever revisited.
