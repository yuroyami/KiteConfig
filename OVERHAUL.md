# KiteSSOT 3.0: The DSL Overhaul

> Superseded: the DSL described below was replaced by the topic reshape.
> See specs/2026-08-28-dsl-reshape-design.md for the current surface.

Status: **implemented in 3.0.0**. Kept as the design record.

The shipped DSL matches this document. See CHANGELOG.md for the release notes and
the exact list of deprecations and removals.

## TL;DR

The 2.x DSL works, but it grew flat. 40 root properties, 13 toggles with a
`propagate` prefix that means three different things, and iOS settings split
across two places. 3.0 reshapes the same engine into a DSL where:

* A new user needs **3 lines** to start.
* Everything lives with its family. Nothing platform-specific sits at root.
* Configuring a feature block **is** the opt-in. No more double switches.
* Every knob stays a lazy Gradle `Property`. Nothing loses wireability.
* CI can flip safety knobs from the command line without editing the build.

The smallest possible setup:

```kotlin
kiteSsot {
    appName = "Jetzy"
    version = "1.4.0"
    appId   = "com.example.jetzy"
}
```

That is a complete, working configuration. Locales auto-detect. The shared
module auto-detects. The Android app project auto-detects. Everything else is
optional.

## What hurts in 2.x

| # | Pain | Example |
|---|------|---------|
| 1 | iOS config is split across root and `ios { }` | `iosBundleSuffix`, `iosBuildNumber`, 5 path properties and `syncIos` live at root, while `deploymentTarget` and plist flags live in `ios { }` |
| 2 | 13 flat toggles, one prefix, three meanings | `propagateVersion` applies config. `propagateLogo` authorizes tasks. `propagateSharedModule` gates a migration. Same prefix, different semantics |
| 3 | Double opt-ins | Logo needs `propagateLogo = true` AND the files AND an explicit task run. The flag adds a step without adding safety |
| 4 | Root is crowded | 40 non-deprecated root properties. Autocomplete is a wall |
| 5 | 8 deprecated legacy properties still visible | `sharedModule`, `iosProjectPath`, `androidAppModule`... noise in every IDE popup |
| 6 | Four different ways to point at projects | `sharedProjectPath`, `androidApplicationProjects`, `interopProjectPaths`, `web.projectPaths` |

## Three rules that shape 3.0

**Rule 1: Truth at root.** What the app IS (name, version, id, locales) is
cross-platform truth. It stays at the top level, unprefixed, three lines from
"hello". This is the SSOT charter itself: declare once, platforms derive.

**Rule 2: Platform blocks own deviation and policy.** Everything that is
Android-only or Apple-only lives inside `android { }` or `ios { }`: suffixes,
build numbers, SDK levels, file paths, sync behavior, conflict policies. If a
property name needs an `ios` or `android` prefix, it is in the wrong place.

**Rule 3: Presence means intent. Invocation means action.** Configuring
`logo { }` authorizes the logo tasks. Configuring `ios { sync { } }`
authorizes the iOS sync tasks. You still have to RUN those tasks yourself,
and `dryRun`, backups, and conflict policies still apply. The charter does not
change: **no ordinary build ever mutates source**. What dies is the redundant
boolean between "I configured it" and "I may run it".

## The full 3.0 shape

Every property shown with its default. Everything is optional except the
identity trinity when a feature needs it.

