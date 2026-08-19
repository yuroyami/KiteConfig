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
Both stay `apply false`: KiteSSOT reads their typed classes from its own
classloader, and that only works when they are declared at the root, not
inside a subproject's `plugins { }` block.

```kotlin
// <repo-root>/build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    id("com.android.application") version "9.3.1" apply false
    id("io.github.yuroyami.kitessot") version "3.0.0"
}
```

Applying it anywhere but the root throws immediately: the plugin aggregates
across every module from there, so a submodule apply can't do its job.

Below is every DSL entry that exists, each with its real default. Nothing
here is required beyond `appName`, `version`, and `appId` on the root; every
block, dial, and file path is opt-in on top of that.

```kotlin
kiteSsot {
    // ---- the shared truth, declared once, read by both platforms ----
    appName = "Jetzy"                  // display name; Android manifest + Apple Info.plist
    version = "1.4.0"                  // versionName / marketing version; feeds scheme below
    appId = "com.example.jetzy"        // reverse-DNS base; android.idSuffix / ios.bundleIdSuffix extend it
    locales = listOf("en", "pt-BR")    // default: auto-detected from Compose resources' values-* folders
    jvmTarget = 21                     // Java + Kotlin JVM compatibility across every module

    // ---- the one formula that turns `version` into a store build number ----
    // default: packs 1|major(3)|minor(3)|patch(2)|rebuild(1), so 1.4.0 -> 1001004000.
    // Android reads the Int as versionCode; Apple reads the same number, as text,
    // for CURRENT_PROJECT_VERSION. Write your own to replace the layout entirely:
    scheme { v -> 1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.rebuild }

    // ---- safety, both overridable per invocation ----
    dryRun = false                     // true: mutating tasks report, write nothing. -Pkitessot.dryRun=true
    backups = true                     // recovery copy before a rewrite. -Pkitessot.backups=false

    // ---- where your modules live ----
    modules {
        shared = ":shared"                          // default: the sole module applying Kotlin Multiplatform
        androidApps(":androidApp")                   // default: the sole Android application module
        androidAppDirectory = layout.projectDirectory.dir("androidApp") // default: found from the app above
        composeResources = layout.projectDirectory.dir("shared/src/commonMain/composeResources") // default: shared's own
    }

    // ---- which values KiteSSOT is allowed to apply, all on by default ----
    propagate {
        appName = true                 // off: the manifest placeholder and Apple name stay untouched
        bundleId = true                // off: applicationId / bundle ID stay untouched
        version = true                 // off: beats every version setting below, including an explicit versionCode
        locales = true                 // off: locale metadata is still computed and reported, just not written
    }

    // ---- Android-only: identity suffix, SDK levels, the Play re-upload dial ----
    android {
        idSuffix = ".debug"            // default: empty, so applicationId == appId
        versionCode = 140              // default: the root scheme; assign to bypass it entirely
        rebuild = 1                    // default: 0. Play keeps every uploaded versionCode forever; bump this to re-upload
        // scheme { v -> ... }          // rare: override the root scheme for Android alone
        compileSdk = 36                // default: unset, leaves each module's own value alone
        minSdk = 26                    // default: unset, leaves each module's own value alone
        targetSdk = 36                 // default: unset. Applications only; AGP removed it from libraries
        ndk = "27.0.12077973"          // default: unset. Classic Android modules only
        publishedVersionCode = 139     // default: unset, no check. When set, the next code must exceed it
        applySdkLevels = true          // off: compileSdk/minSdk/targetSdk/ndk above are computed, not written
        filterResourcesToLocales = false // true: narrows packaged resources to `locales`. Changes shipped output
    }

    // ---- Apple-only: identity suffix, build number, paths, and explicit source sync ----
    ios {
        bundleIdSuffix = ".iosApp"     // default: empty, so the bundle ID equals appId
        marketingVersion = "1.4.0"     // default: the root version; needs three numeric parts
        // buildNumber = "42"           // default: the root scheme, rendered as text; assign to bypass it
        rebuild = 3                    // default: 0. TestFlight refuses a build number it already saw for this version
        // scheme { v -> ... }          // rare: override the root scheme for iOS alone
        // publishedBuildNumber = "1001004000" // release-time guard: the next resolved number must beat this, componentwise
        deploymentTarget = "14.0"      // required by the universal AppIcon installer; asset check only, not IPHONEOS_DEPLOYMENT_TARGET

        // typed paths, defaults shown; only set the ones your tree actually moved
        pbxproj = file("iosApp/iosApp.xcodeproj/project.pbxproj")
        podfile = file("iosApp/Podfile")
        infoPlist = file("iosApp/iosApp/Info.plist")
        appDirectory = layout.projectDirectory.dir("iosApp")
        appIconDirectory = layout.projectDirectory.dir("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")

        // configuring this block IS the opt-in for the explicit Apple source tasks.
        // it authorizes them; you still run kiteSsotSyncIosConfig / kiteSsotSanitizeIosProject yourself.
        sync {
            enabled = true              // false always wins, even over a configured block
            targets("iosApp")           // default: empty, which can still select a sole application target
            sanitizePlist = false       // true: also maintain SSOT keys in a source XML Info.plist
            onConflict = PlistConflictPolicy.FAIL   // or .KEEP, or .REPLACE, when a plist value already differs
            nonExemptEncryption = false // ITSAppUsesNonExemptEncryption. default: unset, key left alone
            proMotion = true            // CADisableMinimumFrameDurationOnPhone. default: unset, key left alone
            renameSharedModule(from = "OldShared", to = "Shared") // one call: Podfile + Swift import migration
        }
    }

    // ---- app icon: configuring this block IS the opt-in for the logo-install tasks ----
    logo {
        enabled = true                  // false always wins, even over a configured block
        foreground = file("art/logo_fg.png")
        backgroundColor = "#102A43"     // or background = file(...); set exactly one, never both
        androidSafeZone = 66.0 / 108.0  // default. Fraction of the adaptive icon canvas the foreground fills
        takeOverLegacyIcons = false     // true: claim known legacy/colliding icon files; still backed up first
    }

    // ---- Kotlin/Native interop opt-ins: configuring this block IS the opt-in ----
    nativeOptIns {
        builtIns = true                 // KiteSSOT's own marker set. false: opt in to nothing but what you add
        add("kotlinx.cinterop.ExperimentalForeignApi")
        projects(":shared")             // default: empty, which means modules.shared
    }

    // ---- browser Kotlin/JS worker helper: configuring this block IS the opt-in ----
    web {
        ioWorker {
            enabled = true               // false always wins, even over a configured block
            targets("js")                // required while enabled; KiteSSOT never guesses browser vs Node
            projects(":shared")          // default: empty, which means modules.shared
            packageName = "kitessot.generated"
        }
    }

    // ---- generated runtime constants for commonMain: configuring this block IS the opt-in ----
    buildConfig {
        enabled = true                  // false always wins, even over a configured block
        packageName = "kitessot.generated"
        className = "BuildConfig"
        includeIdentity = true          // false: fields-only object, no appName/version/appId/locales
        allowBuildCache = false         // true only when every field here is public, non-secret data

        stringField("BASE_URL", "https://api.example.com")
        stringField("CHANNEL", providers.gradleProperty("publicChannel").orElse("stable")) // provider overload; without orElse, an unset -P fails the build naming this field
        intField("API_TIMEOUT_MS", 30_000)
        longField("CACHE_BYTES", 5_000_000L)
        booleanField("ANALYTICS_ENABLED", true)
        doubleField("SAMPLE_RATE", 0.25)
    }
}
```

