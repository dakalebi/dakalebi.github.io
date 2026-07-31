# Bug report — signed-in QA sweep

Executed against [`docs/TEST-PLAN.md`](TEST-PLAN.md) on a local production build
(`http://localhost:4173`) in Chrome, signed in as the admin account, plus Safari
in the iOS Simulator for the iPhone/iPad cases.

| ID | Sev | Area | Summary | Status |
|---|---|---|---|---|
| B1 | High | F24/F25 | Quality menu is built from a different map than the quality label — order is arbitrary and worst-first | Fixed |
| B2 | High | L3/L4 | `Enter` and `Space` are stolen from every focused button on the watch screen | Fixed |
| B3 | Medium | F16/L7 | The scrub `<input type=range>` value never tracks playback | Fixed |
| B4 | Medium | M1 | The autoplay switch has no knob — CSS targets `<i>`, markup renders `<div>` | Fixed |
| B5 | Low | M1 | Toggle label never gets its heading style — CSS targets `<b>`, markup renders `<div>` | Fixed |
| B6 | Medium | A8 | Dates render as `8/1/2026, 1:27:35 AM` in a Georgian-only UI | Fixed |
| B7 | Medium | C13 | 1280×720 stills are ignored in favour of 220×124 ones; an `.mp4` sits in the `<img>` fallback chain | Fixed |

Severity is about what a viewer actually loses: **High** breaks a documented
requirement, **Medium** degrades the experience, **Low** is cosmetic.

---

## B1 — Quality menu is built from a different map than the label

**Area** F24, F25 · **Severity** High

`WatchScreen` resolves fresh sources from Formula and derives the selected
quality from them, but hands the *stored* copy to the player:

```kotlin
val sources = resolved.sources                        // live, best-first
quality = preferred ?: sources.keys.firstOrNull()     // -> "1080p"
...
CustomVideoPlayer(sources = episode.sources, ...)     // <- Firestore copy
```

Two different maps in two different orders.

**Observed** on `#/watch/608` (season 1, episode 49 — one of the episodes that
has all three renditions): the button reads `1080p`, the menu lists
`360p · 720p · 1080p ✓`. Worst first, and the two disagree about ranking.

**Root cause.** `Episode.sources` is a `Map` whose *order carries meaning* —
"best first" is what `bestVideoUrl()` and the default-quality pick both rely on.
That order does not survive a round trip through a Firestore map field.
Fetching the same document twice returned the keys in two different orders:

```
raw REST body : "1080p", "720p", "360p"
parsed object : "360p",  "720p", "1080p"
```

So the ordering was never guaranteed; it happened to look right whenever the
live resolve succeeded and its result was the one being read.

**Fix.** Stop treating map order as data. `qualitySources()` now returns a map
ordered by an explicit rank, the player sorts what it is given through that same
rank before rendering, and `WatchScreen` passes one map — the resolved one — to
both the label and the menu.

## B2 — `Enter` and `Space` are stolen from every focused button

**Area** L3, L4 · **Severity** High

The player's global `keydown` handler skips the shortcut when focus is in a text
field:

```kotlin
if (tag == "INPUT" || tag == "TEXTAREA" || tag == "SELECT" ||
    target?.isContentEditable == true
) return@handler
```

`BUTTON` and `A` are not in that list, and the handler calls
`event.preventDefault()` before acting. `Space` and `Enter` are exactly how a
keyboard user activates a focused button, so the default action is cancelled and
playback toggles instead.

**Observed.** Focus the quality button, press `Enter` → the menu does not open
and the video starts playing. Focus `ნანახად მონიშვნა`, press `Enter` → the
episode is not marked watched, no toast, and the video starts playing. `Space`
behaves the same. **Every control on the watch screen is unusable by keyboard.**

**Fix.** Treat any natively-activatable element as off-limits for `Space` and
`Enter` — buttons, links, and anything with an explicit `role` — rather than
listing text inputs. The arrow keys and `f`/`m` keep working, since those are
not activation keys.

## B3 — The scrub input's value never tracks playback

**Area** F16, L7 · **Severity** Medium

The visible bar is painted imperatively from a `requestAnimationFrame` loop,
which is what keeps 60fps recomposition off the table. The transparent
`<input type=range>` layered on top for interaction is never updated to match.

**Observed.** At `currentTime` 291s of 2219s the input still read `value="50"`
where the position was 131/1000.

**Consequences.** Arrow keys on the focused scrub bar jump relative to a stale
position rather than nudging from the current one, and assistive technology
reads out the wrong position — the element's `aria-label` says `დროის ხაზი`, so
it is announced as a slider with a value that is simply wrong.

**Fix.** Write the input's `value` from the same `paintBars()` pass that moves
the fill, skipping it while the user is actively scrubbing so it does not fight
the drag.

## B4 — The autoplay switch has no knob

**Area** M1 · **Severity** Medium

```css
.switch i    { /* the white circle */ }
.switch.on i { transform: translateX(17px); }
```