```kotlin
kiteSsot {

    // ------------------------------------------------------------ THE TRUTH
    appName = "Jetzy"                 // display name, all platforms
    version = "1.4.0"                 // versionName / marketing version
    appId   = "com.example.jetzy"     // reverse-DNS base for both platforms

    locales = listOf("en", "pt-BR")   // default: auto-detected from compose resources
    jvmTarget = 21                    // was javaVersion; Java compat + Kotlin JVM target

    scheme { v -> ... }               // ONE build-number formula for BOTH platforms
                                      // (v.major, v.minor, v.patch, v.rebuild) -> Int
                                      // default: 1|major(3)|minor(3)|patch(2)|rebuild(1)
                                      // 1.4.0 -> 1001004000, 1.4.1 -> 1001004010

    // ----------------------------------------------------------- SAFETY
    dryRun  = false                   // mirror: -Pkitessot.dryRun=true
    backups = true                    // was backupBeforeRewrite; mirror: -Pkitessot.backups=false

    // ----------------------------------------------------------- STRUCTURE
    modules {
        shared = ":shared"            // default: auto-detected when exactly one KMP project
        androidApps(":androidApp")    // default: auto-detected sole application project
        androidAppDirectory = ...     // rarely needed; auto-found from the selected app
        composeResources = ...        // locale discovery source; default: shared's commonMain
    }

    // ----------------------------------------------------------- APPLY GATES
    propagate {                       // all default true; the "hands off" switchboard
        appName  = true
        bundleId = true
        version  = true
        locales  = true
    }

    // ----------------------------------------------------------- ANDROID
    android {
        idSuffix = ""                 // was androidApplicationIdSuffix; appId + suffix = applicationId

        rebuild = 1                   // NEW: Play ate my AAB. 1001004000 -> 1001004001
        versionCode = 140             // was root versionCodeOverride
                                      // default: the root scheme. Assign to bypass it
        // scheme { v -> ... }        // rare: different formula for Android only

        compileSdk = 36
        minSdk     = 26
        targetSdk  = 36               // applications only
        ndk        = "27.0.12077973"  // was ndkVersion; classic modules only

        publishedVersionCode = 139    // offline monotonicity guard, never written anywhere

        applySdkLevels = true         // was propagateAndroidSdk
        filterResourcesToLocales = false  // was filterAndroidResources; changes packaged output

        // read-only derived:
        // applicationId : Provider<String>
    }

    // ----------------------------------------------------------- IOS
    ios {
        bundleIdSuffix   = ".iosApp"  // appId + suffix = bundle id
        marketingVersion = "1.4.0"    // default: version
        rebuild = 3                   // NEW: TestFlight burned it. -> "1001004003"
        buildNumber      = "42"       // CURRENT_PROJECT_VERSION
                                      // default: the root scheme. Assign to bypass it
        // scheme { v -> ... }        // rare: different formula for iOS only
        publishedBuildNumber = "41"   // NEW, optional: TestFlight monotonicity guard
        deploymentTarget = "14.0"     // asset compatibility check only

        // paths, defaults shown; only set them when your tree differs
        pbxproj          = file("iosApp/iosApp.xcodeproj/project.pbxproj")
        podfile          = file("iosApp/Podfile")
        infoPlist        = file("iosApp/iosApp/Info.plist")
        appDirectory     = dir("iosApp")
        appIconDirectory = dir("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")

        sync {                        // PRESENCE authorizes the explicit Apple tasks (was syncIos = true)
            targets("iosApp")         // was targetNames; empty may select a sole app target
            sanitizePlist = false     // was sanitizeIosProject
            onConflict = Fail         // was plistConflictPolicy; also Keep, Replace

            nonExemptEncryption = false   // was usesNonExemptEncryption; unset leaves the key alone
            proMotion = true              // was proMotion120Hz; unset leaves the key alone

            renameSharedModule(from = "OldShared", to = "Shared")
            // one call replaces: propagateSharedModule + iosSharedModuleName
            //                    + iosPreviousSharedModuleName
        }

        // read-only derived:
        // bundleId : Provider<String>
    }

    // ----------------------------------------------------------- LOGO
    logo {                            // PRESENCE authorizes the logo tasks (was propagateLogo = true)
        foreground = file("art/logo_fg.png")
        background = file("art/logo_bg.png")   // XOR with backgroundColor
        backgroundColor = "#102A43"            // XOR with background

        androidSafeZone = 0.61        // was appLogoAndroidSafeZoneRatio; default 66.0 / 108.0
        takeOverLegacyIcons = false   // was cleanupLegacyLogoArtifacts; backups still made
    }

    // ----------------------------------------------------------- NATIVE OPT-INS
    nativeOptIns {                    // PRESENCE enables (was propagateInteropOptIns = true)
        builtIns = true               // KiteSSOT's own marker set
        add("kotlinx.cinterop.ExperimentalForeignApi")   // was extraOptIns
        projects(":shared")           // was interopProjectPaths; default: modules.shared
    }

    // ----------------------------------------------------------- WEB
    web {
        ioWorker {                    // PRESENCE enables (was generateIoWorker = true)
            targets("js")             // was browserTargetNames; still required, still no guessing
            projects(":shared")       // was projectPaths; default: modules.shared
            packageName = "kitessot.generated"   // was ioWorkerPackage
        }
    }

    // ----------------------------------------------------------- BUILDCONFIG
    buildConfig {                     // PRESENCE enables (was enabled = true)
        packageName = "kitessot.generated"
        className   = "BuildConfig"
        includeIdentity = true
        allowBuildCache = false

        stringField("BASE_URL", "https://api.acme.com")
        stringField("CHANNEL", providers.gradleProperty("publicChannel"))
        intField("API_TIMEOUT_MS", 30_000)
        longField("CACHE_BYTES", 5_000_000L)
        booleanField("ANALYTICS_ENABLED", true)
        doubleField("SAMPLE_RATE", 0.25)
    }
}
```