Five read-only providers are worth wiring into your own build logic: `versionCode`,
`androidApplicationId`, `iosBundleId`, `canonicalLocales`, and
`resolvedSharedProjectPath`. Hand any of them straight to another plugin's
`Property`, no `.get()` needed:

```kotlin
val ssot = extensions.getByType<io.github.yuroyami.kitessot.KiteSsotExtension>()
someOtherTask.someProperty.set(ssot.androidApplicationId)
```

Run `./gradlew kiteSsotVerify` after any change. It resolves the whole model
above and prints it. It writes nothing.

## Upgrading from 2.x

Your 2.x build still compiles on 3.0.

**Old root properties still work.** They warn as deprecated and name their
replacement, so the IDE can rename them for you. The common ones:

| 2.x | 3.0 |
| --- | --- |
| `versionName` | `version` |
| `bundleIdBase` | `appId` |
| `javaVersion` | `jvmTarget` |
| `sharedProjectPath` | `modules { shared }` |
| `versionCodeOverride` | `android { versionCode }` |
| `iosBundleSuffix` | `ios { bundleIdSuffix }` |

**A block is its own switch.** Writing `logo { }` or `ios { sync { } }` turns
that feature on, so drop `propagateLogo = true` and `syncIos = true`. To force a
configured feature off, set `enabled = false` inside its block.

