# Test plan — signed-in state

Written from the code as it stands on `main` at `0a0634e`, not from memory of
building it. Covers only the **signed-in** experience: the account is already
authenticated, so login, signup and password reset are out of scope.

## Environment

| | |
|---|---|
| Build | `./gradlew jsBrowserDistribution` |
| Served from | `http://localhost:4173` (static, `build/dist/js/productionExecutable`) |
| Desktop | Chrome, real profile (holds the Google session) |
| Mobile | Safari in the iOS Simulator — iPhone and iPad |
| Account | `bachanamosulishvili@gmail.com` — on the admin allowlist |
| Data | Live Firestore `dakalebi-tv`: 932 episodes, 18 seasons |

Not verifiable here, and deliberately not claimed as passing:

- **AirPlay** (`I10`) needs a real Apple TV; the Simulator has no receiver.
- **Pages deployment** — this branch never deploys, because Pages builds from
  `main` and `main` is frozen for this pass.

The console is watched throughout. With the logging commit in place, any caught
failure prints `dakalebi/<tag>`; an empty console is now meaningful evidence
rather than the absence of it.

---

## A — Boot, session and shell

| ID | Scenario | Expected |
|---|---|---|
| A1 | Cold load at `#/` with a restored session | Dashboard renders; no flash of the login screen |
| A2 | Console after boot settles | No `error`; no uncaught exception |
| A3 | The moment before Firebase reports the session | `იტვირთება...` placeholder, then content |
| A4 | Hard reload on the dashboard | Session survives; same view |
| A5 | Avatar in the nav | First letter of the email, uppercased; `title` is the full address |
| A6 | Open the menu sheet as an admin | `სერიების განახლება` is present |
| A7 | Catalog size after load | 932 episodes across 18 seasons |
| A8 | Sheet footer | `ბოლო განახლება:` with a ka-GE formatted date, not `—` |

## B — Routing and deep links

| ID | Scenario | Expected |
|---|---|---|
| B1 | `#/` | Dashboard |
| B2 | `#/watch/<valid id>` typed into the address bar, cold | Watch screen for that episode |
| B3 | `#/watch/999999999` | `სერია ვერ მოიძებნა`, no crash |
| B4 | `#/watch` with no id | Falls back to the dashboard |
| B5 | `#/nonsense` | Falls back to the dashboard |
| B6 | No hash at all | Dashboard |
| B7 | Back button from watch | Returns to the dashboard |
| B8 | Forward button after B7 | Returns to the same episode |
| B9 | Hard reload while on a watch route | Same episode, position restored |
| B10 | Copy-link button, then open the copied URL | Loads the same episode |

## C — Dashboard content

| ID | Scenario | Expected |
|---|---|---|
| C1 | Hero | Shows the continue-watching episode |
| C2 | Hero eyebrow | `გაგრძელება` unfinished / `ბოლო ნანახი` watched / `დასაწყისი` fresh |
| C3 | Hero progress bar | Width matches the stored percentage |
| C4 | Hero subtitle | `დარჩა N წუთი · m:ss / m:ss`, arithmetic correct |
| C5 | Hero CTA | Links to the hero episode's watch route |
| C6 | Hero on an episode with no video | Shows `ვიდეო ამ სერიისთვის მიუწვდომელია` |
| C7 | Continue rail | Present only when something is started; count in the header matches |
| C8 | Season chips | 18 chips, `სეზონი n`, exactly one selected |
| C9 | Chip tick | `✓` only when every episode in that season is watched |
| C10 | Click a season chip | Grid switches; header title and counts follow |
| C11 | Grid header | `N სერია · M ნანახი` matches the tiles |
| C12 | Tile chrome | `E<n>` badge, watched tick, progress bar, duration when known |
| C13 | Thumbnail that 404s | Falls back to the deterministic gradient, no broken-image icon |
| C14 | Tile copy buttons | Copy without navigating; success toast |

## D — Catalog refresh (admin)

