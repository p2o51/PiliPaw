pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // PipePipeExtractor builds on a Java 25 toolchain; foojay provisions one when the machine has none.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // nanojson (the extractor's JSON parser) is only on JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "PiliPala"
include(":app")

// Icons: Yoin Symbols (io.github.p2o51:yoin-symbols), always built from source: the checkout
// next to this repo, or -PyoinSymbolsDir=<path>. Every app reads the same current symbols this
// way, so there is no Maven Central fallback; without the checkout the build stops here.
val yoinSymbolsDir = providers.gradleProperty("yoinSymbolsDir").getOrElse("../yoin-symbols")
val yoinSymbolsBuild = file("$yoinSymbolsDir/android")
check(yoinSymbolsBuild.isDirectory) {
    "Icons come from Yoin Symbols source: git clone https://github.com/p2o51/yoin-symbols " +
        "${yoinSymbolsBuild.parentFile.absolutePath}, or pass -PyoinSymbolsDir=<path>."
}
includeBuild(yoinSymbolsBuild)

// YouTube: the upstream extractor, built from the submodule (PipePipeClient is not needed here).
includeBuild("PipePipeExtractor")