```kotlin
Div({ classNames("switch", if (Prefs.autoplayNext) "on" else null) }) { Div() }
```

The selector wants an `<i>`; the markup emits a `<div>`. Neither rule ever
matches, so the control is a plain grey pill with nothing inside it and no
travel between states. The background does turn red when on, which is the only
reason it reads as a toggle at all.

**Fix.** Render the knob as the `<i>` the stylesheet already describes.

## B5 — Toggle label never gets its heading style

**Area** M1 · **Severity** Low

Same class of mistake, one element over: `.toggle-row .lab b` sets the label to
13.5px/600, but the markup renders a `Div`. The label falls back to inherited
body text, so it does not read as the heading for the description beneath it.

**Fix.** Render it as the `<b>` the stylesheet expects.

## B6 — Dates render in US format in a Georgian-only UI

**Area** A8 · **Severity** Medium

`formatDateTime` asks for Georgian and takes whatever it gets:

```kotlin
Date(millis).toLocaleString("ka-GE")
```

**Observed.** The menu footer reads `ბოლო განახლება: 8/1/2026, 1:27:35 AM`.

**Root cause.** This Chrome has no Georgian locale data at all —
`Intl.DateTimeFormat.supportedLocalesOf(['ka'])` returns `[]`, and
`new Intl.DateTimeFormat('ka-GE').resolvedOptions().locale` resolves to `en-US`.
`de-DE` formats correctly on the same browser, so ICU is present and Georgian
specifically is missing. The fallback is silent, which is why it shipped.

**Fix.** Do not depend on the runtime carrying `ka`. Format explicitly as
`dd.MM.yyyy, HH:mm` — day-first and 24-hour, which is what the rest of the UI
implies, and identical on every browser.

## B7 — The largest available still is never used

**Area** C13 · **Severity** Medium

```kotlin
fun FormulaEpisode.thumbnailUrl(): String? =
    listOf(imageURL, originalImageURL, videoThumbnailSrc)
        .firstOrNull { !it.isNullOrBlank() }
```

Two problems in one expression.

**Wrong preference.** Surveying all 932 episodes: every one has `imageURL`, and
it is a **220×124** thumbnail. **28** also carry `originalImageURL` at
**1280×720**. Because `imageURL` is listed first and is never null, the larger
image is unreachable — those 28 episodes render a thumbnail where a real still
exists. The original comment justified `imageURL` as the field that is always
present, which makes it the right *fallback*, not the right *first choice*.

**A video in the image chain.** `videoThumbnailSrc` is not an image. All four
sampled values are `.mp4` files, e.g.
`cdn.formula.ge/trimmer/THUMBNAIL/28042021/9ea36339-….mp4`. 100 episodes carry
it. It is unreachable today only because `imageURL` always wins — so correcting
the order alone would have turned this into a broken `<img>` on 24 episodes.

**Fix.** Prefer `originalImageURL`, fall back to `imageURL`, and drop
`videoThumbnailSrc` from the chain entirely.

---

## Verified as correct

Recording these so the sweep is falsifiable rather than a list of complaints.

- **Requirement — always-visible progress bar.** The thin bar appears exactly
  when the control bar hides and tracks position (`4.73%` fill at 105s of
  2219s); the buffered bar correctly leads the playhead at `4.83%`.
- **Requirement — next-episode suggestion with a countdown.** Card appears at
  180s remaining with the next episode's still, title and `სეზონი 15 · სერია 2`;
  the bar read `46.48%` at 96s remaining against an expected 46.67%.
- **Quality switch preserves position and play state** — 602s → 607s, still
  playing, `src` swapped, preference written to `localStorage`.
- **Watched threshold** fires at 90%.
- **Controls auto-hide** after 2.5s of stillness during playback.
- **Catalog** loads 932 episodes across 18 seasons; grid header, chip count and
  continue-rail count all agree with the tiles.
- **1080p is genuinely selected** where it exists — the original app's
  720p-always bug has not regressed. Episodes that show `720p` (such as
  `#/watch/29`) have no FHD rendition in the API at all.

## Not verifiable in this environment

- **I10 — the AirPlay wall-clock workaround.** Needs a real Apple TV; the
  Simulator has no receiver. Untested, not "passing".

## Investigated and dismissed

Two symptoms looked like serious bugs and were not. Both are recorded because
the evidence trail matters more than the count.

- **"The app hangs on `იტვირთება...` after a reload."** Reproduced on the
  deployed site too, which made it look like a shipped regression. It was the
  harness: Chrome was in the background, `document.visibilityState` was
  `hidden`, and Compose HTML drives recomposition from `requestAnimationFrame`,
  which browsers pause in hidden tabs. Foregrounding Chrome resumed it
  instantly. Every subsequent test was run with the window frontmost.
- **"The autoplay toggle does not respond."** Same cause. With the tab visible
  the class flips to `switch on` and the background turns red correctly. What
  survived from that investigation is B4 — the knob really is missing, for an
  unrelated reason.
