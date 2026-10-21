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
include(":yoin-symbols")

// YouTube: the upstream extractor, built from the submodule (PipePipeClient is not needed here).
includeBuild("PipePipeExtractor")
