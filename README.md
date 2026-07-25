# KiteSSOT

A root-project Gradle plugin that gives a Kotlin Multiplatform repo one place to
declare app identity, and propagates it to the platform files that would
otherwise each hold their own copy.

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.yuroyami.kitessot?label=plugin%20portal)](https://plugins.gradle.org/plugin/io.github.yuroyami.kitessot)
[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/KiteSSOT/ci.yml?branch=main&label=CI)](https://github.com/yuroyami/KiteSSOT/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

## What you get

App name, version, bundle ID, locales, Android SDK levels and Java level are
written down four times in a typical KMP repo: the Android module's
`defaultConfig`, the Xcode project's build settings, the source `Info.plist`, and
whatever constant common Kotlin code reads. They drift.

KiteSSOT declares them once in the root build and splits the work in two. Gradle
configuration is applied automatically on every build, inside AGP's `finalizeDsl`
hook, which runs after a module's own `android { }` block, so a value set in
`kiteSsot { }` wins over the same value set locally. Anything that edits a file
you own — `project.pbxproj`, `Info.plist`, `Podfile`, Swift imports, launcher
icons — never happens during a build; it takes an opt-in flag and an explicitly
named task. A plain `./gradlew build` writes nothing outside `build/`, and CI
asserts that on every commit.

## A working setup

```kotlin
// Root build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    id("com.android.application") version "9.2.1" apply false
    id("io.github.yuroyami.kitessot") version "2.0.2"
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

Run `./gradlew kiteSsotVerify` first; it prints the resolved model and changes
nothing. The Android application module then receives the application ID, the
`versionName`, a version code derived from `1.4.0`, and an `appName` manifest
placeholder for `android:label="${appName}"`. Every Android module receives the
SDK levels.

## Install

Published on the Gradle Plugin Portal as `io.github.yuroyami.kitessot`, current
version 2.0.2. Two preconditions:

**Apply it to the root project.** Applying it in a submodule throws immediately;
the plugin aggregates across `allprojects` from the root.

**Declare the Kotlin and Android plugins at the root with `apply false`.** Those
lines are load-bearing. KiteSSOT's KGP- and AGP-typed integrations are guarded on
those plugin classes being loadable from KiteSSOT's own classloader, and
declaring `kotlin("multiplatform")` only inside a subproject's `plugins { }`
block puts KGP in a sibling classloader KiteSSOT cannot see, so the affected
features cannot run. The plugin fails with that explanation rather than skipping
them quietly.

## Two tiers of "turn this on"

Setting a switch to `true` does not, on its own, mean something happens.

**Gradle configuration is automatic and continuous.** The `propagate*` Booleans,
`filterAndroidResources`, `buildConfig { enabled }` and `web { generateIoWorker }`
govern values applied on every build: Android identity and SDK levels, Java and
Kotlin JVM alignment, and Kotlin source generated into `build/`.

**Source-tree edits are opt-in and manual.** `syncIos`, `sanitizeIosProject`,
`propagateLogo` and `cleanupLegacyLogoArtifacts` are authorization gates. They
unlock tasks, never run them, and they combine with the switches above rather
than replacing them. Installing the Apple app icon needs `propagateLogo = true`
and `syncIos = true` and `ios { deploymentTarget }` set, and then you still run
`./gradlew kiteSsotSyncIosLogo` yourself; `propagateLogo = true` on its own
changes no files. The `propagate` prefix promises automatic behavior that these
four switches deliberately do not deliver. When one of the tasks they unlock does
land an edit, it first passes containment, ownership, checksum, backup and
rollback checks.

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

There is deliberately no aggregate "sync everything" task; each source-tree
mutation is a separate decision with its own review, backup and recovery
boundary. Set `dryRun = true` to have the mutating tasks report what they would
write without writing it. Generated Kotlin under `build/` ignores `dryRun`,
because it is a build input.

## The DSL

`kiteSsot { }` holds scalars and typed paths — `appName`, `versionName`,
`bundleIdBase`, `versionCodeOverride`, `javaVersion`, `locales`,
`sharedProjectPath`, `androidApplicationProjects`, `iosPbxprojFile`,
`appLogoPngForeground`, `appLogoBackgroundColor` and their siblings — plus
fourteen Boolean switches (the eight `propagate*` values,
`filterAndroidResources`, `syncIos`, `sanitizeIosProject`,
`cleanupLegacyLogoArtifacts`, `backupBeforeRewrite`, `dryRun`) and four nested
blocks, sketched here rather than spelled out:

```
android     { compileSdk, minSdk, targetSdk, ndkVersion, publishedVersionCode }
ios         { deploymentTarget, targetNames, plistConflictPolicy, … }
web         { generateIoWorker, browserTargetNames, projectPaths, ioWorkerPackage }
buildConfig { enabled, packageName, className, stringField(), intField(), … }
```

Five read-only derived providers (`versionCode`, `androidApplicationId`,
`iosBundleId`, `canonicalLocales`, `resolvedSharedProjectPath`) can be passed
straight into another plugin's `Property` rather than calling `.get()`.

Every property carries KDoc saying whether it is optional, what its default is,
and which other values it needs; the IDE shows that on autocomplete, and the
published javadoc jar carries it as Dokka HTML. [FEATURES.md](FEATURES.md) is the
prose reference for behavior and safety rules.

### Deprecated 1.x properties

Still in the DSL and the ABI, so autocomplete offers them, and the 2.x typed
defaults are derived from them — a stale one still affects resolved paths.

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
| Android Gradle plugin | 8.5.2 through 9.2.x |
| Kotlin Gradle plugin | 2.4.x |
| JDK running Gradle | 17 and 21 |

Built with the JDK 21 toolchain and emitting Java 17 bytecode, so a JDK 17
consumer daemon can load it. Configuration cache is supported.

239 tests across 23 files: 233 in `./gradlew test`, and 6 in
`./gradlew agpCompatibilityTest`, which starts real consumer builds on Gradle
8.5, 8.9 and 9.5.1 against AGP 8.5.2 and 9.2.1 and KGP 2.4.0. CI builds on
ubuntu-24.04 with JDK 17 and JDK 21, macos-15 with JDK 21, and windows-2025 with
JDK 21, each twice, asserting the second run reuses the configuration cache entry
and that the tracked working tree is unchanged afterwards.

## Limits

- No Gradle Isolated Projects support. The plugin aggregates from the root across
  `allprojects`, which that mode forbids.
- One app identity per build: no per-flavor, per-build-type or per-Xcode-target
  overlays.
- `project.pbxproj`, `Info.plist`, `Podfile` and Swift files are edited as text,
  not through xcconfig generation or a syntax tree. Binary and generated plists
  are rejected rather than converted.
- The 1.x properties above are kept deliberately, so the DSL has two live ways to
  say several things.
- The browser worker is Kotlin/JS browser only, not Node and not wasm. It runs
  caller-supplied JavaScript text, so that text must never come from user input,
  and the deployed CSP has to allow blob workers, normally via `worker-src blob:`.
- `buildConfig` is not a secret store. Every value reaches generated source, task
  inputs, build scans and shipped binaries.
- Nobody has yet verified a real Xcode archive, signing or App Store upload driven
  by these outputs, or live browser CSP execution of the worker.

## Releasing

The published version comes only from `-PkiteSsot.version`, defaulting to
`0.0.0-SNAPSHOT`. To cut a release, add a `## [x.y.z]` heading to
[CHANGELOG.md](CHANGELOG.md), commit, tag `vx.y.z`, and push the tag. The
`verifyReleaseMetadata` guard rejects a non-semver version, rejects anything
ending in SNAPSHOT, requires the tag to equal `v<version>`, and requires the
matching changelog heading. [CONTRIBUTING.md](CONTRIBUTING.md) has the
development workflow; security reports go through [SECURITY.md](SECURITY.md).

## License

Apache-2.0. See [LICENSE](LICENSE) and [CHANGELOG.md](CHANGELOG.md).

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[KiteImage](https://github.com/yuroyami/KiteImage),
[KiteQR](https://github.com/yuroyami/KiteQR).
