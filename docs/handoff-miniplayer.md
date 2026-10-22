# Handoff: 继续播放 mini bar / floating window (merge/pipepaw)

State at handoff: 3ebda47 plus this note. Unit tests pass (66 at 5d5ea5e, more since). None of the animations below have been watched on a device by this thread: adb shell was blocked for most of the work, then the tablet was busy.

## Files

| File | What |
|---|---|
| `feature/sources/PlayerHost.kt` | `PlayerHostMachine` (pure) + `PlayerHost` (applies its actions) |
| `feature/sources/MiniBarMotion.kt` | pure: springs, size axis, geometry (`miniGeo`), gesture rules (`MiniGestures`), `MiniTouch` (activity-level finger tracking), `miniBarWidth` |
| `feature/sources/KeepPlaying.kt` | `KeepPlaying` (rest state, window centre/corner, arrival, landing, openFrom), `NavSpot`, `BiliKeeper`, `MiniBarHost` (layout, gesture detector, releases), `MiniLog` |
| `feature/video/VideoSwipeDown.kt` | the B站 page's swipe down on its picture + its `HeroDock` registration |
| `navigation/transition/PageTransitions.kt` | `startSwipeBack`/`SwipeBack`, `HeroDock` registry, `PageTransitionsHost` |
| `yt/feature/player/Player.kt`, `PlayerOverlay.kt` | YouTube: `expanded` setter → PlayerHost, `overlayUp`, collapse lands on the bar tile / `openFrom` / `landing` |
| `feature/player/PlayerControls.kt` | drag mode 5 (`PictureSwipeDown`, `LocalPictureSwipeDown`) |
| `MainActivity.kt` | `dispatchTouchEvent` → `MiniTouch.observe` |
| `ui/shell/FloatingNavBar.kt` | `layout {}` that opens room for the merged circle (`KeepPlaying.navRoom`) |
| tests | `PlayerHostMachineTest`, `MiniAxisTest`, `MiniGeoTest`, `MiniGesturesTest` |

## PlayerHost (one player for the user)

