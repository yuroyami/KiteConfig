# KiteSSOT feature reference

This page describes current KiteSSOT behavior. It is not a roadmap. Start with
the [README](README.md) if you are setting up the plugin for the first time.

KiteSSOT is applied to the root Gradle project. You declare the values and
features you need in one `kiteSsot {}` block. Most unset scalar values leave the
matching platform setting alone. Some values have a convention instead:
`ios.marketingVersion` follows `version`, build numbers follow the root
`scheme`, and locales can be discovered. Enabled features may require related
inputs. The model is frozen after root-project evaluation.

Feature blocks are their own switch. Configuring `logo {}`, `ios { sync {} }`,
`nativeOptIns {}`, `web { ioWorker {} }`, `buildConfig {}`, or `desktop {}`
turns that feature on. Leaving the block out keeps it off. You still have to
run the explicit tasks yourself for anything that changes source files.

The deprecated `Project.kiteSsot` accessor remains for source compatibility.
It reaches across projects, exposes the mutable root model, and does not support
Gradle Isolated Projects. New module build logic should use local platform
configuration or generated values instead.

## What KiteSSOT can manage

| Area | What KiteSSOT can do |
|---|---|
| App identity | Share an app name, release version, Android application ID, Apple bundle ID, and locale list |
| Android settings | Apply SDK levels, an NDK version, Java compatibility, Kotlin JVM compatibility, app identity, and optional locale filters |
| Runtime configuration | Generate a typed Kotlin object in `commonMain` |
| Browser work | Generate a small Kotlin/JS helper for trusted work in a browser Web Worker |
| Native compiler settings | Add selected Kotlin/Native opt-ins |
| Apple project updates | Update selected Xcode app targets, source XML plists, and explicit CocoaPods or Swift module references |
| Desktop packaging | Share app identity and a build number with a Compose Desktop application, generate its installer icons, and derive a stable Windows upgrade code |
| App icons | Install owned Android launcher icons and an Apple universal AppIcon |
| Validation | Report resolved values, diagnose setup problems, create CI reports, and preview source-changing work |
| Release checks | Derive one ordered build number for both platforms, enforce an optional published-code baseline, and offer per-platform re-upload dials |

## Supported tool versions

| Tool | Supported or verified behavior |
|---|---|
| Gradle | Gradle 8.5 or newer is supported. A published-plugin fixture verifies the 8.5 floor. |
| Kotlin Gradle plugin | Stable 2.4.x releases are supported. A real fixture applies KGP 2.4.0, generates a fields-only BuildConfig in `commonMain`, and compiles code that reads it. Stable numeric `-release-N` runtime metadata is accepted. RC, Beta, dev, and arbitrary suffixes are rejected. |
| Android Gradle plugin | AGP 8.5.2 through 9.3.x is supported. Real fixtures cover the AGP 8.5.2 classic adapters and the AGP 9.3.1 classic and KMP-native adapters. |
| Configuration cache | Reuse is verified for the KGP integration on the current Gradle wrapper. |

Declare KGP and AGP versions in the root `plugins` block with `apply false` when
subprojects use them. This keeps their typed APIs visible to KiteSSOT. If a
requested integration is isolated in a sibling classloader, configuration fails
with setup guidance instead of silently skipping the feature.

KiteSSOT uses a root aggregation model. Gradle Isolated Projects are not
supported.

## Defaults

