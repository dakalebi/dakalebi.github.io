# Native TV app

Status: **implemented and verified on the Google TV emulator (API 36).** Planned and
built on 2026-09-29, branch `feature/NO_TICKET/native-tv-app`. §6 has the results and
§7 has what is still open.

Goal: an Android TV / Google TV app that is a full-screen WebView on
`https://dakalebi.github.io/tv/`, where the remote behaves exactly as it does in the
browser TV version.

## 1. Starting point

**The app already existed.** `:tv` was added in `5a03813` (2026-08-05). This work
finished and verified it rather than starting over.

| Area | What `:tv` had before this work |
|---|---|
| Module | `tv/`, plain `Activity`, no AndroidX / Compose / `:shared`. AGP 9.0.0, compile/target SDK 36, min SDK 26. Included only when an Android SDK is visible (`settings.gradle.kts`) |
| Page | Loads `https://dakalebi.github.io/tv/`. JS, DOM storage, autoplay without a gesture, HTTPS only, UA gets a ` DakalebiTV/1.0 (AndroidTV)` suffix |
| D-pad, OK | Left to the WebView. Chromium turns them into DOM `ArrowUp`/.../`Enter` keydowns |
| Back | Consumed in `dispatchKeyEvent`, sent on key-up as `window.__tvShell.onBack()` |
| Media keys | First key-down turned into a synthetic `keydown` (`MediaPlayPause`, ...) on `window` |
| Exit | Page calls `window.AndroidTvHost.exit()` at the top of the Back ladder |
| Resilience | Native offline screen with Retry, auto-reload when the network returns, WebView rebuilt if the renderer dies |
| Launcher | Leanback + normal launcher entry, placeholder vector banner, landscape, keep-screen-on |

The web side of the contract was already in place: `TvInput` publishes `__tvShell`,
`TvApp` wires `onExitRequested` only when `AndroidTvHost` exists, and `TvKeys.keyOf`
already knows `GoBack` and the `Media*` keys. **No web code changed.**

## 2. Input flow

```
remote key
   |
   |-- Back, Android 13+ ---> OnBackInvokedCallback --.
   |     (the key-up that follows arrives cancelled    |
   |      in dispatchKeyEvent and is ignored)          |
   v                                                   v
MainActivity.dispatchKeyEvent                     handleBack()
   |-- Back, Android 8-12 (key-up) -----------------> |
   |                                                   |
   |-- D-pad, OK, first press -> WebView (Chromium) -> DOM keydown ArrowX / Enter --.
   |-- D-pad repeats ---------> synthetic keydown ArrowX, repeat: true ------------|
   |-- media keys ------------> synthetic keydown MediaXxx (repeats flagged) ------|
   '-- handleBack() ----------> synthetic keydown "GoBack" -------------------------|
                                                                                    v
                                   TvInput: one window listener, same dispatch()
                                   as a browser key press
page -> host: window.AndroidTvHost.exit()   (top of the Back ladder only)
```

The rule: **every remote key reaches the page as the same DOM `keydown` a browser
would produce**, targeted at the focused element and bubbling to `window`. Then there
is only one code path, and the browser TV version already tests it.

## 3. Gaps and fixes

The baseline run (§6) against the unchanged shell decided every "verify" item on
evidence.

