# KiteSSOT

A root-project Gradle plugin that gives a Kotlin Multiplatform repo one place to
declare app identity, and propagates it to the platform files that would
otherwise each hold their own copy.

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.yuroyami.kitessot?label=plugin%20portal)](https://plugins.gradle.org/plugin/io.github.yuroyami.kitessot)
[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/KiteSSOT/ci.yml?branch=main&label=CI)](https://github.com/yuroyami/KiteSSOT/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](https://yuroyami.github.io/KiteSSOT/)** · the setup guide, plus
the generated API reference.

## What you get

A typical Kotlin Multiplatform (KMP) repo records app name, version, bundle ID,
locales, Android SDK levels and Java level four times. They live in the Android
module's `defaultConfig`, the Xcode project's build settings, the source
`Info.plist`, and whatever constant common Kotlin code reads. The four copies
stop matching.

KiteSSOT declares them once in the root build, and splits the work in two.

KiteSSOT applies the Gradle configuration on every build. It runs inside the
`finalizeDsl` hook of AGP, the Android Gradle plugin. That hook runs after a
module's own `android { }` block, so a value set in `kiteSsot { }` replaces the
same value set in the module.

Edits to files you own never happen during a build. That covers
`project.pbxproj`, `Info.plist`, `Podfile`, Swift imports and launcher icons.
Each one needs its own block configured, plus an explicitly named task. A plain
`./gradlew build` writes nothing outside `build/`, and CI asserts that on every
commit.

## Usage

Add the plugin to the **root** `build.gradle.kts`, alongside Kotlin and AGP.
A module that packages a Compose Desktop app also needs
`org.jetbrains.compose` and `org.jetbrains.kotlin.plugin.compose` declared at
the root.

Every one of those stays `apply false`: KiteSSOT reads their typed classes
from its own classloader, and that only works when they are declared at the
root, not inside a subproject's `plugins { }` block. Compose adds its own
rule on top of that: `org.jetbrains.compose` refuses to apply without the
Kotlin Compose compiler plugin on the same classpath, so the two are always
declared together. Give `org.jetbrains.kotlin.plugin.compose` the same
version as `kotlin("multiplatform")` above; that plugin tracks the Kotlin
release, not the Compose one.

```kotlin
// <repo-root>/build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.compose") version "1.12.0-rc01" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("io.github.yuroyami.kitessot") version "3.0.0"
}
```

Applying it anywhere but the root throws immediately: the plugin aggregates
across every module from there, so a submodule apply can't do its job.

The whole DSL follows one law:

1. **Facts always flow.** A declared fact reaches every platform found, on every
   build, in memory or as files under `build/`. Declaring it is the consent.
   `skip()` and `only()` beside the fact are the only flow control.
2. **`rewrite { }` is the only word that acts on your files.** It arms a by-name
   task that edits source. `dryRun`, `backups`, and `onConflict` always apply.
3. **One topic, one block.** Platform corners nest inside topics. Platform
   blocks hold only platform-exclusive things.

Below is the full surface. Nothing is required beyond `appName`, `version`, and
`id` on the root.

```kotlin
kiteSsot {
    // Simple forms: appName = "Jetzy" / id = "..." / version = "1.4.0".
    // Detailed forms take the value plus a block, shown where useful.

    appName("Jetzy") {
        ios("Jetzy Lite")              // ios shows its own name
        skip(desktop)                  // desktop keeps what it has
    }

    jvmTarget = 21                     // Java + Kotlin JVM level, whole build

    id("com.example.jetzy") {
        android { suffix = ".android" }   // applicationId = base + suffix
        ios     { suffix = ".ios" }       // bundle id     = base + suffix
        desktop { suffix = ".desktop" }
    }

    version("1.4.0") {
        // one formula: version to every store build number
        formula { v -> 1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.reupload }
        android {
            reupload = 1               // re-upload same version to Play
            shipped  = 1001003090      // highest code ever shipped, guard floor
            // pin = 123               // hard versionCode, formula skipped
        }
        ios {
            shipped = "1001003090"
            // pin = "42"              // hard buildNumber
            // marketingVersion = "1.4.0"
        }
        desktop { shipped = "1001003090" }
    }

    locales {
        // omit the block entirely to auto-detect from Compose resources
        pin("en", "ar", "fr")          // hand list, detection skipped
        filterAndroidRes = true        // drop Android res outside the list
    }

    logo {
        foreground = file("art/logo-fg.png")
        backgroundColor = "#0B0B0F"
        android { safeZone = 0.611 }   // launcher icon safe zone
        desktop { roundMac = true }    // desktop icons flow from presence
        rewrite { replaceOld = true }  // arms kiteRewriteLogo, source edits
    }

    splash {
        // empty block already works: art defaults to logo, plate to its color
        dark { backgroundColor = "#000000" }
        android { theme = "AppTheme" }  // your app theme; generated KiteSplash inherits it,
                                        //   and your Manifest points at it once:
                                        //   android:theme="${'$'}{kiteSplashTheme}"
        rewrite { }                     // arms the iOS launch-screen delivery
    }

    optIns {
        add("kotlinx.cinterop.ExperimentalForeignApi")
        projects(":shared")
        builtIns = true
    }

    android {
        sdk(min = 26, target = 36, compile = 36)
        ndk = "27.1.12297006"
    }

    ios {
        deploymentTarget = "15.0"
        // pbxproj / podfile / infoPlist / appDirectory / appIconDirectory
        //   only when detection guesses wrong
        rewrite {                      // arms kiteRewriteXcode, source edits
            targets("iosApp")
            cleanPlist = true
            onConflict = io.github.yuroyami.kitessot.PlistConflictPolicy.FAIL
            renameSharedModule(from = "shared", to = "Shared")
        }
    }

    desktop {
        linuxPackageName  = "jetzy"
        deriveUpgradeUuid = true       // stable Windows MSI upgrade id from id
    }

    web { ioWorker { targets("js") } }       // presence generates the worker

    buildConfig {                      // presence generates into build/
        packageName = "com.example.jetzy"
        className   = "AppInfo"
        stringField("API_HOST", "api.jetzy.app")
    }

    modules {                          // only when detection guesses wrong
        shared = ":shared"
        androidApps(":androidApp")
        desktopApps(":desktopApp")
    }

    // skip(desktop)                   // root master: platform receives nothing

    dryRun  = false                    // armed rewrites print, write nothing
    backups = true                     // recovery copy before any rewrite
}
```

Five read-only providers are worth wiring into your own build logic: `versionCode`,
`androidApplicationId`, `iosBundleId`, `desktopBundleId`, `canonicalLocales`, and
`resolvedSharedProjectPath`. Hand any of them straight to another plugin's
`Property`, no `.get()` needed:

```kotlin
val ssot = extensions.getByType<io.github.yuroyami.kitessot.KiteSsotExtension>()
someOtherTask.someProperty.set(ssot.androidApplicationId)
```

Run `./gradlew kiteVerify` after any change. It resolves the whole model
above and prints it. It writes nothing.

## The reshape, old name to new

The 2.x compatibility layer is gone and the 3.0 surface was reshaped into the
topic form above. The renames:

| Before | Now |
| --- | --- |
| `appId` | `id` |
| `scheme { }` | `version("x") { formula { } }` |
| `rebuild` | `reupload` inside a `version` corner |
| `versionCode` / `buildNumber` overrides | `pin` inside a `version` corner |
| `publishedVersionCode` / `publishedBuildNumber` | `shipped` inside a `version` corner |
| `android { idSuffix }`, `ios { bundleIdSuffix }` | `id("base") { android { suffix } }` |
| `android { filterResourcesToLocales }` | `locales { filterAndroidRes }` |
| `logo { androidSafeZone }` | `logo { android { safeZone } }` |
| `desktop { roundMacOsIcon }` | `logo { desktop { roundMac } }` |
| `logo { takeOverLegacyIcons }` | `logo { rewrite { replaceOld } }` |
| `ios { sync { } }` | `ios { rewrite { } }` |
| `sanitizePlist` | `cleanPlist` |
| `nativeOptIns { }` | `optIns { }` |
| `propagate { x = false }` | `skip(platform)` beside the fact, or at the root |
| every `enabled` flag | delete the block, or `skip()` the platform |

Desktop identity now flows automatically when a Compose Desktop app module
exists, exactly like Android and iOS. Derived version codes are unchanged.

## Tasks

All in the `kitessot` group. None is attached to `build`, `check` or any other
lifecycle task, so the mutating ones run only when named.

| Task | Writes | What it does |
| --- | --- | --- |
| `kiteVerify` | nothing | Prints the resolved model |
| `kiteDoctor` | nothing | Diagnoses the setup; never fails the build |
| `kitePlan` | nothing | Lists the mutations the armed config authorizes, and their exact paths |
| `kiteCheck` | `build/` | Same checks, writes a JSON or SARIF report, fails on ERROR findings |
| `kiteRewriteLogo` | source | Installs the logo into Android res and the iOS asset catalog |
| `kiteRewriteXcode` | source | Applies Xcode build settings, plist, Podfile and Swift plans |
| `kiteInternalBuildConfig` | `build/` | The `commonMain` constants object, wired automatically |
| `kiteInternalIoWorker<Target>` | `build/` | The browser worker helper, wired automatically |
| `kiteInternalDesktopIcons` | `build/` | The desktop `.icns`, `.ico` and `.png` icons, wired automatically |

The `kiteInternal*` tasks run on their own as part of ordinary builds; the two
`kiteRewrite*` umbrellas run only when armed by a `rewrite { }` block and only
when you invoke them.

There is deliberately no aggregate "sync everything" task. Each source-tree
mutation is a separate decision, with its own review, backup and recovery
boundary.

Set `dryRun = true` to make the mutating tasks report what they would write,
without writing it. Generated Kotlin under `build/` ignores `dryRun`, because it
is a build input.

The four reporting tasks colour their output by severity when a real terminal is
attached. `NO_COLOR`, `TERM=dumb`, and `--console=plain` each turn it off, so
piped output and CI logs stay plain text. Force it either way with
`-Pkitessot.color=true` or `-Pkitessot.color=false`.

## More on the DSL

The [Usage](#usage) block above is the whole surface. Every property there
carries the same KDoc in your IDE: whether it's optional, what its default is,
and which other values it needs. The published javadoc jar carries it as
Dokka HTML, and [FEATURES.md](FEATURES.md) is the prose reference for behavior
and safety rules beyond a single property.

## Compatibility

| Component | Supported |
| --- | --- |
| Gradle | 8.5 and newer |
| Android Gradle plugin | 8.5.2 through 9.3.x |
| Kotlin Gradle plugin | 2.4.x |
| JDK running Gradle | 17 and 21 |

The build uses the JDK 21 toolchain and emits Java 17 bytecode, so a JDK 17
Gradle daemon can load the plugin. The plugin supports the Gradle configuration
cache, which stores the result of the configuration phase and reuses it on the
next build.

249 tests across 23 files. 242 of them run in `./gradlew test`. The other 7 run
in `./gradlew agpCompatibilityTest`. That task starts real consumer builds on
Gradle 8.5, 8.9 and 9.5.1. It uses AGP 8.5.2 and 9.3.1, and KGP 2.4.0.

CI builds on ubuntu-24.04 with JDK 17 and JDK 21, on macos-15 with JDK 21, and on
windows-2025 with JDK 21. Each build runs twice. CI then asserts that the second
run reuses the configuration cache entry, and that the tracked working tree is
unchanged.

## Limits

- No Gradle Isolated Projects support. The plugin aggregates from the root across
  `allprojects`, which that mode forbids.
- One app identity per build: no per-flavor, per-build-type or per-Xcode-target
  overlays.
- KiteSSOT edits `project.pbxproj`, `Info.plist`, `Podfile` and Swift files as
  text. It does not generate xcconfig files and does not parse a syntax tree. It
  rejects binary and generated plists instead of converting them.
- The deprecated 2.x root properties are still present, so an old name and its
  3.0 block can set the same value. The block wins, and the old name warns.
- The browser worker is Kotlin/JS browser only. It does not run on Node and it
  does not run on wasm. It executes caller-supplied JavaScript text, so that text
  must never come from user input. The deployed Content Security Policy, the HTTP
  header that tells a browser which scripts it may run, also has to allow workers
  created from a blob URL. That normally means `worker-src blob:`.
- `buildConfig` is not a secret store. Every value reaches generated source, task
  inputs, build scans and shipped binaries.
- The desktop build number reaches macOS only. Windows and Linux packaging
  have no separate build-number field, so the desktop `version` corner affects
  the macOS bundle version alone.
- Locales are not propagated to desktop packaging. `desktop { }` also does not
  manage `vendor`, `description` or `copyright`; set those directly on the
  Compose `nativeDistributions` block.
- Desktop application detection reads Kotlin `internal` members on the Compose
  Desktop extension reflectively, since Compose exposes no public API for it.
  If a future Compose release renames them, detection degrades to requiring an
  explicit `modules { desktopApps(...) }` selector instead of silently
  detecting nothing.
- Nobody has yet verified a real Xcode archive, signing or App Store upload driven
  by these outputs, or live browser CSP execution of the worker.
- Nobody has yet verified a real signed DMG, a real MSI upgrade across two
  versions, or a real Debian install driven by these outputs.

## Releasing

The published version comes only from `-PkiteSsot.version`, and defaults to
`0.0.0-SNAPSHOT`. To publish a release, add a `## [x.y.z]` heading to
[CHANGELOG.md](CHANGELOG.md), commit, tag `vx.y.z`, then push the tag.

The `verifyReleaseMetadata` guard checks four things:

- It rejects a version that is not semantic versioning (`major.minor.patch`).
- It rejects anything ending in SNAPSHOT.
- It requires the tag to equal `v<version>`.
- It requires the matching changelog heading.

[CONTRIBUTING.md](CONTRIBUTING.md) has the development workflow. Security reports
go through [SECURITY.md](SECURITY.md).

## License

Apache-2.0. See [LICENSE](LICENSE) and [CHANGELOG.md](CHANGELOG.md).

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[KiteImage](https://github.com/yuroyami/KiteImage),
[KiteQR](https://github.com/yuroyami/KiteQR).