| Setting | Default | Meaning |
|---|---|---|
| `appName`, `version`, `appId`, `android.idSuffix`, `ios.bundleIdSuffix`, `ios.deploymentTarget`, and `jvmTarget` | Unset | KiteSSOT leaves the matching platform value alone unless a configured feature needs it. |
| `ios.marketingVersion` | `version`, when present | Apple can still use a separate marketing version. |
| `scheme` | Pack `1`, `major(3)`, `minor(3)`, `patch(2)`, and `rebuild(1)` | One build-number formula for both platforms. |
| `android.versionCode` | Unset, so the resolved code comes from `scheme` | Assign a value to bypass the formula. |
| `ios.buildNumber` and `desktop.buildNumber` | Unset, so the resolved number is the `scheme` result as a string | Assign a value to bypass the formula. |
| `android.rebuild`, `ios.rebuild`, and `desktop.rebuild` | `0` | Re-upload dials. Bump one when a store burns a number. |
| `locales` | Discover supported locale-only resource directories when a shared project or resources directory can be resolved | You can replace discovery with an explicit list. |
| `propagate.appName` | `true` | A present app name can reach enabled platform consumers. |
| `propagate.bundleId` | `true` | A present app ID can reach enabled platform consumers. |
| `propagate.version` | `true` | Present platform release values can reach enabled consumers. |
| `propagate.locales` | `true` | Canonical locales can reach enabled metadata consumers. |
| `android.applySdkLevels` | `true` | Present Android SDK and NDK values are applied. |
| `android.filterResourcesToLocales` | `false` | Locales do not prune packaged Android resources unless you opt in. |
| `logo {}` | Not configured | App icon installers are disabled. |
| `ios { sync {} }` | Not configured | Apple source files are not changed. |
| `ios.sync.sanitizePlist` | `false` | The source Info.plist is not changed. |
| `ios.sync.renameSharedModule(...)` | Not called | Podfile and Swift module-reference migration is disabled. |
| `nativeOptIns {}` | Not configured | Kotlin/Native opt-ins are not added. |
| `logo.takeOverLegacyIcons` | `false` | Legacy Android icon takeover is not allowed. |
| `backups` | `true` | Eligible user-owned files receive a recovery copy before first replacement. |
| `dryRun` | `false` | Explicit installers and migrations apply their reviewed plan. Build-owned generators ignore this switch. |
| `logo.androidSafeZone` | `66.0 / 108.0` | The adaptive-icon foreground uses Android's standard safe area. |
| `ios.sync.targets(...)` | Empty | KiteSSOT selects an Xcode app automatically only when there is exactly one. |
| `ios.sync.onConflict` | `FAIL` | A conflicting plist value stops the complete plist plan. |
| `web { ioWorker {} }` | Not configured | No browser helper is generated. |
| `web.ioWorker.projects(...)` and `web.ioWorker.targets(...)` | Empty | A browser runtime is never guessed. Project scope may fall back only to the selected shared project. |
| `web.ioWorker.packageName` | `kitessot.generated` | Generated worker source uses this package. |
| `buildConfig {}` | Not configured | No runtime constants object is generated. |
| `buildConfig.packageName` | `kitessot.generated` | Generated BuildConfig source uses this package. |
| `buildConfig.className` | `BuildConfig` | The generated object uses this name. |
| `buildConfig.includeIdentity` | `true` | Enabling BuildConfig requires complete identity values unless this is set to `false`. |
| `buildConfig.allowBuildCache` | `false` | Generated values do not enter local or remote Gradle build caches unless you opt in. |
| `desktop {}` | Not configured | No values reach Compose Desktop, and no installer icons are generated. |
| `desktop.icons` | `true` | Generates installer icons only while `logo {}` is also configured. |
| `desktop.roundMacOsIcon` | `true` | The generated macOS icon gets Apple's rounded-square mask. |
| `desktop.linuxPackageName` | A Debian-legal slug derived from `appName` | Set it yourself when the slug is not the name you publish under. |
| `desktop.deriveUpgradeUuid` | `false` | The Windows upgrade code is left to jpackage instead of being derived from `appId`. |

## Reading values in Gradle build logic

The root extension exposes each configured value as a Gradle `Property` or
`Provider`. Keep it lazy when another API accepts a provider:

```kotlin
import io.github.yuroyami.kitessot.KiteSsotExtension
import org.gradle.kotlin.dsl.getByType

val ssot = extensions.getByType<KiteSsotExtension>()
val minSdkProvider = ssot.android.minSdk

// Replace this with the other plugin's actual extension.
otherPluginExtension.minimumSdk.set(minSdkProvider)
```

Call `get()` only when the receiving API needs a plain value and the property is
known to be set. Use `orNull` when it is optional:

```kotlin
val requiredMinSdk: Int = ssot.android.minSdk.get()
val optionalMinSdk: Int? = ssot.android.minSdk.orNull
```

The root model also provides read-only derived providers:

| Provider | Result |
|---|---|
| `versionCode` | Explicit `android.versionCode`, or the value derived from `scheme` |
| `androidApplicationId` | App ID plus Android suffix |
| `iosBundleId` | App ID plus Apple suffix |
| `canonicalLocales` | Normalized locale list |
| `resolvedSharedProjectPath` | Effective shared Gradle project path |

Read the derived provider when you want the resolved value.
`android.versionCode` and `ios.buildNumber` are inputs. They stay empty until
you assign them, and an assigned value replaces the `scheme` result.

The deprecated `Project.kiteSsot` accessor is the compatibility path for old
subproject scripts. Treat it as read-only. It reaches across projects and does
not support Isolated Projects. Generated BuildConfig is the supported way to
read selected identity and public client values from application code.

## App identity and release values

