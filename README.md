# PiliPala Compose

A Jetpack Compose rewrite of [guozhigq/pilipala](https://github.com/guozhigq/pilipala) (Flutter), a third-party Bilibili client, in the Yoin design language: Material 3 Expressive, system dynamic color with cover-driven palettes on detail pages, Google Sans Flex, continuous corners, spring motion, Yoin Symbols.

## Build

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug        # debug
./gradlew :app:assembleRelease      # release, signed with the debug key
```

Requires the Android SDK (compileSdk 36). `flutter-src/` (the original Flutter source, used as a reference) is git-ignored.

## Layout

- `app/src/main/java/com/gpo/pilipala/core` — network (cookies, WBI signing, buvid), account, settings
- `navigation` — routes, container-transform pushes, in-window predictive back
- `feature/*` — one package per area (home, video, player, reply, dynamics, search, member, library, live, message, settings…)
- `feature/player/cdn` — overseas line routing
- `yoin-symbols` — the Yoin Symbols icon library, vendored as a module
- `docs/AGENT_BRIEF.md` — architecture contracts and design rules

## Credits

- [pilipala](https://github.com/guozhigq/pilipala) — the original app, API usage and feature set.
- [bilibili-accelerator](https://github.com/realzza/bilibili-accelerator) (MIT) — the line-switching approach used in `feature/player/cdn`.
- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect) — API documentation.