**Derived version codes grow.** `1.4.1` gave `1001004001` and now gives
`1001004010`. Bigger is safe: Play only rejects a code that shrinks.

[CHANGELOG.md](CHANGELOG.md) lists every rename.

## Two tiers of switch

Some settings act on every build. Others only unlock a task that you run
yourself. Configuring a block therefore does not always make something happen.

**Gradle configuration is automatic and continuous.** The four `propagate { }`
switches, plus `android { applySdkLevels }`, `android { filterResourcesToLocales }`,
`buildConfig { }` and `web { ioWorker { } }`, govern the values KiteSSOT applies
on every build: Android identity and SDK levels, Java and Kotlin JVM alignment,
and Kotlin source generated into `build/`.

**Source-tree edits are opt-in and manual.** `ios { sync { } }` and `logo { }`
are authorization gates. They unlock tasks and never run them. `logo { }` is the
one that surprises people: it looks automatic, but on its own it writes nothing.

Installing the Apple app icon needs three things: a `logo { }` block, an
`ios { sync { } }` block, and `ios { deploymentTarget }`. You then still run
`./gradlew kiteSsotSyncIosLogo` yourself.

When one of the tasks they unlock does write an edit, it first passes
containment, ownership, checksum, backup and rollback checks.

## Tasks

All in the `kitessot` group. None is attached to `build`, `check` or any other
lifecycle task, so the mutating ones run only when named.

| Task | Writes | What it does |
| --- | --- | --- |
| `kiteSsotVerify` | nothing | Prints the resolved model |
| `kiteSsotDoctor` | nothing | Diagnoses the setup; never fails the build |
| `kiteSsotPlan` | nothing | Lists the mutations the current config authorizes, and their exact paths |
| `kiteSsotCheck` | `build/` | Same checks, writes a JSON or SARIF report, fails on ERROR findings |
| `generateKiteSsotBuildConfig` | `build/` | The `commonMain` constants object |
| `generateKiteSsotIoWorker<Target>` | `build/` | The browser worker helper |
| `kiteSsotSanitizeIosProject` | source | Maintains the SSOT keys in a source XML `Info.plist` |
| `kiteSsotSyncIosConfig` | source | Applies Xcode build settings, plist, Podfile and Swift plans |
| `kiteSsotSyncIosLogo` | source | Installs the Apple `AppIcon.appiconset` |
| `kiteSsotSyncAndroidLogo` | source | Installs the Android launcher icon tree |
| `kiteSsotCleanupLegacyAppLogoArtifacts` | source | Backs up, records, then removes legacy Android icon files |

There is deliberately no aggregate "sync everything" task. Each source-tree
mutation is a separate decision, with its own review, backup and recovery
boundary.

Set `dryRun = true` to make the mutating tasks report what they would write,
without writing it. Generated Kotlin under `build/` ignores `dryRun`, because it
is a build input.

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
- Nobody has yet verified a real Xcode archive, signing or App Store upload driven
  by these outputs, or live browser CSP execution of the worker.

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