All identity fields are optional. A field reaches a platform only when the
field is present, its `propagate` gate is enabled, and a matching consumer is
selected.

| Property | Type | Purpose and rules |
|---|---|---|
| `appName` | `Property<String>` | Android receives the `appName` manifest placeholder. Explicit Apple sync can use it for product and display names. |
| `version` | `Property<String>` | Android display version and the default source for `ios.marketingVersion`. When consumed, it must be non-blank, contain no control characters, and contain at most 255 characters. |
| `android.versionCode` | `Property<Int>` | Android store build number in `1..2_100_000_000`. It follows `scheme` unless you assign a value. |
| `android.rebuild` | `Property<Int>` | Feeds `scheme` as `v.rebuild`. Bump it when Play has already taken the current code. |
| `android.publishedVersionCode` | `Property<Int>` | Optional offline lower bound. The next resolved Android code must be greater while Android version propagation is active for a detected app. KiteSSOT does not contact a store. |
| `ios.marketingVersion` | `Property<String>` | Apple `MARKETING_VERSION` during explicit Apple sync. It must contain three non-negative integer components. |
| `ios.buildNumber` | `Property<String>` | Apple `CURRENT_PROJECT_VERSION` during explicit Apple sync. It follows `scheme` unless you assign a value. An assigned value accepts one to three numeric components. The positive first part may use at most four digits, and each optional later part may use at most two. |
| `ios.rebuild` | `Property<Int>` | Feeds `scheme` as `v.rebuild` for Apple. Bump it when TestFlight has already taken the current build number. |
| `appId` | `Property<String>` | Reverse-DNS base for the Android application ID and Apple bundle ID. |
| `android.idSuffix` | `Property<String>` | Optional Android-only suffix appended to `appId`. |
| `ios.bundleIdSuffix` | `Property<String>` | Optional Apple-only suffix appended to `appId`. |
| `ios.deploymentTarget` | `Property<String>` | Compatibility assertion required by the Apple universal AppIcon installer. It does not write `IPHONEOS_DEPLOYMENT_TARGET`. |

### Build numbers and the scheme

Android needs an `Int` version code. Apple needs a `String` build number. One
formula at root feeds both:

```kotlin
kiteSsot {
    version = "1.4.0"

    scheme { v -> ... }   // optional
}
```

The lambda receives `v.major`, `v.minor`, `v.patch`, and `v.rebuild`. It returns
one `Int`. Android uses that `Int`. Apple uses the same number as a string.

The default formula packs `1 | major(3) | minor(3) | patch(2) | rebuild(1)`:

| Version | Rebuild | Result |
|---|---|---|
| `1.4.0` | `0` | `1001004000` |
| `1.4.0` | `1` | `1001004001` |
| `1.4.1` | `0` | `1001004010` |
| `1.5.0` | `0` | `1001005000` |

Fixed widths keep build numbers ordered as versions increase. A version bump
always beats any rebuild of the previous version.

When a consumer needs a derived number, `version` must use exactly `x.y.z` with
no leading zeroes. Under the default formula, major and minor must be in
`0..999`, patch must be in `0..99`, and rebuild must be in `0..9`.

Do you need a different shape? Write your own `scheme { }`, or assign
`android.versionCode` and `ios.buildNumber` directly.

Upgrade note: the pre-3.0 formula gave `1.4.1` the code `1001004001`. The 3.0
default gives `1001004010`. Derived version codes therefore change in 3.0. The
new numbers are higher, so Play stays happy. Bring your old formula as a
`scheme { }` if you need the exact previous numbers.

## Project and target selection

Selectors tell KiteSSOT exactly where a value or generated file belongs.

| Selector | Selection rule |
|---|---|
| `modules.androidApps(...)` | Exact absolute Gradle project paths for app identity, app versions, app-name placeholders, locale filtering, and Android icon selection. Without a call, KiteSSOT selects a single detected app. Multiple detected apps require an explicit selector when an app-scoped operation is active. The single Android icon destination accepts at most one effective app. When no Android application plugin is applied, it can use `modules.androidAppDirectory`. SDK and JVM policy are not restricted by this selector. |
| `modules.desktopApps(...)` | Exact absolute Gradle project paths for desktop app identity, build numbers, packaging names, and installer icons. Without a call, KiteSSOT selects a single detected Compose Desktop application. Multiple detected applications fail configuration and name every candidate. |
| `modules.shared` | Exact KMP project that owns generated `commonMain` source and provides the default KMP scope. Without a value, KiteSSOT detects a sole KMP project. |
| `nativeOptIns.projects(...)` | Exact KMP projects that may receive Kotlin/Native opt-ins. Without a call, the scope can fall back to `modules.shared`. |
| `web.ioWorker.projects(...)` | Exact KMP projects that may receive browser-worker source. Without a call, the scope can fall back to `modules.shared`. |
| `web.ioWorker.targets(...)` | Exact Kotlin/JS targets that use a browser runtime. This list is required when the `ioWorker` block is configured. |
| `ios.sync.targets(...)` | Exact Xcode application targets whose build configurations explicit Xcode sync may change. Without a call, an app-setting update can auto-select only a sole app target. Project-level locales and file-based plist, Podfile, and Swift work use their own scopes and do not require this selector. |

