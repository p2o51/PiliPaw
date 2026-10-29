# AGENTS.md

Architecture contracts and design rules: [docs/AGENT_BRIEF.md](docs/AGENT_BRIEF.md).

## Icons

- Every icon is `com.gpo.yoin.symbols.YoinSymbols.*` from [Yoin Symbols](https://github.com/p2o51/yoin-symbols). Gradle builds it from the sibling checkout `../yoin-symbols` (`includeBuild` in `settings.gradle.kts`), or from Maven Central when that checkout is missing.
- Do not draw icons in this app (no `ImageVector.Builder`, `addPathNodes`, `PathParser` glyphs, icon-like `Canvas` drawings) and do not copy `YoinSymbols.kt`, drawables or SVGs from Yoin Symbols into this repo.
- Missing a symbol? Add it in `../yoin-symbols` first, following its `CONTRIBUTING.md` ("Requesting or adding a symbol from an app") and its `add-symbol` skill; then use it here as `YoinSymbols.X`. Once this repo has CI, pin the `p2o51/yoin-symbols` checkout to the merged commit and pass `-PyoinSymbolsDir=yoin-symbols` (as Yoin's `.github/workflows/ci.yml` does).
