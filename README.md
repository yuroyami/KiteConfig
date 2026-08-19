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
Each one needs an opt-in flag and an explicitly named task. A plain
`./gradlew build` writes nothing outside `build/`, and CI asserts that on every
commit.

## A working setup

```kotlin
// Root build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    id("com.android.application") version "9.3.1" apply false
    id("io.github.yuroyami.kitessot") version "2.0.3"
}

kiteSsot {
    appName = "Jetzy"
    versionName = "1.4.0"
    bundleIdBase = "com.example.jetzy"

    android {
        compileSdk = 36
        minSdk = 26
        targetSdk = 36
    }
}
```

Run `./gradlew kiteSsotVerify` first. It prints the resolved model and changes
nothing.

The Android application module then receives four values:

- the application ID
- the `versionName`
- a version code derived from `1.4.0`
- an `appName` manifest placeholder, for `android:label="${appName}"`

Every Android module receives the SDK levels.

## Install

Published on the Gradle Plugin Portal as `io.github.yuroyami.kitessot`, current
version 2.0.3. Two preconditions:

**Apply it to the root project.** Applying it in a submodule throws immediately;
the plugin aggregates across `allprojects` from the root.

**Declare the Kotlin and Android plugins at the root with `apply false`.** You
must keep those two lines. KiteSSOT integrates with typed classes from KGP, the
Kotlin Gradle plugin, and from AGP. Those integrations run only when KiteSSOT can
load the plugin classes from its own classloader.

Declare `kotlin("multiplatform")` only inside a subproject's `plugins { }` block
and Gradle loads KGP with a different classloader. KiteSSOT cannot read the
plugin classes from there, so the affected features cannot run. The plugin fails
with that explanation, rather than skipping them quietly.

## Two tiers of switch

Some switches act on every build. Others only unlock a task that you run
yourself. Setting one to `true` therefore does not always make something happen.

**Gradle configuration is automatic and continuous.** Seven of the eight
`propagate*` Booleans, plus `filterAndroidResources`, `buildConfig { enabled }`
and `web { generateIoWorker }`, govern the values KiteSSOT applies on every
build: Android identity and SDK levels, Java and Kotlin JVM alignment, and Kotlin
source generated into `build/`.

**Source-tree edits are opt-in and manual.** `syncIos`, `sanitizeIosProject`,
`propagateLogo` and `cleanupLegacyLogoArtifacts` are authorization gates. They
unlock tasks and never run them. `propagateLogo` is the eighth `propagate*`
Boolean, and it is the exception to the paragraph above: its name suggests
automatic behavior, but it only unlocks a task.

Installing the Apple app icon needs three settings: `propagateLogo = true`,
`syncIos = true`, and `ios { deploymentTarget }`. You then still run
`./gradlew kiteSsotSyncIosLogo` yourself. `propagateLogo = true` alone changes no
files.

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

## The DSL

`kiteSsot { }` holds three kinds of thing.

Scalars and typed paths: `appName`, `versionName`, `bundleIdBase`,
`versionCodeOverride`, `javaVersion`, `locales`, `sharedProjectPath`,
`androidApplicationProjects`, `iosPbxprojFile`, `appLogoPngForeground`,
`appLogoBackgroundColor` and their siblings.

Fourteen Boolean switches: the eight `propagate*` values, plus
`filterAndroidResources`, `syncIos`, `sanitizeIosProject`,
`cleanupLegacyLogoArtifacts`, `backupBeforeRewrite` and `dryRun`.

Four nested blocks, summarized here rather than listed in full:

```
android     { compileSdk, minSdk, targetSdk, ndkVersion, publishedVersionCode }
ios         { deploymentTarget, targetNames, plistConflictPolicy, … }
web         { generateIoWorker, browserTargetNames, projectPaths, ioWorkerPackage }
buildConfig { enabled, packageName, className, stringField(), intField(), … }
```

You can pass five read-only derived providers (`versionCode`,
`androidApplicationId`, `iosBundleId`, `canonicalLocales` and
`resolvedSharedProjectPath`) directly into another plugin's `Property`. You do
not need to call `.get()`.

Every property carries KDoc. The KDoc says whether the property is optional, what
its default is, and which other values it needs. The IDE shows that on
autocomplete, and the published javadoc jar carries it as Dokka HTML.
[FEATURES.md](FEATURES.md) is the prose reference for behavior and safety rules.

### Deprecated 1.x properties

These are still in the DSL and in the committed ABI dump, so autocomplete still
offers them. KiteSSOT derives the 2.x typed defaults from them. An old value
therefore still changes the resolved paths.

| Deprecated | Replacement |
| --- | --- |
| `sharedModule` | `sharedProjectPath`, `composeResourcesDirectory`, `iosSharedModuleName` |
| `oldSharedModuleName` | `iosPreviousSharedModuleName` |
| `androidAppModule` | `androidApplicationProjects` or `androidAppDirectory` |
| `iosProjectPath` | `iosPbxprojFile` |
| `iosPodfilePath` | `iosPodfileFile` |
| `iosInfoPlistPath` | `iosInfoPlistFile` |
| `iosAppDir` | `iosAppDirectory` |
| `iosAppiconsetPath` | `iosAppIconDirectory` |
| `buildConfig { fields }` | `stringField`, `intField`, `longField`, `booleanField`, `doubleField` |

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

239 tests across 23 files. 233 of them run in `./gradlew test`. The other 6 run
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
- The 1.x properties above are still present, so two properties can set the same
  value.
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