Active selectors are validated and must contain unique entries. Unknown
projects, projects with the wrong plugin, ambiguous apps, ambiguous Xcode
targets, and attempts to assign one bundle ID to multiple apps fail with a clear
error. Two or more KMP projects also fail when `modules.shared` is not set.

The root project participates in project discovery. A selected value can point
to the root project when the root applies the required platform plugin.

## Files and directories

Use typed Gradle path properties:

| Property | Purpose |
|---|---|
| `modules.composeResources` | Compose resources directory used for locale discovery |
| `modules.androidAppDirectory` | Android application directory used by the icon installer |
| `ios.pbxproj` | Source `project.pbxproj` used by explicit Apple sync |
| `ios.podfile` | Podfile used by explicit shared-module migration |
| `ios.infoPlist` | Source XML Info.plist used by explicit sanitization |
| `ios.appDirectory` | Apple source tree used for narrowly scoped Swift import migration |
| `ios.appIconDirectory` | AppIcon installation directory |

`modules.shared` selects a Gradle project, not a directory. Swift and pod module
identities are named in `ios { sync { renameSharedModule(from = ..., to = ...) } }`.

The old `sharedModule`, `oldSharedModuleName`, `androidAppModule`,
`iosProjectPath`, `iosPodfilePath`, `iosInfoPlistPath`, `iosAppDir`, and
`iosAppiconsetPath` properties are removed. They have no replacement beyond the
typed properties above.

Every source-changing task keeps resolved paths inside the root project or the
selected Apple source tree. Symlinks, special files, path traversal, and paths
outside the declared root are rejected.

## Locales

KiteSSOT stores one canonical locale list that can be rendered for Android
resources and Xcode regions.

### Accepted values

- Use a 2 or 3 letter language code with optional script, region, and variants.
- Examples include `en`, `pt-BR`, and `sr-Latn`.
- General BCP-47 extensions and private-use tags are rejected because they do
  not map consistently to both platforms.
- Android forms such as `pt-rBR` and `b+sr+Latn` are accepted at the boundary
  and normalized.
- The configured list may contain at most 1,000 entries.
- Each raw entry may contain at most 255 characters.
- Results keep their input order and remove duplicates.

### Automatic discovery

When `locales` is not set, KiteSSOT can scan the explicit
`modules.composeResources` directory or the selected shared project's
conventional Compose resources directory.

Discovery accepts only locale-only directory names such as:

- `values-en`
- `values-pt-rBR`
- `values-b+sr+Latn`

Direct hyphenated names such as `values-pt-BR` and mixed qualifiers such as
`values-en-night`, `values-land`, and `values-v26` are ignored. The scan checks
only immediate entries, does not follow links, and refuses more than 10,000
entries.

### Platform behavior

- Explicit Apple sync adds canonical tags to `knownRegions`. It preserves
  `Base` and unrelated existing regions because KiteSSOT does not own every
  `.lproj` directory.
- Android resource filtering is separate and defaults to off.
- With `android { filterResourcesToLocales = true }`, KiteSSOT replaces the
  selected application's locale-filter set with the canonical list.
- AGP 9 uses `localeFilters`. AGP 8 uses compatible resource qualifiers.
- On AGP 8, density, ABI, and unrelated resource configurations are preserved.
  The locale-shaped `car` UI-mode qualifier is also preserved. A `car` locale
  is emitted as the unambiguous `b+car`.
- The filtered locale list must not be empty.
- Android libraries are never pruned.

## Android behavior

| Value | Android application | Classic Android library | KMP-native Android library |
|---|:---:|:---:|:---:|
| `appName` manifest placeholder | Yes | No | No |
| Application ID | Yes | No | No |
| Version name and code | Yes | No | No |
| Locale filter | Opt in | No | No |
| Compile SDK | Yes | Yes | Yes |
| Minimum SDK | Yes | Yes | Yes |
| Target SDK | Yes | No | No |
| NDK version | Yes | Yes | Not available in this DSL |
| Java source and target compatibility | Yes | Yes | Not available in this DSL |
| Kotlin JVM target | When KGP is visible | When KGP is visible | Compatible Kotlin targets |