| ID | Problem | Result |
|---|---|---|
| G8 | **Found in the baseline.** On Android 16 an app targeting API 36 gets predictive Back: `KEYCODE_BACK` goes to the system's default callback, which finished the app. Every Back closed the app from any screen | Fixed: register an `OnBackInvokedCallback` (API 33+) and opt in with `enableOnBackInvokedCallback`. The key path stays for API 26 to 32 |
| G8b | **Found after the G8 fix.** The system runs the callback, then still forwards the key-up to `dispatchKeyEvent`, marked cancelled (`ViewRootImpl.doOnBackKeyEvent`, API 36 source). One press ran Back twice, skipped a rung, and exited one press early. Sign-in hid it, because the open keyboard took the forwarded key | Fixed: ignore a cancelled key-up |
| G1 | Back in a text field on sign-in closed the app: `__tvShell.onBack()` skipped the page's edit-mode branch | Fixed: Back is pressed as a `GoBack` keydown on the focused element, so it runs `dispatch()` like a browser Escape |
| G2 | Back did nothing while the page was blank, slow or stuck | Fixed: the injected script reports whether the input layer exists; if not, the shell finishes |
| G3 | Only the first media key-down was consumed; repeats and key-ups reached the page natively as extra presses | Fixed: every media key event is consumed; each key-down is pressed, with `repeat` for held keys (D5) |
| G4 | Chromium delivers held D-pad repeats with `repeat: false`, so the player could not tell a hold from taps | Fixed: the first press stays native; repeats are pressed with `repeat: true`, only while the page has focus |
| G5 | Home during playback | **Not a bug.** The WebView pauses media on `onPause`. Dropped |
| G6 | WebView-only visuals | Grey default poster: fixed (`getDefaultVideoPoster()` returns a transparent bitmap). System font scale enlarging the rem layout: fixed (`textZoom = 100`). Default focus highlight: **not visible**, dropped |
| G7 | Hardening | Main frame limited to `https://` on the app's host. No backup and no device-to-device transfer, because the WebView storage holds the Firebase refresh token: `allowBackup="false"`, plus `dataExtractionRules` that exclude every domain, since on Android 12+ an app targeting 31+ is transferred whatever `allowBackup` says (found in review), and a matching `fullBackupContent` for 8 to 11. The `AndroidTvHost` bridge is injected into every frame, cross-origin ones included, so `exit()` is honoured only while a Back press is being delivered to the page, which is the only time the page calls it (found in review). Remote debugging needed no code: the WebView exposes its DevTools socket by itself in the debuggable builds (debug, preview), as seen on the emulator |

Also done: the `HOME_URL` comment now says Pages sends `Cache-Control: max-age=600`, so a
deploy can take up to 10 minutes to reach the app.

## 4. Decisions

All defaults were taken.

| ID | Decision |
|---|---|
| D1 | Sideload only. No Play listing |
| D2 | A `preview` build type: `/preview/tv/`, id `ge.dakalebi.tv.preview`, name "დაქალები preview", amber banner and icon. Installs beside the real app |
| D3 | Keep-screen-on stays as is |
| D4 | Debug-signed APKs. `assembleRelease` gives an unsigned APK; a release keystore is yours to create and stays out of the repo |
| D5 | A held FF/RW repeats the skip, as in a browser |

## 5. Building and installing

Needs an Android SDK: a gitignored `local.properties` with `sdk.dir`, or
`ANDROID_HOME`. The web build also needs the `keel` submodule
(`git submodule update --init keel`).

```bash
./gradlew :tv:assembleDebug :tv:assemblePreview
```

```bash
adb install -r tv/build/outputs/apk/debug/tv-debug.apk
```

Debug and preview builds show up in `chrome://inspect` for the WebView.

**In iCloud Drive** (this working tree):

- Use JDK 17. JDK 21's `Files.copy` uses `clonefile()`, which iCloud refuses on synced
  files, and `:tv:mergeDebugResources` fails with "Operation not permitted".
- iCloud drops `"… 2"` conflict copies into `tv/build/` between builds, and R8 or the
  resource parser then fails on duplicates. Run `./gradlew :tv:clean` first and build
  everything in one invocation.

## 6. Device results

Run on `Television_1080p` (Google TV, API 36), final code, unless noted. Keys went in
with `adb shell input keyevent` (`--longpress` for a hold). Focus and received events
were read over the WebView's DevTools socket, and exits from the `wm_finish_activity`
event log. Browse and player tests used the `?ui=tv-demo` fixture. Nothing signed in
to the production site.

