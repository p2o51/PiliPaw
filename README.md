# PiliPaw

PiliPaw (霹雳啵) is a Jetpack Compose rewrite of PiliPala, the Flutter Bilibili client by guozhigq ([guozhigq/pilipala](https://github.com/guozhigq/pilipala)); it is not related to PiliPawa (噼哩啪哇).

A third-party Bilibili client in the Yoin design language: Material 3 Expressive, system dynamic color with cover-driven palettes on detail pages, Google Sans Flex, continuous corners, spring motion, Yoin Symbols.

基于 [guozhigq/pilipala](https://github.com/guozhigq/pilipala)（PiliPala）重写。PiliPaw 以 GPL-3.0 发布，见 [LICENSE](LICENSE)。

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

- [PiliPala](https://github.com/guozhigq/pilipala) by guozhigq (GPL-3.0) — the original app this is rewritten from: API usage and feature set.
- [bilibili-accelerator](https://github.com/realzza/bilibili-accelerator) (MIT) — the line-switching approach used in `feature/player/cdn`.
- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect) — API documentation.