App-scoped identity values are applied during AGP DSL finalization, after the
module's own `android {}` block. A configured KiteSSOT value therefore wins.
Leaving a value unset preserves the module's value.

SDK levels and the NDK version live in `android { }`. Use `android.ndk` for the
NDK. `android.applySdkLevels = false` turns this alignment off.

`jvmTarget` is root-global policy. It aligns Java compatibility in compatible
classic Android modules and Kotlin JVM targets in detected Kotlin
Multiplatform, Kotlin/JVM, and Kotlin Android projects. Module, native, and
web selectors do not limit this alignment.

Before use, KiteSSOT validates SDK relationships, application and bundle IDs,
NDK syntax, Java levels, and supported runtime tool versions.

## Generated BuildConfig

BuildConfig generation is an optional way to read public app identity and
client configuration from KMP code. Configuring `buildConfig { }` turns it on.
Keep `enabled = false` inside the block when you want to force it off without
deleting your configuration.

When enabled, KiteSSOT:

- generates one Kotlin object below
  `build/generated/kitessot/commonMain/kotlin`;
- wires that directory into the selected shared project's `commonMain`;
- can include the app name, version, version code, Android application ID,
  Apple bundle ID, and locales;
- supports custom `String`, `Int`, `Long`, `Boolean`, and `Double` fields;
- accepts a lazy `Provider<String>` for string fields;
- supports a fields-only object with `includeIdentity = false`;
- keeps generation active when `dryRun = true`, because the generated file is a
  compilation input.

BuildConfig needs a shared project, so `modules.shared` must resolve. When
`includeIdentity` is `true`, `appName`, `version`, a resolvable version code,
and `appId` must all be present.

The generator rejects:

- invalid or reserved Kotlin identifiers;
- duplicate field names;
- custom names that collide with identity fields;
- arbitrary Kotlin source fragments;
- malformed literals;
- non-finite `Double` values.

The generator accepts at most 512 custom fields. A String value may contain at
most 10,000 characters. A legacy transport entry may contain at most 65,536
characters, and all legacy entries together may contain at most 1,048,576
characters. `Int.MIN_VALUE` and `Long.MIN_VALUE` receive valid canonical Kotlin
literals.

Checksum ownership prevents deletion or replacement of unknown or manually
modified generated files.

BuildConfig is not a secret store. Generated values can enter source, task
inputs, build scans, KLIBs, APKs, IPAs, decompiled binaries, and any explicitly
enabled build cache. Do not place passwords, private API keys, signing material,
or other credentials in it.

## Browser Web Worker helper

The optional worker helper is a single-shot API for Kotlin/JS browser targets.
Configuring `web { ioWorker { } }` turns it on. KiteSSOT never guesses that a
JavaScript target uses a browser.

When enabled, KiteSSOT:

- requires exact KMP project and Kotlin/JS browser target selection;
- generates `kiteSsotOffload` below
  `build/generated/kitessot/<target>Main/kotlin`;
- supports custom target names and package names;
- includes request IDs and explicit success or error state in protocol messages;
- checks Worker, Blob, and object-URL browser APIs;
- revokes object URLs;
- uses a 30-second default timeout;
- terminates the worker after success, failure, timeout, or coroutine
  cancellation;
- normalizes errors and handles worker creation and message-posting failures;
- keeps generation active when `dryRun = true`, because the source is a
  compilation input.

The consuming project must provide `kotlinx-coroutines-core`. Its Content
Security Policy must permit Blob workers, normally with `worker-src blob:`.
The helper executes caller-supplied JavaScript text, so that text must be
trusted and must not be built from user input.

Node.js-only targets and `wasmJs` are not supported.

## Desktop application packaging

Compose Multiplatform packages a desktop application as a macOS `.dmg` or
`.pkg`, a Windows `.msi` or `.exe`, or a Linux `.deb`, `.rpm`, or AppImage.
Configuring `desktop { }` extends the root `appName`, `version`, and `appId`
to that package, folds its build number into the same `scheme` every other
platform uses, and generates its installer icons from your `logo { }` art.

When enabled, KiteSSOT:

- shares `appName`, `version`, and `appId` with the detected Compose Desktop
  application, the same way it does for Android and iOS;
- folds the desktop build number into the root `scheme`, so `desktop.rebuild`
  bumps it independently of `android.rebuild` and `ios.rebuild`;