| ID | Action | Expected | Result |
|---|---|---|---|
| T1 | Launch | Black, no white flash; page loads; ring arrives | Pass (baseline). Cold start 6 to 7 s; the first launch after installing a new package waits about 60 s for Play Protect |
| T2 | D-pad around browse | Same moves as the browser | Pass: trusted native `ArrowX`/`Enter` events |
| T3 | OK on a tile | Player opens. No grey poster | Pass: black video area. Real playback not tested (§7) |
| T4 | Player: tap Right, hold Right | Tap skips, hold scrubs | Pass: tap is one native `repeat: false`; hold adds synthetic `repeat: true` |
| T5 | Play/pause, FF, RW; hold FF | Once per press; hold repeats | Pass: one synthetic event per press, hold adds `repeat: true`, no native duplicates |
| T6 | Back from player to exit | One rung per press | Pass: controls hide, player closes, ring to rail, exit |
| T7 | Sign-in: OK on email, Back, Back | Keyboard opens; first Back hides it; second Back puts the ring on the field wrapper, no exit | Pass |
| T8 | Back while stuck, on the error screen, on `about:blank` | Exits | Pass, all three |
| T9 | Network off, then on | Native retry screen, then reload | Retry screen with focus on Retry: pass. Retry after the network returns: pass. Automatic reload: **not tested** (§7) |
| T10 | Home during playback | Audio stops | Pass (baseline, sample video); code unchanged |
| T11 | Force-stop, relaunch | Interface size and session kept | Interface size (`localStorage`) and IndexedDB (where Firebase keeps the session) survive and are applied. A real signed-in session was not tested |
| T12 | System font 1.3 | Layout unchanged | Pass: root stays 16 px, same as at 1.0 |
| T13 | Idle on browse | Screen timeout recorded for D3 | Not measured. The screensaver started as soon as the app closed, which fits keep-screen-on holding it off while open |
| — | Navigation allowlist | Off-site and `http:` blocked | Pass: both stay on the page; same-host `https` loads |
| — | Preview build | Beside the real app, on `/preview/tv/` | Pass: loads, bridge and UA token present, Back exits |
| — | `AndroidTvHost.exit()` called outside a Back press, directly and from a timer | Ignored | Pass: the app stays open. Back at the top of sign-in and of browse still exits |
| — | Device-to-device transfer (`LocalTransport` with `is_device_transfer=true`: back up, `pm clear`, restore) | Nothing leaves the device | Pass. Before the fix, 4.4 MB went out and all 46 files came back, IndexedDB included. After it, every domain is excluded, the backup is 0 bytes and nothing comes back |

Also checked: an offline launch within 10 minutes of the last one shows the cached page
(`LOAD_DEFAULT` with `max-age=600`), not the retry screen. Sign-in cannot work then,
but the app is not stuck. This was already the behaviour before this work.

Builds: `:tv:assembleDebug`, `:tv:assemblePreview` and `:tv:assembleRelease` pass.
`:tv:lintDebug`/`lintPreview`/`lintRelease` (run without the build cache) report 0
errors and these warnings: `DiscouragedApi` (fixed landscape) and `VectorRaster`
(banner size). Both are known and left as they are. The web
build is untouched: `./gradlew jsNodeTest jsBrowserDistribution` passes, 80 tests,
with `:tv` in the build.

## 7. Open items and risks

- **Android 8 to 12 are not device-verified.** `Television_720p` (API 31) crashed on
  every boot on this machine: emulator hang then segfault, with GPU modes, snapshots and
  crash reporting all varied. On those versions Back comes only through
  `dispatchKeyEvent`, and that path has no cancelled forward. Check Back on a real
  Android TV 9 to 12 if one is available.
- **Real video.** The office network's TLS inspection is rejected by the emulator for
  `cdn.formula.ge`, so real episodes did not play. Check playback, autoplay, and FF/RW
  on real content on a TV.
- **Auto-reload on network return.** The emulator's Ethernet cannot be taken down from
  the shell, and blocking the app's traffic does not raise `onAvailable`. The code is
  unchanged from before; check it by unplugging a real TV's network.
- **Key-repeat timing and the TV keyboard** differ between the emulator and real remotes.
- **Google TV home row.** Its "Your apps" row showed the preview app with the red icon
  even after a reinstall, although the APK carries the amber one (checked with
  `aapt2`). Cause not found. The two apps still differ by name.
- **The app is only as good as the live site.** A broken deploy to `main` breaks the
  TV app with no reinstall. The preview build (D2) is the mitigation.

## 8. Out of scope

A native Compose-for-TV UI, an offline bundled copy of the web app, launcher channels
and recommendations, voice search, a Play listing, and a CI job that builds the APK.