Root scalar count: 2.x has 40. This shape has 7.

## Version and build numbers

### One formula, at root

`scheme` turns a version into a build number. It lives at root. Both platforms
use it. You write it once.

```kotlin
kiteSsot {
    version = "1.4.0"

    scheme { v -> ... }    // optional. Default shown below.
}
```

The lambda gets four Ints and returns one Int:

```
v.major  v.minor  v.patch  v.rebuild   ->   Int
```

Android takes that Int as `versionCode`. iOS takes the same Int, as a string,
for `CURRENT_PROJECT_VERSION`. Same number, two field types. Nothing else
differs.

### The default

```
1 | major(3) | minor(3) | patch(2) | rebuild(1)

1.4.0            -> 1001004000
1.4.0 rebuild 1  -> 1001004001
1.4.0 rebuild 9  -> 1001004009
1.4.1            -> 1001004010
1.5.0            -> 1001005000
```

Ten codes per version. Ceiling 1999999999, under Play's 2100000000 cap.

### `rebuild`: the store-ate-my-upload dial

Play keeps every uploaded versionCode forever. TestFlight refuses a reused
build number. Same problem, one word:

```kotlin
android { rebuild = 1 }    // versionCode  1001004000 -> 1001004001
ios     { rebuild = 3 }    // buildNumber  "1001004000" -> "1001004003"
```

Two dials, because Play and TestFlight burn numbers on different days. Wire
both to one Gradle property if yours move together.

Never needs resetting: a version bump changes the higher digits, so
`1.4.1 rebuild 0` (1001004010) always beats `1.4.0 rebuild 9` (1001004009).

### Custom schemes

Need more than 10 rebuilds per version? Move a digit:

```kotlin
scheme { v -> "1${"$"}{v.major.pad(2)}${"$"}{v.minor.pad(3)}${"$"}{v.patch.pad(2)}${"$"}{v.rebuild.pad(2)}".toInt() }
// 1.4.0 -> 1010040000, 1.4.0 rebuild 42 -> 1010040042
// 100 rebuilds per version, major capped at 99, ceiling still 1999999999
```

Already have a scheme from before KiteSSOT? Bring it:

```kotlin
scheme { v -> 1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.rebuild }
```

Or skip the formula entirely and hand over a value:

```kotlin
android { versionCode = providers.environmentVariable("CI_RUN").map(String::toInt) }
ios     { buildNumber = providers.gradleProperty("tf") }
```

### Per-platform scheme override

Only if the two platforms genuinely need different formulas. Rare, but there:

```kotlin
android { scheme { v -> ... } }    // overrides the root scheme for Android only
ios     { scheme { v -> ... } }    // returns String here
```

### Precedence, per platform

| gear | you write | result |
|---|---|---|
| 0 | nothing | root scheme, default formula |
| 1 | `rebuild = N` | root scheme, rebuild fed in |
| 2 | root `scheme { }` | your formula, both platforms |
| 3 | platform `scheme { }` | your formula, that platform only |
| 4 | `versionCode` / `buildNumber` = provider | your value verbatim |
| 5 | `propagate { version = false }` | KiteSSOT writes nothing |

`kiteSsotDoctor` prints which gear is active and the resolved number.

### Store limits

| store | field | limit |
|---|---|---|
| Play | `versionCode`, Int | 2100000000, documented hard cap |
| App Store / TestFlight | `CFBundleVersion`, String | 1 to 3 numeric dot components, no practical ceiling |

Ten digits is what the default formula happens to produce. Not a requirement.
A scheme returning `140` is equally valid.

### The rule no scheme escapes