- generates the macOS `.icns`, Windows `.ico`, and Linux `.png` installer
  icons from the configured `logo { }` art with `generateKiteSsotDesktopIcons`,
  writing only to `build/`;
- wires that task into packaging automatically, with no `dependsOn` for you
  to write;
- can derive a stable Windows upgrade code from `appId` with
  `desktop { deriveUpgradeUuid = true }`, so an MSI upgrades an existing
  install instead of sitting beside it;
- derives a Debian-legal Linux package name from `appName` unless
  `desktop { linuxPackageName }` is set;
- rejects a resolved `version` outside the Windows MSI/EXE numeric limits
  before Compose does;
- offers the same offline `publishedBuildNumber` guard already available for
  `android { }` and `ios { }`, with failures naming `desktop { }` throughout.

Desktop values are written through the same early callback used for Android
and iOS. Compose holds identity as plain `var` fields, not lazy `Property`
objects, and reads them inside its own `afterEvaluate`. KiteSSOT registers
its write earlier than that; a later registration would let every value
above land too late.

KiteSSOT selects the sole Compose Desktop application project it detects.
Two or more candidates fail configuration and name every one of them; select
the intended project explicitly with `modules { desktopApps(":desktopApp") }`.
An explicitly selected project that applies `org.jetbrains.compose` but
configures no desktop application also fails, naming that project.

`desktop { icons = true }` requires a usable `logo { }` block: a foreground
PNG plus exactly one of a background PNG or a background color. Configuration
fails otherwise. With no `logo { }` block at all, icon generation defaults
off instead of failing.

The derived Windows upgrade code is a UUIDv5 built from a fixed namespace,
`6b0f4c1e-6d4a-5a2f-9c3d-1f6b2a8e7d40`, and your resolved `appId`. This
namespace is a published contract: changing it would change every upgrade
code KiteSSOT has ever derived, breaking in-place upgrades for everyone who
already installed the app. An `upgradeUuid` a module already set is always
kept.

Windows `.msi` and `.exe` accept only `MAJOR.MINOR.BUILD` versions, with
MAJOR and MINOR capped at 255 and BUILD at 65535. The cap applies only when
Msi or Exe is an enabled target format. Debian and RPM package names must
start with a letter or digit and be at least two characters long; KiteSSOT
derives one from `appName` or fails, naming `desktop { linuxPackageName }` as
the escape hatch. The macOS bundle identifier follows the same reverse-DNS
rule every Apple bundle ID in KiteSSOT uses: letters, digits, hyphens, and
periods, in at least two dot-separated segments.

`org.jetbrains.compose` refuses to apply without the Kotlin Compose compiler
plugin, `org.jetbrains.kotlin.plugin.compose`, on the same classpath, so both
belong in the root `plugins { }` block with `apply false`, for the same
classloader reason Kotlin and AGP already are. Supported Compose Gradle
plugin releases are `1.11.0` through `1.12.x`. `kiteSsotDoctor` reports the
active version's compatibility.

## Kotlin/Native compiler opt-ins

Native opt-ins are off until you configure `nativeOptIns { }`. When configured,
selected native compilations receive these markers:

- `kotlinx.cinterop.ExperimentalForeignApi`
- `kotlin.experimental.ExperimentalObjCName`
- `kotlin.experimental.ExperimentalNativeApi`

`nativeOptIns { add("...") }` adds validated fully qualified marker names.
KiteSSOT removes duplicates while preserving order.

Only selected KMP projects and native compilations receive these options.
KiteSSOT does not add annotations to source files.
`nativeOptIns { projects(...) }` limits only this native policy. It does not
limit the root-global JVM alignment from `jvmTarget`.

## Explicit Apple project changes

Apple source changes never run as part of normal compilation, linking, or
archiving. You must configure `ios { sync { } }` and run the named task.

### Xcode project file

Explicit Xcode sync can update:

- product and display names;
- bundle ID;
- marketing version;
- build number;
- project-level locale regions;
- an existing AppIcon catalog assignment.

KiteSSOT follows `PBXNativeTarget` application entries through their
configuration-list IDs to exact `XCBuildConfiguration` entries. It can select
one or more named application targets, but it refuses to assign one bundle ID
to multiple app targets.

There is no global build-settings fallback. Missing targets, malformed graph
links, missing expected settings, duplicate objects, garbage input, and parser
uncertainty stop the complete plan. Text replacement handles special
characters without regex replacement errors.

### Source Info.plist

