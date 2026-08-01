# Round 2 — reported issues and design pass

Every item below was reproduced before being touched. Verified against a local
production build in Chrome, signed in as the owner's account.

| # | Sev | Area | Reported as | Status |
|---|---|---|---|---|
| R1 | High | Drawer | Refresh option missing for the email/password account | Fixed |
| R2 | High | Everywhere | Progress fills, volume level and the position bar are "one colour" | Fixed |
| R3 | High | Player | Play icon does not change when pausing or playing | Fixed |
| R4 | High | Player | Muted state shows the same icon | Fixed |
| R5 | Medium | Player | No visual feedback when seeking with the arrows | Fixed |
| R6 | Medium | Drawer | The autoplay toggle does not look like a toggle | Fixed |
| R7 | Medium | Player | Player icons are too small | Fixed |
| R8 | — | Dashboard | Hero dwarfs the tiles; layout feels scattered | Reworked |
| R9 | — | Everywhere | Underlined links look dated | Reworked |
| R10 | — | Everywhere | Buttons look rough; wants Apple-like rounding | Reworked |
| R11 | — | Watch page | Player takes almost the whole screen; wants a YouTube layout | Reworked |

---

## R2 — one root cause behind several reports

Three of the reported symptoms — the volume slider "not changing colour", the
always-visible bar being "just one colour", and progress not showing — were the
same defect in eight places.

```css
.hero-bar i      .tile-prog i     .thinbar i      .vol i
.nextcard-bar i  .spinner i       .stat b         .time em
```

Every one targets an `<i>`, `<b>` or `<em>` that Compose never emits; the markup
is a plain child `<div>`. So each fill had its width set correctly and no
background at all — only the track was ever visible.

Measured on production before the fix: `.hero-bar`'s child had inline
width `39.2%` and `background: rgba(0,0,0,0)`. After: `rgb(225,53,47)`.

**This is a miss from round 1.** That sweep found two instances of exactly this
pattern and I fixed only those two instead of grepping for the shape. `.time em`
has no counterpart in the markup at all and was deleted.

## R3, R4 — icons frozen at first render

`Icon()` wrote `innerHTML` from inside `ref{}`, which Compose runs once per
element and never again. The glyph was pinned to whatever it was on first
render.

The giveaway: the `aria-label` on the button **did** update. Pressing play
switched the label to `პაუზა` while the SVG path stayed `M7 4.5v15l12-7.5z` —
still a play triangle. The control announced one thing to a screen reader and
drew another. Same for mute and for the fullscreen arrows.

`DomSideEffect(markup)` re-runs when the markup changes. Verified: play/pause
swaps both ways, and the speaker's slash now tracks `video.muted`.

## R1 — refresh missing for the new account

Confirmed on the live session: uid `PZ6HS4…`, provider `password`,
**`emailVerified: false`**. An email/password account created from the Firebase
console is not marked verified.

`isAdmin` required `emailVerified`, so the row was hidden — and the Firestore
rules carried the same condition, so simply showing the button would have
produced `permission-denied`.

The allowlist now matches on **UID first**. The email branch existed to solve a
bootstrap problem — no UID exists before the first sign-in — which no longer
applies. It stays as a recovery path, still guarded by `email_verified`, so an
unverified account matches neither branch.

`firebase/firestore.rules` was updated to match **and published**, since the fix
is inert until the rules are live.

Verified end to end: the row is back, and pressing it produced
`განახლდა: 932 სერია, 28 შეიცვალა`. Those 28 are exactly the episodes carrying a
larger still — which independently confirms the round-1 thumbnail fix reached
stored data.

## R5 — no feedback when seeking

Volume already flashed a pill; seeking flashed nothing. On a 32-minute episode
a ten-second jump can leave the frame looking identical, so there was no way to
tell the key had registered.

Verified: `10 წმ ▸`, `◂ 10 წმ`, `ხმა: 100%`.

## R6 — the toggle did not read as a toggle

Two things. The knob was missing entirely — `.switch i` again (see R2). And even
with the knob, 40×31 with a 17px dot is not a shape people recognise.

Now iOS proportions: 51×31 with a 27px knob and a drop shadow, sliding 20px on a
250ms ease-out. Verified in both states — off `rgb(57,57,61)` with no transform,
on `rgb(225,53,47)` translated 20px.

> Worth noting: the screenshots you sent predate the merge. On current `main`
> the knob renders — the sizing complaint is still valid, which is what R6 now
> addresses.

## R7, R9, R10 — foundations

- Icon sizing moved out of the SVG markup into CSS, so one glyph can be 20px in
  the nav and **24px in the player**, with the centre play button at 34px. Tap
  targets went 34 → 40px.
- A rounding scale replaces the single 2px token: 14px surfaces, 9px chrome that
  sits on media, capsules for controls. Buttons are capsules with a press-scale
  and Apple's standard ease-out.
- Anchors lose their underline globally. Every link here is a card or a button;
  none wanted the default. Verified: 54 anchors on the dashboard, 0 underlined.

## R8 — dashboard balance

The hero was `21/9` with no height cap, so on a laptop it was taller than the
viewport — nothing else appeared without scrolling, which is what made the rest
feel small and scattered. Capped at `min(62vh, 620px)`, and the tiles grew to
match: rail cards 236 → 300px, grid columns 210 → 280px, with bigger gaps,
larger section headings and more space between rails. Hover is a lift and a
shadow rather than a hard white outline.

## R11 — watch page

Two columns from 1180px up: the episode on the left, up-next on the right,
previous episodes underneath. Below that the columns collapse and up-next
reflows into as many columns as fit.

The player is capped by constraining its container's **width**
(`min(100%, 72vh * 16/9)`) rather than its height — `.player` derives height
from width through `aspect-ratio`, so a `max-height` would clip rather than
scale. That is the same trap that made the hero overflow on phones in round 1.

Up-next is a new stacked list rather than a reused rail: a horizontal scroller
beside a player wastes the one axis there is room on.

Grid minimums are wrapped in `min(…, 100%)` so a container narrower than the
track minimum cannot overflow, rather than relying on the 720px breakpoint.

Verified at 1680px: player 1078×606, 12 up-next rows, no horizontal scroll.

---

## Testing notes

- **Narrow viewports were tested in a same-origin iframe**, because a window
  manager on this machine pins Chrome to full screen and refuses
  `bounds` changes. Media queries respond to the iframe's width and it shares
  the session, so it is a real test. At 371px: single column, player 324px,
  document `scrollWidth` 356 against 371 — no horizontal scroll on either
  screen.
- **Stale CSS is a trap here.** The static server sends no `Cache-Control`, so
  Chrome serves the old stylesheet on a normal reload and the fix looks like it
  failed. Every visual check in this round used a hard reload.
- **Not verified:** real iOS Safari, and the AirPlay wall-clock extrapolation,
  which still needs an Apple TV.