`PlayerSlot` = `YouTube` | `Bili(controller)` (identity). The machine tracks `front` (a page's player, or YouTube open) and `docked` (in the bar).

- `opened(slot)`: always `ResetDock`; releases a docked *other* slot; a B站 page opening releases YouTube's (overlay); `front = slot`.
- `started(slot)`: a page player starting releases the docked other one; the docked one toggled from the bar changes nothing.
- `dock(slot)`: refused (null → the caller releases) unless `front == slot`. That is how a page covered by the *next* video page never docks (it leaves after the new page opened).
- `released(slot)`: clears front/docked/remembered.
- Remembered form: `openingFromDock(form)` before a tap opens the docked picture; the next `opened` of the same slot keeps it; `dock` returns `Restore(form)` once. A card open forgets it.
- Callers: `rememberBiliPlayerState` (opened), `PiliPlayerController` listener (started) and `release()` (released), `BiliKeeper.offer` (dock), `Player.play` / `expanded` setter / `close`.

Invariant: at most one docked player; opening anything releases a docked other one exactly once.

## Size axis and geometry (MiniBarMotion.kt)

One axis = the picture's long side (px): 48dp circle ← row tile (bar height × 16/9) → +40dp detaches into a window of the video's own aspect → max (screen −24dp, tablet 520dp, 60% of the height). Rubber band ×0.3 past the ends. A pinch never opens the video. `miniGeo` maps the axis to the four block rects, the window keys, the hit rect and the goo neck. It is continuous across merge/detach (tests).

Bar: −15 / pause / +15 are squares as wide as the bar is tall (56dp phone, 52dp tablet), the picture 16:9, 3dp gaps; the bar hugs its blocks. It sits 8dp over the nav bar (phone), left of it on a tablet when it fits, or at the bottom on pages without a nav bar (`miniBarRect`). Springs: FAST 1400/0.9, DEF 700/0.86, SLOW 320/0.9, LIQ 420/0.68.

## Gesture detector (`miniGestures`, one per bar/window)

- Reads on `PointerEventPass.Initial`. `MiniTouch` (from the activity) adds fingers that landed outside the bar.
- Window or not is decided by `KeepPlaying.rest is Window`, not the live size (the LIQ spring dips below the detach size while settling; that was the "two drags to merge" bug).
- Tap: exactly one finger ever, under slop, within the long-press timeout (`MiniGestures.classify`). Taps resolve by rect: picture opens, side blocks seek or pause. No `clickable`s (only a11y semantics).
- Two fingers anywhere: pinch on the axis, 1:1; a window grows about the fingers' centroid. A pinch stays a pinch until every finger is up.
- One finger past slop (`dragIntent`):
  - Window: move it.
  - Bar sideways: swipe away (35% or 1000dp/s).
  - Bar picture upward: pop-out (progress = lift / 48dp; ≥ 0.5 on release → window; dragging back down undoes it).
  - Merged circle: nothing.
- Window in a bottom corner, dragged mostly down: progressive merge (progress = dy / (window bottom → nav centre, 80–120dp)); on release > 0.4 or > 500dp/s down → dock (LIQ + CONFIRM haptic), else back to its corner.
- Window release:
  - A hard fling off a side (> 2500dp/s) closes it.
  - Otherwise it goes to a corner: projection v × 0.38s, direction bias over 250dp/s, LIQ carrying the velocity, CLOCK_TICK.
  - The corner persists through resizes and rotation. A new window defaults to bottom right. Bottom corners rest 32dp above the nav bar, or just above the system inset without one.
  - No throw-to-dock.
- Window keys (play bottom-left, close bottom-right): always on, ≥ 32dp, touch padding outward only. They fire only on a clean tap that began on them (`keyFires`). Close is also ignored while the window settles and for 300ms after it moved. The backing fades after 3s idle; any touch restores it.

## Flight ownership (who starts what)

| Transition | Owner | Animated? |
|---|---|---|
| B站 page → bar (back gesture, back button, swipe down) | `PageTransitions.prepareHero` via `HeroDock` registered in `VideoSwipeDown.kt` (enabled when `BiliKeeper.wouldKeep(front)`) → hero flies the captured frame to `dockTile`/remembered rect; `KeepPlaying.arrive(frame, shell)`; page pops → `offer` → `dock`; `MiniBarHost` takes over in place (shown snapped, side blocks grow in once) | yes |
| YouTube player → bar | `PlayerOverlay` progress spring to the tile (`landing` ?: `openFrom` ?: `dockTile`); bar shows only when `overlayUp` is false, `arrive(null)` | yes |
| YouTube listen page → bar | `Listen.kt` folds over the whole collapse (5d5ea5e) | yes |
| bar / circle / window → page | tap: YouTube grows from `openFrom` (the tile's rect); B站 page container-transforms from the tile (`pageSharedSource` under `PageTransitionsHost`) | yes |
| bar ↔ circle (scroll, 130ms debounce) | `MiniBarHost` rest effect, LIQ | yes |
| bar ↔ window (pinch, pop-out, bottom-corner merge) | detector releases | yes |
| window corner snap | `snapWindow` | yes |
| close | bar or window shrinks as it fades | yes |
| bar following the nav bar (page push/pop) | `anchor` Animatable, DEF | yes |
| keep playing starts without a flight (no frame) | fade in | fade only |

Debug log: `MiniLog.event` (debug builds only), logcat tag `PiliMini`, `#<running id> <what> <detail> (<file:line>)`. It logs opened, dock / dock refused, released, hero flies to bar, arrive, bar takes over / fades in / fades out, YouTube opens / collapses, window to corner, and window merges into bar. A double trigger shows as two lines. There is no per-flight token yet: add one if the log shows doubles.

3ebda47 fixes:
- Double return on 收藏 etc. A landing over the shell holds `NavSpot.last` for 1.5s (`navForBar`), so the bar no longer drops to the no-nav spot and springs back before the shell re-reports its nav bar.
- Side-block flicker. `sidesIn` starts at 0 and is reset to 0 after every fade-out, so an arriving bar never shows its blocks for a frame first.

## Known gaps

- B站 bar video re-attaches ~400ms after the bar appears (the page's view must detach first). The landed frame (`frame.arrived`) covers it for 1.2s; unverified.
- The immersive portrait B站 layout has no hero capture: its bar fades in instead of flying.
- A paused YouTube video may show its cover in the bar until the next frame renders.
- Pinching from the 48dp circle is impractical (both fingers must start on it).
- The goo neck only draws for the phone bar merging into the circle, not for the window → bar merge.

## Device testing

- `docs/tools/miniplayer-tablet.sh install|start|log|shot NAME|tap X Y|swipe …|back|reset`: dedicated adb server :5038 on the Pixel Tablet (see ~/.claude/CLAUDE.md). `log` dumps `PiliMini`. Use the debug APK (logging is debug-only). Hand the tablet back with `reset` and PipePaw in front.
- `MiniBarPinchTest` (androidTest) injects real two-finger touches: `adb shell am instrument -w -e class com.gpo.pilipala.MiniBarPinchTest -e from 80 -e to 160 com.gpo.pilipala.test/androidx.test.runner.AndroidJUnitRunner`; screenshots land in the app's `files/pinch`. It opens a YouTube video, collapses it, then pinches the bar's TextureView.