Play compares codes as integers and remembers every upload. **You can never go
smaller.** If your history contains 1001004001, every future code must exceed
it. Moving into KiteSSOT from any scheme is fine when the new number is
higher. Moving from big numbers down to small ones is impossible on Play, with
or without this plugin. `android.publishedVersionCode` turns that into a build
error instead of a console surprise.

Apple only requires the next build number to beat the previous one for the
same marketing version, so scheme changes are cheap there.

### Guards

* `android.publishedVersionCode` (from 2.x): next code must exceed it.
* `ios.publishedBuildNumber` (new): same, componentwise.

Both offline. Neither is ever written into a DSL.

### Changes from 2.x

1. **iOS follows by default.** 2.x only wrote `CURRENT_PROJECT_VERSION` when
   you set `iosBuildNumber`. 3.0 writes the scheme result, still only inside
   explicitly invoked, dry-runnable, backed-up sync tasks.
2. **Derived codes shift.** The old formula gave patch three digits and left no
   room for rebuilds; `1.4.1` moves from 1001004001 to 1001004010. Higher, so
   Play monotonicity survives. Patch now caps at 99: repos above that bring
   their old formula as a `scheme { }`, and the error message prints it.

## Old to new: the complete map

Every 2.x property, including the deprecated ones, and where it goes.

### Identity and versions

| 2.x | 3.0 |
|-----|-----|
| `appName` | `appName` (unchanged) |
| `versionName` | `version` |
| `bundleIdBase` | `appId` |
| `versionCodeOverride` | `android.versionCode` (one property; convention = derived value; setting it is the override) |
| `versionCode` (derived provider) | `android.versionCode` is readable as a `Provider<Int>` |
| `iosMarketingVersion` | `ios.marketingVersion` |
| `iosBuildNumber` | `ios.buildNumber` (BEHAVIOR CHANGE: now follows the root `scheme` by default; see "Version and build numbers") |
| `iosBundleSuffix` | `ios.bundleIdSuffix` |
| `androidApplicationIdSuffix` | `android.idSuffix` |
| `javaVersion` | `jvmTarget` |
| `locales` | `locales` (unchanged) |

### Structure

| 2.x | 3.0 |
|-----|-----|
| `sharedProjectPath` | `modules.shared` |
| `sharedModule` (deprecated) | `modules.shared` |
| `androidApplicationProjects` | `modules.androidApps(...)` |
| `androidAppModule` (deprecated) | `modules.androidApps(...)` |
| `androidAppDirectory` | `modules.androidAppDirectory` |
| `composeResourcesDirectory` | `modules.composeResources` |
| `iosSharedModuleName` | `ios.sync.renameSharedModule(to = ...)` |
| `iosPreviousSharedModuleName` | `ios.sync.renameSharedModule(from = ...)` |
| `oldSharedModuleName` (deprecated) | `ios.sync.renameSharedModule(from = ...)` |

### iOS paths

| 2.x | 3.0 |
|-----|-----|
| `iosPbxprojFile` / `iosProjectPath` (deprecated) | `ios.pbxproj` |
| `iosPodfileFile` / `iosPodfilePath` (deprecated) | `ios.podfile` |
| `iosInfoPlistFile` / `iosInfoPlistPath` (deprecated) | `ios.infoPlist` |
| `iosAppDirectory` / `iosAppDir` (deprecated) | `ios.appDirectory` |
| `iosAppIconDirectory` / `iosAppiconsetPath` (deprecated) | `ios.appIconDirectory` |

### Logo

| 2.x | 3.0 |
|-----|-----|
| `appLogoPngForeground` | `logo.foreground` |
| `appLogoPngBackground` | `logo.background` |
| `appLogoBackgroundColor` | `logo.backgroundColor` |
| `appLogoAndroidSafeZoneRatio` | `logo.androidSafeZone` |

### Nested blocks

| 2.x | 3.0 |
|-----|-----|
| `android.compileSdk` / `minSdk` / `targetSdk` | unchanged, same block |
| `android.ndkVersion` | `android.ndk` |
| `android.publishedVersionCode` | unchanged |
| `ios.deploymentTarget` | unchanged |
| `ios.targetNames` | `ios.sync.targets(...)` |
| `ios.plistConflictPolicy` | `ios.sync.onConflict` |
| `ios.usesNonExemptEncryption` | `ios.sync.nonExemptEncryption` |
| `ios.proMotion120Hz` | `ios.sync.proMotion` |
| `web.generateIoWorker` | `web.ioWorker { }` presence |
| `web.browserTargetNames` | `web.ioWorker.targets(...)` |
| `web.projectPaths` | `web.ioWorker.projects(...)` |
| `web.ioWorkerPackage` | `web.ioWorker.packageName` |
| `buildConfig.*` | unchanged, except `enabled` (see toggles) |

