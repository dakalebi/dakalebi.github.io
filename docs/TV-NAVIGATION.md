# TV navigation — the reference model, and what the app actually does

**Status: researched, measured, fixed, and re-measured.** It exists so the D-pad
behaviour of the TV shell can be judged against how established television apps behave,
rather than against whatever the engine happened to do first.

§2 is the sourced reference model. §5 is every case run against the running app on
2026-09-10, pass and fail, **before** any fix. §6 lists the defects those runs found,
worst first. §7 is the fixing plan, one entry per defect, and §7.1 is the same cases
re-run afterwards. The cases in §5 are meant to be folded into section N of
`TEST-PLAN.md`.

Sources were fetched on 2026-09-10. Every statement below is tagged:

- **documented** — stated on a vendor or standards page, linked in §1;
- **reported** — described by named press or a secondary write-up, not by the vendor;
- **inference** — my reading, not written anywhere fetched.

The two apps named as the bar — YouTube on TV and Netflix — publish almost nothing
about focus mechanics. What they do publish is here; the rest of the model comes from
the platform vendors' guidance and from the spatial-navigation libraries whose
behaviour is documented in detail and used by real TV apps.

---

## 1. Sources

| Kind | Source |
|---|---|
| Platform | [Android TV — navigation on TV](https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv), [focus system](https://developer.android.com/design/ui/tv/guides/styles/focus-system), [navigation drawer](https://developer.android.com/design/ui/tv/guides/components/navigation-drawer), [layouts and overscan](https://developer.android.com/design/ui/tv/guides/styles/layouts), [TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality) |
| Platform | [tvOS HIG — focus and selection](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection), [remotes](https://developer.apple.com/design/human-interface-guidelines/remotes), [tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars) |
| Platform | [Xbox and TV — designing for TV](https://learn.microsoft.com/en-us/windows/apps/design/devices/designing-for-tv) |
| Platform | [Fire TV — UX guidelines](https://developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html), [controller behaviour](https://developer.amazon.com/docs/fire-tv/ja-controller-behavior-guidelines.html) |
| Platform | [Roku — remote control buttons](https://developer.roku.com/docs/developer-program/design/remote-control-buttons.md), [key design principles](https://developer.roku.com/dev/docs/key-design-principles) |
| Platform | [webOS — overscan](https://webostv.developer.lge.com/develop/guides/overscan) |
| App | [YouTube Help — navigate YouTube on TV](https://support.google.com/youtube/answer/7583931?hl=en); [9to5Google on the sidebar, 2024](https://9to5google.com/2024/05/10/youtube-android-tv-sidebar-animation/) and [2026](https://9to5google.com/2026/05/08/youtube-app-tv-sidebar-subscription-library-access/) |
| App | [Netflix Tudum — the 2025 TV layout](https://www.netflix.com/tudum/articles/netflix-new-tv-layout), [Netflix Help — the new TV homepage](https://help.netflix.com/en/node/321880164349028), [FlatpanelsHD on the 2018 sidebar](https://www.flatpanelshd.com/news.php?subaction=showfull&id=1532082404), [a UI breakdown of the sidebar era](https://mlangendijk.medium.com/breaking-down-the-new-netflix-tv-ui-d651aff8bbee) |
| Standard | [CSS Spatial Navigation Level 1](https://www.w3.org/TR/css-nav-1/) |
| Library | [Norigin spatial navigation](https://github.com/NoriginMedia/Norigin-Spatial-Navigation), [BBC lrud](https://github.com/bbc/lrud), [js-spatial-navigation](https://github.com/luke-chang/js-spatial-navigation), [react-tv-space-navigation](https://github.com/bamlab/react-tv-space-navigation), [Leanback BaseGridView](https://developer.android.com/reference/androidx/leanback/widget/BaseGridView) |

---

## 2. The model the platforms agree on

### 2.1 One focus, always visible

- Exactly one element is focused at any moment (documented, Android TV). Every vendor
  says the focus indicator has to be findable at ten feet; Xbox adds that it must stay
  inside the TV-safe area *even while a list is scrolled* (documented).
- Android TV's own focus treatment is a scale of 1.025–1.1× plus a glow of 2–32dp
  (documented). YouTube's browse tiles do not scale, which this app already follows
  with a ring alone.
- When the focused element disappears, tvOS says: if the viewer is mid-navigation,
  move focus to an item within one step so the indicator stays where they are looking;
  otherwise hide it rather than guess (documented). Every library restores focus
  automatically instead — Norigin's `autoRestoreFocus` is on by default, lrud refocuses
  the last focusable node on unregister (documented). This app restores; on a WebView
  where the browser's own `:focus` is unreliable, that is the right call (inference).
- No vendor page says anything about focus during loading or skeleton states. The
  practice in libraries is to land on the real content once it exists, which is what
  the provisional landing here does (inference).

### 2.2 Content moves, focus stays

- Roku names it the fixed-focus model: move the content into the highlight rather than
  the highlight across the content (documented). Netflix's rows keep the focused item
  left-aligned with the row title visible above it and at most about four items on
  screen (reported). Leanback's default is a keyline at 50% of the viewport with the
  focused position remembered per row (documented); react-tv-space-navigation defaults
  to pinning the focused item near the start of the viewport (documented).
- So there are two sanctioned keylines, **left-aligned** (Netflix, react-tv-space-
  navigation) and **centred** (Leanback's default). YouTube's is unverified; my
  inference from use is left-aligned. Either is fine; what matters is that the keyline
  is fixed once a row starts scrolling, the heading stays visible, and at least one
  tile is visible ahead of the ring so the viewer can see there is more.

### 2.3 Rows: walls, not wrap

- Fire TV is the only vendor that documents wrap-around at the end of a one-dimensional
  list. Android TV, tvOS, Roku and Xbox say nothing either way; YouTube and Netflix
  are unverified. Libraries make it opt-in (lrud `isWrapping`, off by default). A wall
  at the end of a long shelf is the safer default (inference), and it is what this app
  does.

### 2.4 Entering a band: memory, then the declared item, then geometry

Every library agrees on the order, and it is the single most important rule for a
screen made of stacked rows:

1. **the item the band last had** — Norigin `saveLastFocusedChild` (default on), lrud's
   `activeChild`, js-spatial-navigation `enterTo: last-focused`, Leanback's aligned
   scroll strategy remembering the last position (all documented);
2. **the item the band declares** for a first visit — Norigin `preferredChildFocusKey`,
   js-spatial-navigation `defaultElement` (documented);
3. **geometry** — the candidate nearest along the travel axis with the least sideways
   drift. The CSS draft's distance function weights sideways drift 30× for a
   horizontal move and only 2× for a vertical one (documented): a sideways press
   should stay in its row; a vertical press is allowed to change column freely.

Grids get a fourth rule: on a vertical move, prefer the tile in the same column
(CSS `spatial-navigation-function: grid`, lrud `isIndexAlign`, both documented).

### 2.5 The left navigation rail

- Android TV's drawer has two named states that are both always visible, icon-only
  collapsed and icon-plus-label expanded, in a *standard* variant that pushes content
  and a *modal* one that overlays it with a scrim (documented). YouTube on TV is the
  standard variant: an icon-led sidebar that widens into labels when engaged
  (reported). Fire TV places global navigation in a left column and says Left from a
  content row returns to it (documented). YouTube Help describes reaching it the same
  way (documented).
- **Netflix no longer has one.** From May 2025 its TV home has a top bar, and Netflix's
  help page says Back from content returns to that top menu (documented). The 2018–2024
  sidebar opened on Left from a leftmost tile or on Back (reported). Android TV's
  guidance covers both shapes: Back in a top-navigation app focuses the top tab, Back
  in a left-navigation app opens the rail and focuses the active item (documented).
  This app is a left-navigation app and follows YouTube; nothing in the sources argues
  for changing that.
- No vendor states where the ring lands when the rail is entered by **Left**. For
  **Back**, Android TV is explicit: the active destination (documented). Landing on the
  same item for Left is my inference, and it is what YouTube does in use.
- Android recommends five to six destinations (documented). This app has two, for the
  reason written in `TvNavRail.kt`: there is one show.

### 2.6 The Back ladder

- Android TV: for a left-navigation app, Back from content opens the rail on the active
  item; repeated presses must reach the launcher; **avoid exit gating** — the viewer
  should be able to leave without a confirmation. Quality requirement `TV-DB`: Back
  presses lead back to the home screen (all documented).
- Fire TV takes the opposite stance and explicitly allows an "are you sure you want to
  exit" dialog; Roku requires an exit opportunity at the top level, and allows it to be
  a confirmation (both documented). tvOS: Back opens the parent screen; at the top
  level that is the Home screen, with no confirmation concept (documented).
- So a double-press-to-exit is tolerated on Fire TV and Roku and discouraged on Android
  TV, which is this app's primary host. Whatever is chosen, a first press that does
  nothing visible is wrong on every platform (inference).

### 2.7 Held keys

Android documents press-and-hold only for Select (more options) and Home; Roku
documents auto-repeat only for scrubbing (both documented). No vendor specifies list
scrolling under a held direction key; libraries either pass every repeat through or
throttle it (Norigin `throttle`, documented). The expectation in use is one step per
repeat with the ring never leaving the screen (inference).

### 2.8 Safe area and legibility

- Overscan margins: Android TV 48dp sides and 27dp top/bottom on a 960×540dp canvas;
  Xbox the same 48/27 in effective pixels; Fire TV and Roku 5% per edge, which Roku
  gives as 90px and 60px at 1080p; webOS a flat 20px (all documented). This app's
  `--safe-x` is 48px and `--safe-y` 28px at 960×540 — Android's numbers.
- Body text floors: Fire TV 14sp, about 28px at 1080p; Xbox 15 effective px, 30px at
  1080p (documented). Android gives no number. These bound how small the 75% interface
  size may legitimately make body text.

---

## 3. Where this app stands against the model

Measured on `/tv/?ui=tv-demo` at 960×540 on 2026-09-10, on the `preview` branch after
today's fixes.

| Rule | This app | Standing |
|---|---|---|
| One focus, always visible | Ring is a DOM attribute; guardian restores it after recomposition and late content | matches — except that when the focused element is *removed* the ring goes to the masthead, not to a neighbour (R6) |
| Content moves, focus stays | Horizontal: tile centred in its rail once it scrolls. Vertical: band centred when it fits, tile when it does not; headings stay visible | matches, centred keyline (see §4) |
| Walls at row ends | Right at the end of every rail and grid row stops | matches |
| Enter a band: memory → declared → geometry | Same order; season strip declares the selected season, masthead declares Play | matches |
| Grid column alignment | Nearest column by geometry | matches |
| Rail: two visible states, content pushed | 56px icons, 260px with labels, content inset | matches |
| Rail entered by Left from the leftmost item | Works from every band, including with the season strip scrolled | matches |
| Rail entered by Back lands on the active item | Yes | matches |
| Rail entered by Left lands on the active item | On a cold load it lands on the rail item nearest vertically — Settings, from a low grid row, while Home is active. Correct only once memory is seeded | differs (R19) |
| Rail has walls | It has none. A vertical press may leave any group, and a `Y` group may be left horizontally both ways, so Up, Down and Left all escape depending on what is scrolled behind the fixed rail | differs — the root cause of the intermittency (R20, R21) |
| Right out of the rail returns to the seat | Yes, page scrolled so it is visible | matches |
| Forward route change is not Back | Fixed today (popstate marking) | matches |
| Back from the rail at the top level | No visible change; only a console line. On Settings, Back never leaves the screen at all | differs badly (R26, and the Back trap) |
| Return from the player | Ring lands on the masthead with the page scrolled to the top; the tile that opened the episode is forgotten | differs (R28) |
| Safe area | Content is inset 48/28px. The rail itself sits in the margin: fixed at `left: 0` with 8px of padding, so a rail pill starts at x=8 | differs on an overscanning panel (R3) |
| Held key | One step per repeat | matches |
| Legibility floors | 26px smallest body text at 1920×1080 at 100%, 19.5px at 75% | differs (R33) |

---

## 4. Decisions to make before fixing anything

| # | Question | Options | My recommendation |
|---|---|---|---|
| D1 | Horizontal keyline | Keep centred (Leanback default) · Left-aligned like Netflix | Left-aligned. It shows more of what is ahead, aligns the ring with the heading, and is what the two named apps do — Netflix reported, YouTube inferred |
| D2 | Where Left lands in the rail | Nearest item vertically (today) · The active destination, as Back does | Active destination. A stray Enter should not change screen because the viewer happened to be low on the page |
| D3 | Exit protocol on Back from the rail | Exit on the first press (Android TV, `TV-DB`) · Keep two presses but show a visible hint | Exit at once when a host bridge exists; show a hint only in a plain browser where there is nothing to exit to |
| D4 | Rail in the overscan margin | Leave it (modern panels rarely overscan) · Inset the rail by `--safe-x` and give back width elsewhere | Inset it. `TV-OV` is a listed quality requirement, and a pill that starts 8px from the edge is the first thing a cropping panel cuts |
| D5 | Enter on the rail item for the screen you are on | Nothing (today) · Return the ring to content, or scroll to the top as YouTube does | Return to the top of the screen, which is what the same press does on YouTube (inference) |
| D6 | Wrap-around at row ends | Walls (today) · Wrap, as Fire TV documents | Keep walls |

---

## 5. Test results

Every case below was **executed** on 2026-09-10 against the `preview` build, on
`/tv/?ui=tv-demo` at 960×540, by dispatching `keydown` on `window` and reading the DOM
after each press. The player cases ran against a real MP4 (36 min, `readyState 4`,
`timeupdate` firing), not the fixture's `example.invalid` URL.

Two environment notes, because they bound what the results mean:

- The Browser pane was hidden, which stops `requestAnimationFrame` and with it every
  Compose re-render. I replaced `requestAnimationFrame` with a `MessageChannel` pump at
  runtime so frames would run. Focus, DOM and route assertions are unaffected. **Anything
  timing-based is not**, so the player chrome's auto-hide delay and CSS transition
  midpoints were not measured and are not reported as findings.
- A reload does not clear session history. One early result was wrong because of
  leftover history entries; the Back cases below were re-run in a fresh tab
  (`history.length` 2) and that is the result recorded.

Legend: **PASS** · **FAIL** · **DIFFERS** (works, but not as the case was written).

### Focus invariants

| ID | Case | Result | Measured |
|---|---|---|---|
| R1 | One ring, one `tabindex=0` | PASS | `ringCount=1`, `tabIndexZero=1` on every snapshot of the run, including across route changes |
| R2 | Ring fully inside the viewport | PASS | `inView` true on every recorded press |
| R3 | Ring inside the 48/27 safe area | **FAIL** | `nav-home` and `nav-settings` both at **x=8**; `--safe-x` resolves to 48px |
| R4 | Ring survives a control's own recomposition | PASS | Enter on `scale-115` kept the ring on `scale-115`, still `activeElement`; `--tv-scale` → 1.15 |
| R5 | Cold load lands on content | PASS | Ring on `hero-play`, rail shut, no press needed |
| R6 | Focused element disappears | **FAIL** | Removing focused grid tile `1004` moved the ring to `hero/hero-play` at the top of the page, not to a neighbour |

### Rows

| ID | Case | Result | Measured |
|---|---|---|---|
| R7 | Fixed keyline once a row scrolls | PASS | Season strip settles at x≈446, continue rail at x≈423 |
| R8 | Wall at the end of a row | PASS | `season-12` and continue `705` both absorb further presses |
| R9 | Left from a leftmost tile with the strip scrolled | PASS | Entered the rail; strip `scrollLeft` stayed 864 |
| R10 | Horizontal press does not scroll vertically | PASS | `rootScroll` unchanged across every horizontal move |
| R11 | Held key | PASS | One step per `repeat:true` event, ring in view throughout, walls at the end |

### Vertical movement

| ID | Case | Result | Measured |
|---|---|---|---|
| R12 | Band ladder down and back | PASS | hero → continue → seasons → grid → grid → back-to-top → wall, all in view |
| R13 | A row remembers its last child | PASS | Right×3 → `105`, away to the strip, Up → `105` at the same x |
| R14 | First entry to the strip lands on the selected season | PASS | Cold load: Down → `season-3`, the selected chip, ignoring horizontal distance |
| R15 | Geometry when nothing is declared | PASS | Down from `hero-play` → `continue/301` |
| R16 | Grid keeps its column; back-to-top returns | PASS | `302`→`306` both at x=310; Up from back-to-top → `306` |
| R17 | Up from the masthead | PASS | Walls |

### The navigation rail

| ID | Case | Result | Measured |
|---|---|---|---|
| R18 | Two visible states, content pushed | PASS | 56px collapsed, 260px open, labels `opacity: 1` |
| R19 | Left lands on the active destination | **FAIL** | On a cold load, Left from a low grid row lands on **`nav-settings`** while Home is active. Passes only once focus memory has been seeded |
| R20 | Up/Down wall at the rail's ends | **FAIL** | Up from `nav-home` escapes to content whenever any item sits above y=90; Down from `nav-settings` escapes when any item sits below y=472. Both reproduced |
| R21 | Left inside the rail walls | **FAIL** | Left on `nav-home` returns the ring to `hero-play` and shuts the rail |
| R22 | Right returns to the seat | PASS | Returned to tile `305`, scroll preserved at 590, rail shut |
| R23 | Enter on a rail destination | DIFFERS | Route changes correctly to `#/settings`, but the ring stays on `nav-settings` with the rail open. The same route reached by hash puts the ring on `lang-ka` with the rail shut |
| R24 | Enter on the destination you are already on | DIFFERS | No-op: route, scroll and ring all unchanged. Not a dead state, but nothing happens |

### Back and routes

| ID | Case | Result | Measured |
|---|---|---|---|
| R25 | Back from content → rail's active item | PASS | Escape on settings content → `nav-settings`, which is `.tv-nav-item.on` |
| R26 | Back from the rail at the top level | **FAIL** | First press produces no visible change; only a console line. See D3 |
| R27 / N39 | Back once in the player hides the chrome | PASS | With the chrome up, Escape hid it and the route stayed `#/watch/304` |
| N40 | Back again leaves the player | PASS | Returned to browse |
| N40c | Back eight times | PASS | Arrows still move the ring afterwards |
| R28 | Return from the player restores the tile | **FAIL** | Opened `302` (then `304`) from `rootScroll=590`; came back to `hero-play` at `rootScroll=72`. Three reproductions |
| R29 | A hash change is not Back | PASS | Ring moved to `lang-ka`, no ladder |
| R30 | A host Back is a traversal | PASS | `history.back()` on `#/settings`: popstate (state `"tv"`) → hashchange → route `""`. The ring stays on `nav-settings` though Home is now active |
| — | **The Back key cannot leave Settings** | **FAIL** | New. Escape in the rail on `#/settings`, waited 3s for a real event: no popstate, no hashchange, route unchanged. Three presses, same. Console says `back at the top level` while the route is Settings. A host Back (R30) does work, so this is the key path only |

### Selection, the season strip, the player's own axes

| ID | Case | Result | Measured |
|---|---|---|---|
| R31 | Moving across chips does not change season | PASS | Grid stayed `season-3` across eleven chips |
| R32 | Enter on a chip changes season | PASS | Grid `season-3` → `season-10`, ring kept on the chip, Down entered at the nearest column |
| N38c | Down from the scrubber reaches the transport row | PASS | `scrub` → `play` → next-episode tiles, Up retraces |
| N38d | Left/Right on the scrubber seeks | PASS | +10.0s per press, focus does not move |
| N38e | Left/Right on a button moves focus | PASS | `play` → `forward10` → `play`, no seek |

### Legibility

| ID | Case | Result | Measured |
|---|---|---|---|
| R33 | Smallest body text at 1920×1080 | **FAIL** | 19.5px at 75%, and **26px at 100%**. Fire TV's floor is ~28px and Xbox's ~30px at that resolution |

---

## 6. Defects, worst first

**1. The Back key cannot leave Settings.** With the ring in the rail on `#/settings`,
Escape does nothing — verified with a 3-second wait for a real event, so this is not a
timing artifact. The app logs `back at the top level` while the route is Settings,
because it treats "focus is in the rail" as the top of the ladder regardless of route.
A *host* Back still works (R30), so an Android TV remote would escape; the app's own
key handling would not, and in a browser there is no other way out but Enter on Home.
Breaks Android TV's `TV-DB`.

**2. The navigation rail has no boundary.** `mayLeave` lets a vertical press leave any
group, and lets a `Y` group be left horizontally in *both* directions
([SpatialNav.kt:185](../src/jsMain/kotlin/ge/dakalebi/ui/tv/focus/SpatialNav.kt#L185)).
The rail is fixed while content scrolls behind it, so whether Up, Down or Left stays in
the rail depends entirely on what happens to be scrolled behind it. This is the direct
cause of "sometimes they work correctly, but most of the time they don't". R20 and R21.

**3. Coming back from the player throws you to the top of the page.** Open an episode
from deep in the grid, press Back twice, and the ring is on the masthead with the page
scrolled to the top. The tile you came from is forgotten. R28, three reproductions. The
same weakness answers R6: when the focused tile disappears the ring goes to the
masthead rather than to a neighbour.

**4. First entry to the rail lands on the wrong destination.** On a cold load, Left
from a low grid row lands on Settings while you are on Home, so one stray Enter changes
screen. It self-corrects once memory is seeded, which is why it looks intermittent.
Made worse by defect 2: a vertical leak out of `nav-settings` seeds the memory with
Settings, so later entries land there too. R19.

**5. Back at the top level gives no feedback.** The first press only writes a console
line. R26, and D3 decides what it should do instead.

**6. Text is below the platform floors even at 100%.** 26px at 1920×1080 against Fire
TV's ~28px and Xbox's ~30px; 19.5px at the 75% setting. R33.

**7. The rail sits in the overscan margin.** Its pills start at x=8 against a 48px
safe area, so they are the first thing a cropping panel cuts. R3.

Two smaller observations, both real but low severity:

- After a route change the ring can sit on a rail item that is not the active
  destination — arriving at browse with the ring on `nav-settings` while `nav-home` is
  marked active.
- Left from `back-to-top` jumps to the season strip rather than opening the rail,
  because `leaveGroup` drops the row-overlap requirement for horizontal moves.

One non-finding, recorded so it is not re-investigated: stripping `data-tv-focus` by
hand leaves the ring invisible until the next press, but DOM focus is intact and the
next press restores it. The real recomposition path (R4) keeps the ring, so this looks
unreachable in normal use.

---

## 7. Fixing plan

**Status: all seven are implemented and verified.** §7.1 records what was measured
afterwards, including two things the plan got wrong and one defect it did not know
about. The plan below is left as written so the reasoning stays readable next to the
result.

Ordered as in §6. **Defect 2 must land before defect 4**, because a rail that leaks
vertically writes the wrong item into the rail's focus memory, and defect 4's behaviour
is only observable once that stops.

Nothing here is implemented.

### Fix 1 — the Back key must leave Settings

**Where:** the `onBack` lambda in
[TvApp.kt:111](../src/jsMain/kotlin/ge/dakalebi/ui/tv/TvApp.kt#L111).

**Cause:** the ladder is `Watch → replace(Dashboard)`, then `inRail -> false`, then
`else -> focusRailActiveItem`. The `inRail` rung returns false on *every* route, so once
the ring is in the rail the ladder falls straight through to the exit protocol even
though there is a screen to go back to.

**Change:** split that rung in two.

```kotlin
// In the rail but not at the top-level destination: Back is one step out of this
// screen, not out of the app.
inRail && router.current != Route.Dashboard -> {
    router.replace(Route.Dashboard)
    true
}
// In the rail, on Dashboard: genuinely the top. Exit protocol.
inRail -> false
```

`replace`, not a push, to match the `Route.Watch` rung directly above it: Back must not
grow the history it is walking out of.

**Second half:** after the route settles, the rail's `.on` moves to Home while the ring
stays on Settings. Add a helper beside `focusRailActiveItem` that moves the ring to the
rail's active item **without** seating `railReturn` (the existing helper seats it from
`document.activeElement`, which on this path is a rail item and would be wrong), and
call it from a `window.setTimeout(…, 0)` so it runs after the recomposition that moves
the `.on` class. This also clears the minor "ring on `nav-settings` while Home is
active" observation for this path.

**Must not break:** R25 (Back from content still lands on the rail's active item), N40
(Back from the player still leaves to browse), N40c (the root layer is never popped).

**Verify:** the new case from §5; R25; N40; N40c.

### Fix 2 — give the navigation rail walls

**Where:** `move` and `mayLeave` in
[SpatialNav.kt:185](../src/jsMain/kotlin/ge/dakalebi/ui/tv/focus/SpatialNav.kt#L185).

**Cause:** `mayLeave` returns true for every vertical press and for both horizontal
directions out of a `Y` group. `leaveGroup` already refuses chrome as a *destination*
for vertical presses, but nothing refuses chrome as an *origin*. The rail is
`position: fixed` while content scrolls behind it, so Up, Down and Left each escape
whenever some content item happens to sit past the rail item's edge — which is why the
same press behaves differently at different scroll positions.

**Change:** two guards in `move`, immediately after the `mayLeave` check and **before
the `railReturn` branch**. Placement is load-bearing, not stylistic: that branch fires
for any horizontal press out of a `Y` group, Left included, so a Left guard placed after
it would never run and R21 would still fail.

```kotlin
// Chrome sits beside the page, not in its vertical stack. `leaveGroup` already
// refuses to land a vertical press *on* chrome; this is the same rule from the
// other side, and without it a fixed rail's ends leak into whatever content
// happens to be scrolled behind them.
if (!direction.isHorizontal && isChrome(group)) return null

// A rail on the left edge has nothing to its left. Right still leaves, via the
// railReturn seat below.
if (direction == Direction.Left && isChrome(group)) return null
```

`leaveGroup`'s KDoc ends by saying a leftward press with only the rail to its left is
one of the things that reaches it. That stays true — the guard is gated on `isChrome`,
so it stops presses leaving *the rail*, not presses leaving a shelf *into* the rail.
`ARCHITECTURE.md`'s "one asymmetry" section is unaffected for the same reason. Add a
line to `mayLeave`'s own doc pointing at the two new guards, since after this change
`mayLeave` alone no longer tells the whole story.

The second guard hard-codes "the rail is on the left", which is true of this app's one
rail. If a right-hand rail is ever added, replace it with an edge test — whether the
group's box is flush against the scope's left or right edge — rather than a direction
literal.

**Must not break:** entering the rail (the press originates in a content group, so
neither guard applies); R22, Right out of the rail returning to the seat.

**Verify:** R20 and R21 at several scroll positions, including `rootScroll` 0, ~500 and
the bottom — the three that produced different answers before. Re-run R9 and R22.

### Fix 3 — come back from the player to the tile you left

**Where:** `landingSpot` in
[SpatialNav.kt:492](../src/jsMain/kotlin/ge/dakalebi/ui/tv/focus/SpatialNav.kt#L492),
plus `FocusMemory`.

**Cause:** `landingSpot` tries `entryPoint` first, and `entryPoint` only stands aside
when *its own group* has a remembered item. Returning from the player, the hero group
has a memory of its own, so the ring lands on `hero-play` and the page scrolls to the
top. The grid's memory is intact and simply never consulted, because nothing records
*which group* the screen was last in.

**The hook already exists and is dead code.** `FocusMemory.rememberScreen` /
`recallScreen` store exactly a `screenKey → (groupKey, itemKey)` triple and nothing
calls them
([FocusMemory.kt:26](../src/jsMain/kotlin/ge/dakalebi/ui/tv/focus/FocusMemory.kt#L26)).

**Change:**

1. Write it: in `mark`, when the item's group is **not** chrome, call
   `FocusMemory.rememberScreen(screenKey, groupKey, itemKey)`.
2. Read it: in `landingSpot`, before `entryPoint`, resolve the screen's remembered
   group and item and return it when that group still exists in `scope` and still holds
   that item.

**The screen key is the part to get right.** It must not be the route string, because
`#/watch/302` and `#/watch/304` are different routes but the same screen, and browse
must not inherit the player's groups. Derive it from the route *shape* —
`dashboard` / `settings` / `watch` — so the player writes its own key and browse keeps
one stable key of its own. Pass it in rather than letting the focus engine read the
router: `SpatialNav` names nothing outside `ui/tv/focus` today and should keep it that
way, so add a `var screenKey: String?` set by `TvApp` alongside `onFocusChanged`.

**This also fixes R6.** When the focused tile is removed, the same lookup lands the ring
back in the grid rather than on the masthead — though not necessarily on an adjacent
tile, which is what tvOS asks for. If you want the stricter "within one step" rule,
that is a second, separate change to `ensureFocused`; I would not do both at once.

**Must not break:** R5 (a cold load has no screen memory, so `entryPoint` still wins and
the ring lands on `hero-play`); the provisional landing, which calls the same function.

**Verify:** R28 from three different grid tiles at different scroll positions; R6; R5
from a cold load; R13.

### Fix 4 — first entry to the rail lands on the active destination

**Where:** `TvNavRail.kt`, and the arrival branch of `leaveGroup` in
[SpatialNav.kt:328](../src/jsMain/kotlin/ge/dakalebi/ui/tv/focus/SpatialNav.kt#L328).

**Cause:** arrival into a group is memory → `data-tv-entry` → geometry. The rail
declares no entry item and has no memory on a first visit, so geometry picks whichever
rail item is nearest vertically — Settings, from a low grid row.

A Left press is horizontal, so `stacked` is false and that branch does run for the
rail. The mechanism is available; the rail just never opted into it.

**Change, three parts. The third is not optional.**

1. `NavItem` already takes `active`. Pass it straight through:
   `focusItem(key, entry = active)`. Exactly one rail item carries the marker at a
   time, and it moves with the route. No new mechanism — this is the same `entry`
   parameter the season strip and the hero use.
2. For a **chrome** group only, prefer the declared entry over memory — the inverse of
   the normal order, gated on `isChrome`. Android TV specifies the active item for
   Back, and D2 extends that to Left; a rail is not a shelf you resume reading, it is a
   menu that should always open on where you are. Without this, visiting Settings and
   returning to Home would still land the ring on Settings.
3. **Scope the two screen-wide entry queries away from chrome.** `entryPoint`
   ([SpatialNav.kt:539](../src/jsMain/kotlin/ge/dakalebi/ui/tv/focus/SpatialNav.kt#L539))
   and `focusEntry`
   ([SpatialNav.kt:523](../src/jsMain/kotlin/ge/dakalebi/ui/tv/focus/SpatialNav.kt#L523))
   both do a bare `scope.querySelector("[data-tv-entry]")`, which takes the **first in
   document order**. `TvApp` renders the rail at
   [TvApp.kt:216](../src/jsMain/kotlin/ge/dakalebi/ui/tv/TvApp.kt#L216), before the
   screen — so with step 1 alone the rail's marker becomes the *screen's* entry point,
   a cold load would land on the rail instead of `hero-play`, and Back-to-top would
   jump into the rail. Change both to select all matches and take the first whose
   group is not chrome.

Step 3 is not a workaround. It states in code what `landingSpot`'s own comment already
asserts: "The rail is a way to somewhere else, never a destination." Today no chrome
declares an entry, so it is a no-op for every existing screen.

**Must not break:** R5 and back-to-top, both covered by step 3. R13 and R14 rely on
memory-before-entry for *content* groups and must keep it, which the `isChrome` gate on
step 2 preserves.

**Verify:** R19 from a cold load and from a low grid row, on both Home and Settings;
then R5, R12's back-to-top, R13 and R14 to confirm nothing else moved.

### Fix 5 — say something on the first Back at the top level

**Where:** `back()` in
[TvInput.kt:262](../src/jsMain/kotlin/ge/dakalebi/ui/tv/input/TvInput.kt#L262), at the
`pendingExit` block.

**Cause:** the first press only writes `Log.d`. On a television that is indistinguishable
from a dead remote.

**Change, per D3:**

```kotlin
if (onExitRequested != null) { onExitRequested?.invoke(); return }
```

so a host that can actually close the app exits on the first press, which is what
Android TV's "avoid exit gating" and `TV-DB` ask for. Keep the two-press protocol only
where there is nothing to exit to, and make the first press *visible* there.

**Do not give `TvInput` a toast dependency.** It is the input layer and names no UI
today. Add `var onTopLevelBack: (() -> Unit)? = null` beside `onExitRequested`, and let
`TvApp` wire it to the existing toast store — the same place `ToastHost()` already
renders.

**Must not break:** N40c. The exit path must still not pop the root layer.

**Verify:** R26 in a browser (hint appears on press one) and with a stub
`AndroidTvHost.exit` installed (exits on press one).

### Fix 6 — raise the smallest text

**Where:** `tv.css`. Two separate problems; decide them separately.

**6a, a leak, and mechanical.** tv.css sets a type scale whose smallest step is
`--t-label: 0.875rem` (28px at 1920×1080) but never applies it to keel's segmented
control. The season chips and the language picker take their classes straight from
`segmentedLabelClasses()`
([TvPieces.kt:160](../src/jsMain/kotlin/ge/dakalebi/ui/tv/TvPieces.kt#L160)), so they
render at keel's own `0.8125rem` — 26px, the measured minimum. This sheet already fixes
exactly this leak for the toast, with `--toast-font-size`
([tv.css:119](../src/jsMain/resources/tv/tv.css#L119)), whose comment names keel's
`0.8125rem` by value. The chips need the same treatment:
`.tv-chip, .tv-seg-item { font-size: var(--t-label); }`, which makes the 26px 28px.
Specificity is not a problem here the way it is for the focus ring at tv.css:386 —
keel's own rule is a bare `.segmented__label`.

**6b, a decision, and not mechanical.** Even after 6a the floor is 28px at 1080p, which
clears Fire TV's ~28px and misses Xbox's ~30px; and the 75% setting takes everything to
21px. Three options, and this is your call:

| Option | Effect |
|---|---|
| Leave the scale, fix only the leak | Meets Fire TV, misses Xbox, and 75% stays well below both |
| Raise `--t-label` to `0.9375rem` (30px at 1080p) | Meets both floors at 100%; 75% still misses |
| Raise the smallest scale step from 75% to 90% | The setting stops being able to go below the guidance at all |

My recommendation is the leak fix plus raising `--t-label`, and leaving 75% available —
it is a deliberate per-device override by someone sitting closer, and the floors are
written for the default, not for an accessibility control. But if these numbers are
meant to be compliance rather than guidance, raise the minimum step too.

**Verify:** R33 at 1920×1080 at 100% and 75%, on both browse and settings.

### Fix 7 — take the rail out of the overscan margin

**Where:** `.tv-rail-nav` and `--content-x` in `tv.css`.

**Cause:** the rail is `left: 0` with `0.5rem` of padding, so its pills start at x=8
against a 48px safe area.

**Change:** inset the rail by `--safe-x`, and add the same amount to the content inset
so the two do not collide — `--content-x` is currently
`calc(var(--rail-w) + var(--safe-x))`, which after the shift would put content flush
against the rail's right edge. The open-rail rule
`padding-left: calc(var(--rail-w-open) + var(--safe-x))` needs the identical
adjustment. `--safe-x` and `--safe-y` stay outside the interface-size multiplier, as
`ARCHITECTURE.md` records.

**Watch the width budget.** At 960×540 this moves the content edge right by 48px, so
re-measure the grid's column count and the rails' visible tile count afterwards; if a
column is lost, the rail's collapsed width is the thing to trade, not the safe area.

**Verify:** R3 for every rail item; then R7, R12 and R16 to confirm the layout still
holds, at 75% and 130% as well as 100%.

### Suggested order

| Step | Fixes | Why here |
|---|---|---|
| 1 | 2 | Stops the rail corrupting its own focus memory; everything below is measured more honestly once it lands |
| 2 | 4 | Depends on step 1 |
| 3 | 1, 5 | Both are the Back ladder, one file each, no overlap with focus geometry |
| 4 | 3 | The largest change, and the only one that adds state |
| 5 | 6, 7 | CSS only; run `tools/check-css-classes.py` after |

Steps 1–4 are all covered by `./gradlew jsBrowserDistribution` plus the §5 cases; none
of them touch `:shared`, so `jsNodeTest` is unaffected except by fix 3 if the screen-key
derivation is put in `presentation` rather than in the TV layer.

---

## 7.1 Results, measured after the fixes

Same method as §5: `preview` build served from `build/dist/js/productionExecutable`,
`/tv/?ui=tv-demo`, synthetic `keydown` on `window`, DOM read after each press, rAF
replaced by a `MessageChannel` pump because the pane is hidden. Back cases re-run in a
fresh tab (`history.length` 2). Legibility measured at 1920×1080, everything else at
960×540.

| Case | Was | Now |
|---|---|---|
| R3 rail inside the safe area | FAIL, pills at x=8 | **PASS**, rail at x=48, pills at x=56 |
| R5 cold load lands on content | PASS | **PASS**, still `hero-play` |
| R13 a row remembers its last child | PASS | **PASS**, `105` restored |
| R14 first entry to the strip | PASS | **PASS**, `season-3` |
| R19 rail entry on the active item | FAIL, landed on `nav-settings` | **PASS**, `nav-home` from a cold load |
| R20 Up/Down wall at the rail's ends | FAIL, both leaked | **PASS**, both wall |
| R21 Left wall inside the rail | FAIL, returned to `hero-play` | **PASS**, walls |
| R22 Right returns to the seat | PASS | **PASS** |
| R25 Back from content → rail | PASS | **PASS**, and now the ring matches the active item |
| R26 Back at the top level | FAIL, console only | **PASS**, on-screen hint |
| R28 return from the player | FAIL, `hero-play` at scroll 72 | **PASS**, `508` in season 5 at scroll 627 |
| R33 smallest text at 1920×1080 | FAIL, 26px | **PASS**, 30px (22.5px at the 75% setting) |
| Back cannot leave Settings | FAIL | **PASS**, `#/settings` → `#/`, ring on `nav-home` |
| N40c eight Backs, input alive | PASS | **PASS** |
| R12 Enter on back-to-top | PASS | **PASS**, `hero-play` at scroll 0 |

Grid column count at 960×540 is unchanged at 4, so moving the rail inside the overscan
margin cost no content width. The continue rail still shows 4 tiles.

### Three things the plan had wrong

**Fix 6 could not work as written.** The plan set `font-size` on `.tv-chip`, which is
one class. keel sizes the same element through `.segmented--rail .segmented__label`,
which is two, so the rule shipped and changed nothing — measured still 26px. keel
declares `--segmented-font-size` and `--segmented-rail-font-size` as tokens, and its own
token contract test lists them, so the fix is to set those instead. That is also what
the neighbouring `--toast-font-size` already does, which the plan cited without noticing
it was citing the answer.

**Fix 1's ring re-aim could not work as written.** Following the route change with a
`setTimeout(…, 0)` and then reading `.tv-nav-item.on` reads it before the recomposition
that moves the class, so the ring stayed on `nav-settings`. Aiming at the destination's
own `href` needs no wait and no timer.

**Fix 3 was only half the defect.** Focus memory survived the trip to the player;
the browse screen's selected season did not, because it was a `remember` inside a screen
that gets unmounted. Returning from an episode in season 5 rebuilt season 3, so the
remembered group no longer existed and the ring fell back to the top — the original
symptom, from a second cause. The season is now held in a `BrowseState` holder beside
the screen, cleared with focus memory on sign-out. Worth stating plainly: the first
round of testing passed only because it was run in the default season.

### Still open

- **D5 is unimplemented.** Enter on the rail item for the screen you are already on
  still does nothing (R24). It was a decision, not a defect, and no fix was written.
- **R6 is improved but not to the letter.** A removed focused tile now returns the ring
  to the grid rather than the masthead, via the same screen memory. tvOS asks for an
  adjacent item specifically, which would be a separate change to `ensureFocused`.
- **The 75% interface size renders 22.5px**, below both platform floors. That is the
  deliberate decision recorded in fix 6b: the floors describe the default, and the
  setting is an override for someone sitting closer.
- Timing-dependent behaviour is still unmeasured, for the reason in §5.

---

## 8. What this does not settle

- YouTube's own focus mechanics on TV — sidebar entry key, exit key, where the ring
  lands, row alignment, focus memory, return after a video — are not documented by
  Google. Everything attributed to YouTube in §2 is reported or inferred from use.
- Netflix's current TV design is a top bar, so it is a reference for rows and for
  "Back returns to navigation", not for a rail.
- No vendor documents wrap-around except Fire TV, and none documents keyline choice.
  D1 and D6 are judgement calls, not compliance.
- Player chrome auto-hide timing, CSS transition behaviour, and anything else driven by
  a real frame clock were not measured, for the reason given at the top of §5.
- Playback-driven behaviour beyond seeking — resume position, autoplay of the next
  episode at the end, buffering states — was not exercised.
