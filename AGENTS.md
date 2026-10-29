# AGENTS.md

Architecture contracts and design rules: [docs/AGENT_BRIEF.md](docs/AGENT_BRIEF.md).

## Icons

- Every icon is `com.gpo.yoin.symbols.YoinSymbols.*` from [Yoin Symbols](https://github.com/p2o51/yoin-symbols). Gradle always builds it from the sibling checkout `../yoin-symbols` (`includeBuild` in `settings.gradle.kts`) and stops when that checkout is missing; `git -C ../yoin-symbols pull` brings in icon updates.
- Do not draw icons in this app (no `ImageVector.Builder`, `addPathNodes`, `PathParser` glyphs, icon-like `Canvas` drawings) and do not copy `YoinSymbols.kt`, drawables or SVGs from Yoin Symbols into this repo.
- Missing a symbol? Add it in `../yoin-symbols` first, following its `CONTRIBUTING.md` ("Requesting or adding a symbol from an app") and its `add-symbol` skill; then use it here as `YoinSymbols.X`. Once this repo has CI, check out `p2o51/yoin-symbols` at `main` into `yoin-symbols/` and pass `-PyoinSymbolsDir=yoin-symbols` (as Yoin's `.github/workflows/ci.yml` does), so a merged icon change reaches the next build with nothing to bump.