## The 13 toggles: where each one went

| 2.x toggle | Meaning | 3.0 fate |
|------------|---------|----------|
| `propagateAppName` | apply gate | `propagate.appName` |
| `propagateBundleId` | apply gate | `propagate.bundleId` |
| `propagateVersion` | apply gate | `propagate.version` |
| `propagateLocaleList` | apply gate | `propagate.locales` |
| `propagateAndroidSdk` | apply gate | `android.applySdkLevels` |
| `filterAndroidResources` | output changer | `android.filterResourcesToLocales` |
| `propagateLogo` | task authorizer | dies; `logo { }` presence |
| `syncIos` | task authorizer | dies; `ios.sync { }` presence |
| `sanitizeIosProject` | task authorizer | `ios.sync.sanitizePlist` |
| `propagateSharedModule` | task authorizer | dies; `renameSharedModule(...)` presence |
| `propagateInteropOptIns` | feature enable | dies; `nativeOptIns { }` presence |
| `cleanupLegacyLogoArtifacts` | risk expander | `logo.takeOverLegacyIcons` |
| `dryRun` / `backupBeforeRewrite` | safety | root `dryRun` / `backups` |

Presence blocks keep an explicit `enabled` escape: every presence-enabled
block still has `enabled: Property<Boolean>` whose convention flips to `true`
when the block function runs. `enabled = false` always wins. So CI or a
convention plugin can force a feature off without deleting configuration.

## Behavior upgrades (beyond renames)

### 1. Auto-detection with fail-closed ambiguity

* `modules.shared` defaults to the one project applying Kotlin Multiplatform.
  Two or more KMP projects: hard error naming the candidates, asking for an
  explicit `modules.shared`. Zero: only features that need it fail.
* `modules.androidApps` already auto-detects a sole application in 2.x. That
  stays and moves into the block.

Standard template repos configure zero structure. Unusual repos state theirs.
Nobody gets a silent wrong guess.

### 2. Conventions over override-pairs

`android.versionCode` shows the pattern: in 2.x there is a derived provider
(`versionCode`) plus a separate `versionCodeOverride` input, two names for one
idea. In 3.0 there is one property whose convention is the derived value. Set
it, and that is the override. Same pattern for `ios.marketingVersion`
(convention: `version`) and both platform counters (convention: the root
`scheme`; see "Version and build numbers").

### 3. Command-line mirrors for safety knobs

| Gradle property | Effect |
|-----------------|--------|
| `-Pkitessot.dryRun=true` | force preview mode for any explicit mutation task |
| `-Pkitessot.backups=false` | disable recovery copies (not recommended) |
| `-Pkitessot.compat.allowUntestedAgp=true` | see next section |

DSL wins over mirror when both are set? No: the mirror wins, because its use
case is CI overriding a checked-in build. Documented, deterministic.

### 4. The AGP cap gets an escape hatch

Today the supported AGP range is a hard gate: outside it, ordinary Android
builds fail with `KITESSOT-COMPAT-002`, and there is no override. That cap
saved users from real breakage (AGP 9.2 changed task-registration timing and
broke real KMP modules, fixed in 2.0.2), so it stays the default.

What 3.0 adds:

```properties
# gradle.properties
kitessot.compat.allowUntestedAgp=true
```

* It must be a **Gradle property, not a DSL property**. Adapter selection runs
  at plugin apply time, before `kiteSsot { }` is evaluated. A DSL flag would
  silence the error while the adapters were already skipped: a silent no-op,
  the worst outcome.
* With the property set, KiteSSOT applies its newest adapter line and prints
  one loud, unmissable warning per build: active AGP, tested ceiling, and the
  fact that support is void above it.
* `kiteSsotDoctor` reports tested range vs active version vs override state.
* The alternative (hard-cap majors only, warn on untested minors) was
  considered and rejected: the 9.2 incident proves AGP minors break real
  builds. Opt-in risk beats default risk.

### 5. Reserved growth axis