Plist sanitization runs when you set
`ios { sync { sanitizePlist = true } }`. The sanitizer supports source XML
property lists only. It does not convert binary, OpenStep, or generated plists.

It:

- enables secure XML processing;
- disables external entities and DTDs;
- rejects unsafe declarations;
- rejects duplicate or malformed root dictionary entries;
- rejects input larger than 4 MiB when measured as UTF-8;
- requires a lossless baseline round trip;
- can add SSOT build-setting references;
- can manage `ITSAppUsesNonExemptEncryption` through
  `ios.sync.nonExemptEncryption`;
- can manage `CADisableMinimumFrameDurationOnPhone` through
  `ios.sync.proMotion`.

Conflict behavior is explicit and set with `ios.sync.onConflict`:

| Policy | Result |
|---|---|
| `FAIL` | Stop and preserve the original file. This is the default. |
| `KEEP` | Preserve the conflicting value and report a warning. |
| `REPLACE` | Allow explicit replacement. |

A failure produces no partial plist rewrite.

### Shared-module references

Shared-module migration runs only when you call
`ios { sync { renameSharedModule(from = "OldShared", to = "Shared") } }`. Both
names are required. KiteSSOT does not infer the old module from a Podfile.

The migration:

- updates at most one exact local-pod declaration;
- updates only plain, exact Swift module imports;
- masks comments, strings, raw strings, and extended regex literals before
  matching imports;
- stops before any write when it finds an unterminated lexical region;
- skips dependency, vendor, build, checkout, user-data, and symlink trees.

It does not rename a directory, update `settings.gradle`, run CocoaPods, or
change qualified, testable, implementation-only, or bridging-header imports.

## App icon installers

The icon installers and their platform consumers form one contract. Installing
files does not prove that the app uses them.

The Android application manifest remains user-owned. It must point to
`@mipmap/ic_launcher` and, when used,
`@mipmap/ic_launcher_round`.

The selected Xcode app target must define
`ASSETCATALOG_COMPILER_APPICON_NAME` for the configured catalog, which is
`AppIcon` by default. Explicit Xcode sync can align an existing assignment, but
it refuses to invent a missing setting. Diagnostics check both Android and Apple
consumption.

### Android icons

Android icon installation requires:

- a configured `logo { }` block;
- `logo.foreground`, a PNG;
- exactly one of `logo.background`, a PNG, or `logo.backgroundColor`, written as
  `#RRGGBB` or `#AARRGGBB`;
- at most one effective Android application.

The installer:

- limits each PNG to 32 MiB;
- limits each dimension to 4,096 pixels;
- limits decoded content to 16,777,216 pixels;
- rejects an input located inside the output tree;
- contains the foreground without stretching it;
- covers the canvas with the background;
- generates adaptive foreground and background assets;
- generates legacy square and round images at five densities;
- generates API 26 wrappers;
- generates API 33 monochrome wrappers when
  `android.compileSdk >= 33`;
- refuses unknown first-contact output files and same-stem template collisions
  unless `logo.takeOverLegacyIcons = true` explicitly authorizes a backed-up
  takeover;
- replaces or removes later files only when their ownership checksums still
  match.

### Apple AppIcon

Apple icon installation requires:

- a configured `ios { sync { } }` block;
- a configured `logo { }` block;
- `logo.foreground`, a PNG;
- exactly one of `logo.background` or `logo.backgroundColor`;
- `ios.deploymentTarget >= 12.0`;
- Xcode 14 or newer.

The installer creates an opaque 1024 by 1024 composite and a universal
`Contents.json`. It creates only the default universal appearance. Dark and
Tinted appearances and Icon Composer files remain outside this installer.

Input decoding and output paths are bounded and validated. With backups enabled,
first-contact recovery is stored below `.kitessot/recovery`, outside `build/`,
so `clean` does not erase it. Checksum ownership is always enforced.
Unreferenced PNG files are reported but not deleted.

### Legacy Android icon takeover

`logo.takeOverLegacyIcons` authorizes a deliberate takeover of known legacy
files, same-stem Android Studio template icons, and unowned first-contact paths
that the current installer will claim.

The takeover:

- is available only through an explicit task or the enabled installer
  transaction;
- requires a complete replacement icon;
- records SHA-256 provenance before deleting the first candidate;
- shares the icon installer's ownership lock;
- preserves current manifest-owned icons;
- rolls back removed files if the batch cannot finish;
- lists exact candidates without changing them when `dryRun = true`.

## Diagnostics