| ID | Scenario | Expected |
|---|---|---|
| D1 | Non-admin view of the sheet | Refresh row absent |
| D2 | Press refresh | Nav shows a live `სეზონი n / 18` counter |
| D3 | Refresh completes | Toast `განახლდა: 932 სერია, N შეიცვალა` |
| D4 | Refresh again immediately | Reports `0 შეიცვალა` — the diff actually works |
| D5 | While refreshing | The refresh control is disabled |
| D6 | Press refresh twice quickly | Only one run; the second is a no-op |
| D7 | After refresh | `meta/catalog` timestamp updates and shows in the sheet |
| D8 | Refresh with the network offline | Error toast **and** a logged error naming the stage |
| D9 | Refresh after durations were learned | `durationSeconds` is not clobbered |

## E — Watch screen chrome

| ID | Scenario | Expected |
|---|---|---|
| E1 | Back link | Returns to the dashboard |
| E2 | Nav label | `სეზონი n · სერია m` |
| E3 | Episode with a null title | Falls back to `სერია m` |
| E4 | `თავიდან ყურება` | Seeks to 0, clears progress, plays |
| E5 | `ნანახად მონიშვნა` | Marks watched, toast, button swaps |
| E6 | `✓ ნანახია` | Opens the progress-reset dialog |
| E7 | Reset dialog | Confirm clears; cancel changes nothing |
| E8 | `შემდეგი სერია` | Navigates to the next episode |
| E9 | `ფორმულაზე გახსნა` | Opens the Formula page in a new tab |
| E10 | Rails | `წინა სერიები` shows 4 before, `შემდეგი სერიები` 8 after, correct order |

## F — Custom player (desktop)

| ID | Scenario | Expected |
|---|---|---|
| F1 | Desktop Chrome | Custom chrome, no native controls attribute |
| F2 | Arriving after pausing the previous episode | Does not autoplay |
| F3 | Centre overlay | Big play button when paused; gone while playing |
| F4 | Play/pause button | Toggles; icon swaps |
| F5 | Click the video surface | Toggles play/pause |
| F6 | Double-click the video | Enters fullscreen and does **not** also toggle play |
| F7 | Back-10s button | Seeks back exactly 10s |
| F8 | Drag the volume slider | Volume follows |
| F9 | Volume to 0 | Mutes; icon becomes muted |
| F10 | Mute button | Toggles; slider shows 0 while muted |
| F11 | Volume across pause/play | Retained |
| F12 | Time readout | `m:ss / m:ss`, tabular |
| F13 | Readout past an hour | `h:mm:ss` |
| F14 | Scrub fill during playback | Advances smoothly |
| F15 | Buffered bar | Renders ahead of the playhead |
| F16 | Drag the scrub bar | Seeks; fill follows the drag |
| F17 | Click a point on the scrub track | Seeks there |
| F18 | Knob | Tracks the playhead |
| F19 | Leave the mouse still while playing | Controls hide after ~2.5s |
| F20 | While controls are hidden | The thin bar is visible — never no indicator |
| F21 | Move the mouse | Controls reappear |
| F22 | Move the pointer out of the player while playing | Controls hide |
| F23 | Fullscreen button | Enters and exits; icon swaps; layout correct |
| F24 | Quality menu | Lists 1080p / 720p / 360p; choice written to `localStorage` |
| F25 | Switch quality mid-playback | Position and play/pause state preserved |
| F26 | Cast button | Present in Chrome; click opens the picker |
| F27 | Seek to an unbuffered point | Spinner while it loads |
| F28 | Keyboard `Space` `←` `→` `↑` `↓` `f` `m` | Play, ∓10s, ±5% volume, fullscreen, mute |

## G — Next-episode prompt

| ID | Scenario | Expected |
|---|---|---|
| G1 | Seek to 180s before the end | Card appears |
| G2 | Card content | Next episode's still, title, `სეზონი n · სერია m` |
| G3 | Autoplay off | No countdown bar (nothing is counting down) |
| G4 | Autoplay on | Bar fills as the episode approaches its end |
| G5 | `ყურება` | Navigates immediately |
| G6 | `დახურვა` | Dismisses and stays dismissed within the window |
| G7 | Seek back out past 180s, then in again | Prompt re-arms |
| G8 | Let it end with autoplay on | Auto-advances to the next episode |
| G9 | Last episode of season 18 | No prompt, no next button |
| G10 | iOS | No overlay at all |

