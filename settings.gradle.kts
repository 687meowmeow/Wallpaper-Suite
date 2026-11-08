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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // v0.8.0: google() FIRST (content-filtered) — the canonical repo for
        // androidx/AGP artifacts is reliable and was never the rate-limited one
        // (v0.7.14's 429s came from Maven Central). When the Aliyun mirrors sit
        // first, one Aliyun 502 (like 2026-11-08 07:32 UTC) disables the
        // repository mid-resolution and kills the whole build.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        // v0.7.14: Aliyun mirrors before Maven Central to avoid its rate
        // limiting (429) for non-Android artifacts.
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        mavenCentral()
        gradlePluginPortal()
        // Winlator publishes some artifacts to JitPack (e.g. vortek/gladio native binaries)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "WepWawet"
include(":app")