| Task | Behavior |
|---|---|
| `kiteSsotVerify` | Prints the best available resolved values and selected paths. It does not change files. |
| `kiteSsotDoctor` | Runs resilient setup checks and reports findings. Findings do not fail the task. |
| `kiteSsotCheck` | Uses the same checks, writes deterministic JSON or SARIF, then fails on errors or optionally on warnings. |
| `kiteSsotPlan` | Reports selected operations, paths, targets, and policies without changing files. |

Configure the strict CI task from the root build:

```kotlin
import io.github.yuroyami.kitessot.KiteSsotCheckTask
import io.github.yuroyami.kitessot.KiteSsotDiagnosticReportFormat

tasks.named<KiteSsotCheckTask>("kiteSsotCheck") {
    reportFormat.set(KiteSsotDiagnosticReportFormat.SARIF)
    reportFile.set(layout.buildDirectory.file("reports/kitessot/diagnostics.sarif"))
    failOnWarnings.set(true)
}
```

Stable diagnostic families are:

| IDs | Area |
|---|---|
| `KMPS001` to `KMPS003` | Android app-name placeholder and launcher-icon references |
| `KMPS010` to `KMPS012` | Source Info.plist references and Apple bundle-name compatibility |
| `KMPS020` to `KMPS024` | Xcode target selection, AppIcon state, catalog selection, and deployment compatibility |
| `KMPS030` to `KMPS031` | Android resource and icon state |
| `KMPS040` | Locale canonicalization |
| `KMPS050` | Build-number derivation |
| `KMPS060` to `KMPS062` | KGP visibility and active AGP or KGP compatibility |
| `KMPS070` to `KMPS071` | Exact Android project and Xcode target selectors |
| `KMPS080` to `KMPS087` | Desktop identity, icons, Compose plugin compatibility, application selection, bundle identifier, package version, and Linux or Windows packaging names |
| `KMPS901` to `KMPS952` | Provider, path, and input-fingerprint resolution |
| `KMPS999` | Unexpected diagnostic-engine failure |

`KMPS011` follows `ios.sync.onConflict`. `FAIL` is an error. `KEEP` is a
warning that describes intentionally preserved drift. `REPLACE` remains an
error until the explicit migration applies the replacement.

## Source-change safety rules

Every migration or installer follows these rules:

- The capability stays off until you configure its block.
- Ordinary build tasks do not depend on source-changing tasks.
- The complete operation is planned before the first source write.
- User-owned text is read as strict UTF-8.
- Paths must stay inside their declared roots.
- Symlinks and special files are rejected.
- Text changes use sibling staging and atomic replacement where supported.
- Directory durability is attempted after replacement.
- When `backups` is enabled, first-contact text recovery uses a write-once
  `.kitessot.bak` copy.
- Multi-file Apple work locks and stages the complete batch.
- Source snapshots are checked again immediately before commit.
- A later commit failure rolls back files already changed by that transaction.
- Swift discovery is limited to depth 32 and 10,000 entries.
- Each text file is limited to 64 MiB.
- The combined Apple snapshot and rendered-output budget is 256 MiB.
- Generated and installed assets use owner IDs, normalized relative paths,
  checksums, lock files, and atomic ownership manifests.
- Unknown, manually modified, escaping, duplicated, special, or symlinked
  output is never silently overwritten or deleted.
- Source installers are intentionally not cacheable and rerun their safety
  checks every time.
- Only build-owned generators use normal Gradle output and build-cache
  semantics.

## Current limitations

KiteSSOT does not currently provide:

- Gradle Isolated Projects support;
- per-flavor, per-build-type, or per-Xcode-target identity overlays;
- xcconfig generation or automatic Xcode include wiring;
- generated or binary Info.plist conversion;
- automatic project-directory or `settings.gradle` renames;
- Node.js or wasm workers;
- SVG or vector icon input;
- Dark or Tinted Apple icon variants;
- launch-screen editing;
- store API access;
- signing configuration;
- secret management;
- `pod install`;
- release upload;
- automatic version increments;
- a Windows or Linux desktop build number;
- desktop locale propagation;
- desktop `vendor`, `description`, or `copyright` management;
- a verified real signed DMG, a real MSI upgrade across two versions, or a
  real Debian install produced from these outputs.

These are explicit product boundaries, not implied features.

The desktop build-number scheme reaches the macOS bundle version only.
Windows and Linux packaging have no separate build-number field for it to
fill.

Desktop application detection reads Kotlin `internal` initialization flags on
Compose's `DesktopExtension` through reflection, since Compose exposes no
public API for it. If a future Compose release renames those flags,
auto-detection silently stops finding any desktop application, and you have
to name it yourself with `modules { desktopApps(":yourApp") }`.
