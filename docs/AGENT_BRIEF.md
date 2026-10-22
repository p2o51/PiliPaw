# PiliPala Compose — brief for feature agents

We are porting the Flutter app **pilipala** (Bilibili third-party client, reference source
at `flutter-src/lib` (git-ignored), read-only) to **Jetpack Compose**, in the
**Yoin design language** ([Yoin](https://github.com/p2o51/Yoin), the owner's music app;
its design doc is `docs/design.md` there). Speed > polish: ship
working, good-looking UI now; tests/QA later. UI language is Chinese (keep pilipala's labels).

## Build
- `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- `./gradlew :app:compileDebugKotlin --console=plain -q` must pass before you finish.
- Do NOT touch any device / adb / emulator (one shared tablet, QA happens later).
- You MAY `curl` public bilibili GET endpoints to see real response shapes
  (add `-H 'User-Agent: Mozilla/5.0' -H 'Referer: https://www.bilibili.com/'`). Never post, never log in.

## Ownership (hard rule)
- Only create/edit files inside YOUR feature package(s) listed in your task.
- Everything else is read-only: `core/**`, `navigation/**`, `ui/**`, other `feature/**`, gradle files.
- Contract stubs in your package (`BiliVideoPlayer`, `ReplyList`, `MemberDynamicsList`, every
  `XxxScreen(route)`) keep their exact signatures — `AppNavHost` and other features call them.
- Need a shared helper? Write it privately in your package. Need a setting?
  `val K = AppSettings.register("myfeature_x", default)` in your package.
- Need a dependency that is not in `gradle/libs.versions.toml`? You can't add one — make do.

## Architecture (already in place — read the files)
- `core/net/BiliClient.kt` — `getData(path, params, wbi = true/false)` returns `data` (throws
  `BiliException` on code != 0), `get(...)` returns the whole envelope, `postForm/postData(url, form)`
  auto-adds `csrf`. Paths starting `/` hit api.bilibili.com; pass full URLs for other hosts
  (`BiliHosts.*`). `BiliClient.isLoggedIn`, `.mid`, `.csrf`, `.cookies`, `.mediaHeaders()`.
- `core/net/Json.kt` — lenient `BiliJson`, loose accessors (`obj.obj("owner").str("name")`,
  `.long/.int/.bool/.arr/.objects()/.path("a.b.0")`) and `decode<T>()` for @Serializable models.
- `core/account/Account.kt` — `Account.user: StateFlow<UserInfo?>`, `refresh()`, `logout()`.
- `core/settings/AppSettings.kt` — observable settings (`AppSettings.danmakuEnabled.state.collectAsState()`).
- `core/util` — `Fmt.count/duration/relativeTime/stripTags`, `IdUtils.av2bv/bv2av`, `Images.thumb`.
- `navigation/Routes.kt` — every destination. `LocalNavigator.current.push(Route.Video(bvid))`,
  `.pop()`, `.openUrl(url)` (maps bilibili links to native routes, else WebView).
- ViewModels: `viewModel { FooViewModel(route.mid) }` (scoped per nav entry).
- Paging: `ui/components/Paging.kt` `Pager` (+ `LoadMoreEffect`), `RefreshBox` (pull to refresh).
- Components: `NetImage`, `Avatar`, `VideoItem` + `VideoCardV` (grid) / `VideoCardH` (row),
  `Stat`, `Tag`, `CoverBadge`, `LoadingState`, `LoadingFooter`, `ErrorState`, `EmptyState`,
  `PiliTopBar`, `PiliLargeTopBar`, `BackButton`, `rememberToast()`, `widthClass()`,
  `videoGridColumns()`, `Modifier.readingWidth()`, `LocalBottomBarInset` (main tabs only).
- Theme: `ui/theme` (ported from Yoin): `YoinMotion.*Spec()` springs, `YoinShapes`,
  `YoinArtworkShapes`, `YoinContainerShapes`, `ContinuousRoundedCornerShape`, `YoinTypography`,
  `CoverTheme(coverUrl) { }` (re-themes a subtree from a cover image).
- Icons: `com.gpo.yoin.symbols.YoinSymbols.*` and `com.gpo.yoin.symbols.PiliSymbols.*`
  (ThumbUp(Filled), Coin(Filled), Danmaku(Off), Comment, Views, Live, Fire, Trophy, Dynamic, Bell, Mail,
  History, WatchLater, Fullscreen(Exit), Pip, PersonAdd/Check, Link, Image, Qr, Logout, DarkMode,
  Palette, Lock, Video, Article, Forward, At, Gift, Brightness, Replay, Subtitles, Hd, Up, Clear…).
  There is NO material-icons dependency. Missing glyph → draw a small private ImageVector
  (24 viewport, 1.5 stroke, round caps) in your package.
- Material3 1.5 alpha (Expressive) is on; experimental opt-ins are module-wide.

## Yoin design rules (follow them — this is the point of the port)
1. **M3 Expressive components first**: `LoadingIndicator`/`ContainedLoadingIndicator` (never a
   spinner), connected `ButtonGroup` / `ToggleButton` for action rows, `SplitButton`,
   `FloatingToolbar`, `LargeFlexibleTopAppBar`, `IconButton(shapes = IconButtonDefaults.shapes())`
   press-morph, `PullToRefresh` LoadingIndicator, wavy progress indicators, `MaterialShapes`
   (cookie/clover/etc. via `toShape()`) for playful avatars/badges, `ModalBottomSheet` for menus
   that act on one item.
2. **Motion = springs.** Use `YoinMotion.defaultSpatialSpec()/fastSpatialSpec()/defaultEffectsSpec()`
   (or `MaterialTheme.motionScheme`). No tweens/easing curves except platform-mimicking page slides.
   Everything that changes state animates (AnimatedContent/animate*AsState), zero hard cuts.
3. **Color = M3 tokens only** (`MaterialTheme.colorScheme.*`). The app theme is a dual-seed
   expressive scheme (`ExpressiveColorSchemeFactory.fromSeeds`: primary from the wallpaper's
   seed, tertiary from a second seed; 设置 › 外观 › 配色风格 can switch back to the system scheme).
   Video/bangumi/live/member pages wrap in `CoverTheme(cover)` (cover base + accent, Yoin-style)
   and paint `ExpressivePageBackground`. Hard-coded colors only for scrims over video/images.
4. **Color blocks, not strokes**: no `HorizontalDivider`, no outlined cards, no borders on covers
   or avatars. Separate groups with `surfaceContainer*` blocks + spacing. Cards = filled surfaces
   with `YoinContainerShapes.Card` (continuous corners).
5. **Typography**: always `MaterialTheme.typography.*` (Google Sans Flex); never a bare
   `TextStyle()`; never change letterSpacing. Build hierarchy with size/weight/color.
6. **Copy**: labels, not narration. No instructions ("点击…可以…"), no questions, no encouragement,
   no persistent hints. Do NOT join metadata with "·", "•", "—" or "|" — lay them out as separate
   texts/icon+number pairs with spacing and different weights/colors.
7. **Covers/avatars**: bare images, no borders; covers 12dp continuous corners in cards.
8. **Header breathing**: leave clear space between back button and titles.
9. **Edge-to-edge**: the window is edge-to-edge. Pushed pages pad for status/navigation bars
   themselves (Scaffold or `WindowInsets.*`); main-tab pages add `LocalBottomBarInset.current` to
   their bottom content padding so the floating bar never covers content.
10. **Adaptive** (owner tests on a Pixel Tablet): Compact < 600dp, Medium 600–840 (tablet portrait
    is 800dp), Expanded ≥ 840 (tablet landscape is 1280dp). Grids use `videoGridColumns()`;
    reading content uses `readingWidth()`; detail pages go two-pane on Expanded (e.g. player +
    intro left, comments right). Nothing may stretch edge-to-edge as a 1280dp-wide text column.
11. **Native Android over custom**: system share sheet (`Intent.ACTION_SEND` chooser), Custom Tabs
    (androidx.browser) for external links, PiP, MediaSession, predictive back (`BackHandler`/
    `PredictiveBackHandler`), haptics (`LocalHapticFeedback`, `HapticFeedbackType.Confirm/ToggleOn/
    ToggleOff/LongPress/SegmentTick`), `ClipboardManager`, MediaStore for saving images,
    IME insets (`imePadding`), system photo picker if images are attached.
12. Horizontal scrolling rows bleed full-width with `contentPadding` matching page margins (16dp),
    never clipped mid-page.

## Finish
- `./gradlew :app:compileDebugKotlin` passes.
- `git add -A && git commit -m "<feature>: …"` in your worktree (no other branches, no push).