Platform blocks are the extension points. `desktop { }` (Compose Desktop
packaging identity) is the obvious next tenant. The overhaul reserves the
naming pattern; it adds no desktop features.

## What does NOT change

* Task names and behavior: `kiteSsotDoctor`, `kiteSsotCheck`, `kiteSsotVerify`,
  `kiteSsotPlan`, the sync and logo tasks. All identical.
* The charter: ordinary builds never mutate source. Mutation tasks are
  explicit-only, fail-closed, backed up, dry-runnable.
* Diagnostics IDs (`KMPS0xx`) and report formats.
* Everything stays `Property` / `Provider` based. Lazy wiring in, lazy wiring
  out. Both Kotlin and Groovy DSLs are first-class (no Kotlin-only constructs
  in the public API).
* The engine. This overhaul is a surface reshape; wiring, safety, and
  diagnostics stay put.

## Migration plan

Ship in 3.0.0:

1. **New DSL** as described.
2. **Bridge properties**: every 2.x property that maps 1:1 stays as a hidden,
   deprecated delegate to its new home (`versionName` writes `version`, and so
   on). Existing builds keep working with deprecation nags. The 8 already
   deprecated 1.x-era properties are removed outright; they have had a full
   major of warnings.
3. **`kiteSsotDoctor` prints your migrated block**: it reads the resolved 2.x
   model and emits the equivalent 3.0 `kiteSsot { }` snippet, copy-paste
   ready. Zero-thought migration for the common case.
4. Bridges are removed in 4.0.0, or 3.1.0 at the earliest if telemetry-free
   judgment says adoption is done.

## On the "nest same-family stuff" suggestion

The instinct is right, and it is the backbone of this proposal. One
refinement keeps it from backfiring:

**Nest the platform-SPECIFIC stuff. Never nest the truth.**

If `appName` or `version` moved into `android { }` and `ios { }`, the DSL
would invite declaring them twice, and the two would drift. That drift is the
exact disease KiteSSOT exists to cure. So the rule: shared facts live once at
root; platform blocks hold only what genuinely differs per platform
(suffixes, build numbers, SDK levels, file paths, sync policy). The eye-candy
survives, and the single source of truth stays single.

## Open questions

1. `version` and `appName` shadow `Project.version` / `Project.name` inside
   the block. Receiver scope wins, so it works in both DSLs, but copy-pasting
   `version = "1.4.0"` OUTSIDE the block silently sets the project version.
   Fallback if this bites in practice: keep `versionName`.
2. `propagate { }` at root vs folding those four gates into platform blocks
   as `applyIdentity` and friends. Root block chosen because the gates are
   cross-platform. Weak preference; either is defensible.
3. Should `buildConfig { }` presence-enable like the other feature blocks?
   Proposed yes for consistency, with `enabled = false` as the standing
   override. The counterargument (accidentally enabling by configuring
   early) is judged rare.
4. `renameSharedModule(from, to)` is a one-shot call, not two properties.
   Lazy wiring of the pair needs a small value object. Cosmetic cost, real
   elegance gain.
5. `ios.publishedBuildNumber` needs a comparison rule for multi-component
   values, where `"2.1"` must outrank `"2"`. Component-wise numeric compare
   with missing parts read as 0 is proposed (the same rule the suffix ordering
   table relies on). Low risk, needs its own tests.
6. The follow-by-default behavior change on `ios.buildNumber`: is a loud
   `kiteSsotDoctor` callout plus the migration bridge enough, or should 3.0
   also require one explicit ack (a property or the presence of `sync { }`)
   before the first followed write into an upgraded project? Leaning "doctor
   plus bridge is enough", since the write already needs an explicitly
   invoked task, but flagging it for review.
7. The ordinal re-layout drops the patch ceiling from 999 to 99. Repos whose
   release history already contains a patch above 99 cannot represent those
   versions in the new scheme. With `scheme { }` in the design, the answer is
   now "bring the legacy formula as a one-line scheme"; the error message
   should print exactly that snippet.
8. `scheme { }` takes a user lambda into a `Provider` chain. Needs explicit
   verification under the configuration cache (lambda serialization, no
   `Project` capture) and a Groovy closure overload. Also decide whether a
   scheme returning a value that shrinks between builds should fail eagerly
   (`publishedVersionCode` already catches the shipped-code case).