## H — Progress persistence

| ID | Scenario | Expected |
|---|---|---|
| H1 | Play continuously | Writes at most every ~7s |
| H2 | Pause | Writes immediately |
| H3 | Switch browser tab away | Writes on `visibilitychange` |
| H4 | Navigate back to the dashboard mid-playback | Position is saved |
| H5 | Press next mid-playback | Position of the outgoing episode is saved |
| H6 | Reload the watch page | Resumes at the saved position |
| H7 | Saved position below 1s | No resume seek |
| H8 | Watch past 90% | Marked watched |
| H9 | Let it end | Watched, toast `მონიშნულია, როგორც ნანახი` |
| H10 | A stray 0s `timeupdate` on a finished episode | Does **not** un-watch it |
| H11 | Mark season watched | Every tile ticks; chip gains `✓` |
| H12 | Reset season | Ticks and bars clear |
| H13 | Reset all | Everything clears; hero falls back to the first episode |

## I — iPhone and iPad

| ID | Scenario | Expected |
|---|---|---|
| I1 | iPhone Safari | Apple's native controls; no custom chrome anywhere |
| I2 | iPad Safari | Same — the `MacIntel` + touch-points check must catch it |
| I3 | Desktop Safari on a Mac | Keeps the custom player |
| I4 | Playback on iOS | Plays |
| I5 | Resume on iOS | Lands at the saved position |
| I6 | Progress on iOS | Written to Firestore |
| I7 | Next-episode overlay on iOS | Absent by design |
| I8 | iPhone layout | Dashboard and watch usable |
| I9 | iPad layout | Dashboard and watch usable |
| I10 | AirPlay clock | **Not verifiable** without an Apple TV — record as untested |

## J — Failure and resilience

| ID | Scenario | Expected |
|---|---|---|
| J1 | Offline at boot | Load error shown; logged with a code |
| J2 | Back online, retry | Recovers without a full reload |
| J3 | Formula API down during resolve | Falls back to the stored URL; warning logged |
| J4 | Video URL 404s | Retries once, then a clear notice |
| J5 | Firestore rejects a progress write | Logged as an error, not swallowed |
| J6 | `localStorage` unavailable | Warned; app still functions |
| J7 | Thumbnail 404 | Gradient fallback |
| J8 | Episode with no video at all | Notice, no crash |
| J9 | Very long episode title | No layout break |
| J10 | Navigate rapidly between episodes | No timer/rAF leak, no stacking listeners |
| J11 | Sign out mid-playback | Clean teardown, no permission-error storm |

## K — Responsive

| ID | Scenario | Expected |
|---|---|---|
| K1 | 375×812 dashboard | Usable; single-column grid |
| K2 | 375×812 watch | Player and controls usable |
| K3 | 768×1024 | Correct side of the 720px breakpoint |
| K4 | 1280×800 | Baseline |
| K5 | 1920×1080 | No absurd line lengths or stretched hero |
| K6 | Grid columns | Adapt across widths |
| K7 | Nav and sheet at 375 | All controls reachable |
| K8 | Every width | No horizontal page scroll |

## L — Keyboard and focus

| ID | Scenario | Expected |
|---|---|---|
| L1 | Tab through the dashboard | Reaches nav, hero CTA, chips, tiles in a sane order |
| L2 | Focused element | Visible focus indicator |
| L3 | `Enter` on a focused button | Activates **that button** |
| L4 | `Space` on a focused button | Activates that button |
| L5 | `Esc` with a dialog or sheet open | Closes it |
| L6 | Typing in a text input on the watch screen | Player shortcuts do not fire |
| L7 | Arrow keys on the focused scrub input | Move by a sensible step from the current position |

## M — Preferences and storage

| ID | Scenario | Expected |
|---|---|---|
| M1 | Toggle autoplay, reload | Setting persists |
| M2 | Pick a quality, reload | Preference persists and is applied |
| M3 | Pause, navigate away, come back | Stays paused |
| M4 | Change a pref in a second tab | First tab syncs via the `storage` event |
| M5 | Set a volume, reload | Documented either way — is volume meant to persist? |
| M6 | Hard reload | Preferences survive |
