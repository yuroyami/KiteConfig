# KiteConfig feature reference

This page describes current KiteConfig behavior. It is not a roadmap. Start with
the [README](README.md) if you are setting up the plugin for the first time.

KiteConfig is applied to the root Gradle project. You declare values in one
`kiteConfig {}` block, and four rules govern everything:

1. Write each value once. It reaches every platform in your project.
2. A platform block holds only what differs there. Nothing is copied when a
   block opens, so declaration order decides nothing.
3. A feature is on when you gave it what it needs. A feature that needs nothing
   from you asks for `enabled = true`.
4. `autoApply` decides who applies changes to the files you commit. Nobody else.
   With it off, `kiteApply` does it when you ask.

Conventions: a platform's version follows the shared one, build numbers follow
the shared scheme, and locales can be discovered. The model is frozen after
root-project evaluation.

The `kiteConfig` accessor gives any build file a read-only view of the resolved
values. It reaches across projects, so it does not support Gradle Isolated
Projects, which the plugin does not support anywhere. See "Reading values in
Gradle build logic" below.

## What KiteConfig can manage

| Area | What KiteConfig can do |
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
subprojects use them. This keeps their typed APIs visible to KiteConfig. If a
requested integration is isolated in a sibling classloader, configuration fails
with setup guidance instead of silently skipping the feature.

KiteConfig uses a root aggregation model. Gradle Isolated Projects are not
supported.

## Defaults

| Setting | Default | Meaning |
|---|---|---|
| `appName`, `appId`, `version`, their platform overrides, `appId { suffix }`, and `jvm { target }` | Unset | KiteConfig leaves the matching platform value alone unless a configured feature needs it. |
| `ios { version }` | the shared `version` | Apple can show a different marketing version. |
| `version { scheme }` | Pack `1`, `major(3)`, `minor(3)`, `patch(2)`, `rebuild(1)`, as text | One build-number scheme for every platform. |
| `<platform> { version { buildNumber } }` | Unset, so the number comes from the scheme | Assign a value to bypass the scheme. |
| `version { rebuild }` | `0` | Re-upload counter. Bump it when a store burns a number. |
| `<platform> { version { publishedBuildNumber } }` | Unset | The highest number already shipped on that platform. The next one must beat it. |
| `locales { tags }` | Discovered from the shared module's Compose resources | An explicit list replaces discovery. An explicitly empty list means no locales. |
| `<topic> { enabled }` in a platform block | `true` for declared values | Turning it off stops the write and leaves the value readable. |
| `skip(p)` / `only(p)` at the root | every platform found | The outer boundary. Exclusion wins over everything, and `only()` with no arguments is an error. |
| `autoApply` | `false` | Ordinary builds change nothing you commit. `kiteApply` applies the plan when you run it. |
| `android { sdk(...) }` | Not configured | SDK alignment runs exactly when at least one of `compileSdk`, `minSdk`, `targetSdk`, or `ndk` is set. |
| `android { locales { filterResources } }` | `false` | Locales do not prune packaged Android resources unless you opt in. |
| `logo { foreground }` | Unset | Setting it turns the topic on. Nothing is installed without art. |
| `logo { background }` | Unset | One choice: `color("#RRGGBB")` or `image(file(...))`. |
| `logo { foregroundScale }` | Each renderer's own ratio | A shared linear ratio in `(0, 1]`, overridable per platform. Unset, existing icons do not move. |
| `desktop { logo { roundMac } }` | `true` | The generated macOS icon gets Apple's rounded-square mask. |
| `splash { enabled }` | `false` | Artwork, colors, a dark variant, and an empty block all leave it off. |
| `splash { dark }` | On once a dark value is set | A platform can drop it with `dark { enabled = false }`. |
| `android { splash { theme } }` | Unset | The app theme the generated style inherits. |
| `ios { infoPlist { proMotion } }` and `{ nonExemptEncryption }` | Unset | An unset key is left exactly as it is. |
| `ios { xcodeTargets(...) }` | Empty | KiteConfig finds a sole application target. Naming one is needed only when several exist. |
| `ios { renameSharedModule(...) }` | Not called | Podfile and Swift module-reference migration is off. |
| `optIns` | Off | On once a marker is added, or with `enabled = true` for the built-in set alone. |
| `buildConfig` | Off | On once a field is declared, or with `enabled = true` for identity-only output. |
| `buildConfig { packageName }` | `kiteconfig.generated` | Generated source uses this package. |
| `buildConfig { className }` | `KiteBuildConfig` | Does not clash with the `BuildConfig` that AGP generates. |
| `buildConfig { includeIdentity }` | `true` | Identity-carrying output needs complete identity values unless this is `false`. |
| `buildConfig { allowBuildCache }` | `false` | Generated values stay out of build caches unless you opt in. |
| `web { ioWorker { enabled } }` | `false` | Naming targets or a package is configuration, not a request for the feature. |
| `web { ioWorker { packageName } }` | `kiteconfig.generated` | Generated worker source uses this package. |
| `desktop` | Not required | Desktop identity flows by default like every other platform. Stop it with `skip(desktop)`. |
| `desktop { linuxPackageName }` | A Debian-legal slug derived from `appName` | Set it yourself when the slug is not the name you publish under. |
| `desktop { deriveUpgradeUuid }` | `false` | The Windows upgrade code is left to jpackage instead of being derived from `appId`. |
| `backups` | `true` | Eligible user-owned files receive a recovery copy before first replacement. |
| `dryRun` | `false` | The apply tasks print their plan and write nothing. Build-owned generators ignore this switch. |

## Reading values in Gradle build logic

Every resolved value is readable from any build file through the `kiteConfig`
accessor. One import, then use it:

```kotlin
import io.github.yuroyami.kiteconfig.kiteConfig

// Replace this with the other plugin's actual extension.
otherPluginExtension.minimumSdk.set(kiteConfig.minSdk)
```

The accessor returns a read-only view, so a consuming module cannot write to the
model. See the README section "Reading values back" for the full list of values.

Every member is a lazy `Provider`. Keep it lazy whenever the receiving API takes
one, and call `get()` only when it needs a plain value:

```kotlin
val minSdk: Int = kiteConfig.minSdk.get()
```

A value the root build file never declared has no value at all, and `get()` stops
the build. That is deliberate: falling back with `?: 24` would put a second copy
of that number in the consumer, which is the duplication this plugin exists to
remove.

The root model also provides read-only derived providers:

| Provider | Result |
|---|---|
| `versionCode` | An explicit `buildNumber`, or the number the scheme produced |
| `androidApplicationId` | The effective `appId` for Android |
| `iosBundleId` | The effective `appId` for iOS |
| `canonicalLocales` | Normalized locale list |
| `resolvedSharedProjectPath` | Effective shared Gradle project path |

Read the derived provider when you want the resolved value.
`android { version { buildNumber } }` and its siblings are inputs. They stay
empty until you assign one, and an assigned value replaces what the scheme would
have produced.

The `kiteConfig` accessor is how build logic reads these values; the view it
returns is read-only by type. For application code at runtime, the generated
`KiteBuildConfig` object is the supported way to read identity and public client
values.

## App identity and release values

All identity fields are optional. A field reaches a platform only when the
field is present, its flow is not skipped, and a matching consumer is
selected.

| Property | Type | Purpose and rules |
|---|---|---|
| `appName` | `Property<String>` | Android receives it as `android:label` through a generated manifest overlay, so your manifest needs no edit. Explicit Apple sync can use it for product and display names. |
| `version` | `Property<String>` | Android display version and the default source for the Apple marketing version. When consumed, it must be non-blank, contain no control characters, and contain at most 255 characters. |
| `android { version { buildNumber } }` | `Property<String>` | Android store build number, read as one whole number in `1..2_100_000_000`. It follows the scheme unless you assign a value. |
| `android { version { rebuild } }` | `Property<Int>` | Feeds the scheme as `v.rebuild`. Bump it when Play has already taken the current code. |
| `android { version { publishedBuildNumber } }` | `Property<String>` | Optional offline lower bound. The next resolved Android code must be greater while Android version propagation is active for a detected app. KiteConfig does not contact a store. |
| `ios { version }` | `Property<String>` | Apple `MARKETING_VERSION`. It must contain three non-negative integer components. |
| `ios { version { buildNumber } }` | `Property<String>` | Apple `CURRENT_PROJECT_VERSION`. It follows the scheme unless you assign a value. An assigned value accepts one to three numeric components. The positive first part may use at most four digits, and each optional later part may use at most two. |
| `ios { version { rebuild } }` | `Property<Int>` | Feeds the scheme as `v.rebuild` for Apple. Bump it when TestFlight has already taken the current build number. |
| `appId` | `Property<String>` | Reverse-DNS base for every platform identifier. |
| `<platform> { appId }` | `Property<String>` | That platform's exact identifier. No suffix is involved once it is set. |
| `<platform> { appId { suffix } }` | `Property<String>` | Appended to the shared `appId` for that platform. |
| `ios { deploymentTarget }` | `Property<String>` | Icon-format compatibility assertion, checked only when you declare it. It does not write `IPHONEOS_DEPLOYMENT_TARGET`. |

### Build numbers and the scheme

The stores disagree about what a build number looks like, so the scheme returns
text and each platform narrows it. Android reads one whole number; Apple
compares by numeric component, so a dotted build number works there.

```kotlin
kiteConfig {
    version = "1.4.0"

    version {
        scheme { v -> ... }  // optional
    }
}
```

The lambda receives `v.major`, `v.minor`, `v.patch`, and `v.rebuild`, and returns
a `String`.

The default scheme packs `1 | major(3) | minor(3) | patch(2) | rebuild(1)`:

| Version | Rebuild | Result |
|---|---|---|
| `1.4.0` | `0` | `1001004000` |
| `1.4.0` | `1` | `1001004001` |
| `1.4.1` | `0` | `1001004010` |
| `1.5.0` | `0` | `1001005000` |

Fixed widths keep build numbers ordered as versions increase. A version bump
always beats any rebuild of the previous version.

When a consumer needs a derived number, `version` must use exactly `x.y.z` with
no leading zeroes. Under the default scheme, major and minor must be in
`0..999`, patch must be in `0..99`, and rebuild must be in `0..9`.

Do you need a different shape? Write your own with `version { scheme { ... } }`,
or set an exact number per platform with
`android { version { buildNumber } }` and `ios { version { buildNumber } }`.

Coming from an older plugin line, the derived numbers change: a scheme that
gave `1.4.1` the code `1001004001` now gives `1001004010`. The new numbers are
higher, so Play still accepts them. Bring your old one in as
`version { scheme { ... } }` if you need the exact previous numbers.

## Project and target selection

Selectors tell KiteConfig exactly where a value or generated file belongs.

| Selector | Selection rule |
|---|---|
| `modules.androidApps(...)` | Exact absolute Gradle project paths for app identity, app versions, the app label, locale filtering, and Android icon selection. Without a call, KiteConfig selects a single detected app. Multiple detected apps require an explicit selector when an app-scoped operation is active. The single Android icon destination accepts at most one effective app. When no Android application plugin is applied, it can use `modules.androidAppDirectory`. SDK and JVM policy are not restricted by this selector. |
| `modules.desktopApps(...)` | Exact absolute Gradle project paths for desktop app identity, build numbers, packaging names, and installer icons. Without a call, KiteConfig selects a single detected Compose Desktop application. Multiple detected applications fail configuration and name every candidate. |
| `modules.shared` | Exact KMP project that owns generated `commonMain` source and provides the default KMP scope. Without a value, KiteConfig detects a sole KMP project. |
| `optIns.projects(...)` | Exact KMP projects that may receive Kotlin/Native opt-ins. Without a call, the scope can fall back to `modules.shared`. |
| `web.ioWorker.projects(...)` | Exact KMP projects that may receive browser-worker source. Without a call, the scope can fall back to `modules.shared`. |
| `web.ioWorker.targets(...)` | Exact Kotlin/JS targets that use a browser runtime. This list is required when the `ioWorker` block is configured. |
| `ios.rewrite.targets(...)` | Exact Xcode application targets whose build configurations explicit Xcode sync may change. Without a call, an app-setting update can auto-select only a sole app target. Project-level locales and file-based plist, Podfile, and Swift work use their own scopes and do not require this selector. |

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
identities are named in `ios { renameSharedModule(from = ..., to = ...) }`.


Every source-changing task keeps resolved paths inside the root project or the
selected Apple source tree. Symlinks, special files, path traversal, and paths
outside the declared root are rejected.

## Locales

KiteConfig stores one canonical locale list that can be rendered for Android
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

When `locales` is not set, KiteConfig can scan the explicit
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

- On iOS the canonical tags are written as `CFBundleLocalizations` in
  `Info.plist`. That is the key iOS reads to decide which languages the app
  offers. A Compose app has no `.lproj` directories, so without it the system
  sees no languages at all.
- The same tags are added to `knownRegions` in the Xcode project, which is
  Xcode's own list for projects that do have localized variant groups. It
  preserves `Base` and unrelated existing regions, because KiteConfig does not
  own every `.lproj` directory.
- Android resource filtering is separate and defaults to off.
- With `android { locales { filterResources = true } }`, KiteConfig replaces the
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
| `appName` as `android:label` | Yes | No | No |
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
module's own `android {}` block. A value declared in `kiteConfig { }` therefore wins.
Leaving a value unset preserves the module's value.

SDK levels and the NDK version live in `android { }`. Use `android.ndk` for the
NDK. Alignment runs only for the values you actually set; leave them unset to
leave the module's own values alone.

`jvm { target }` is the shared bytecode level. It aligns Java and Kotlin together in
classic Android modules and Kotlin JVM targets in detected Kotlin
Multiplatform, Kotlin/JVM, and Kotlin Android projects. Module, native, and
web selectors do not limit this alignment.

Before use, KiteConfig validates SDK relationships, application and bundle IDs,
NDK syntax, Java levels, and supported runtime tool versions.

## Generated BuildConfig

BuildConfig generation is an optional way to read public app identity and
client configuration from KMP code. Configuring `buildConfig { }` turns it on.
Opening the block is the only switch: remove or comment out `buildConfig { }`
to turn generation off.

When enabled, KiteConfig:

- generates one Kotlin object below
  `build/generated/kiteconfig/commonMain/kotlin`;
- wires that directory into the selected shared project's `commonMain`;
- can include the app name, version, version code, Android application ID,
  Apple bundle ID, and locales;
- supports custom `String`, `Int`, `Long`, `Boolean`, and `Double` fields;
- accepts a lazy `Provider` for any field type, not only strings;
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
Configuring `web { ioWorker { } }` turns it on. KiteConfig never guesses that a
JavaScript target uses a browser.

When enabled, KiteConfig:

- requires exact KMP project and Kotlin/JS browser target selection;
- generates `kiteConfigOffload` below
  `build/generated/kiteconfig/<target>Main/kotlin`;
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
Configuring `desktop { }` extends the shared `appName`, `version`, and `appId`
to that package, folds its build number into the same scheme every other
platform uses, and generates its installer icons from your `logo { }` art.

When enabled, KiteConfig:

- shares `appName`, `version`, and `appId` with the detected Compose Desktop
  application, the same way it does for Android and iOS;
- folds the desktop build number into the shared scheme, so the desktop `rebuild`
  bumps it independently of the Android and iOS ones;
- generates the macOS `.icns`, Windows `.ico`, and Linux `.png` installer
  icons from the configured `logo { }` art with `kiteInternalDesktopIcons`,
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
objects, and reads them inside its own `afterEvaluate`. KiteConfig registers
its write earlier than that; a later registration would let every value
above land too late.

KiteConfig selects the sole Compose Desktop application project it detects.
Two or more candidates fail configuration and name every one of them; select
the intended project explicitly with `modules { desktopApps(":desktopApp") }`.
An explicitly selected project that applies `org.jetbrains.compose` but
configures no desktop application also fails, naming that project.

Installer icon generation requires a usable `logo { }` block: a foreground
PNG plus exactly one of a background PNG or a background color. Configuration
fails otherwise. With no `logo { }` block at all, icon generation defaults
off instead of failing.

The derived Windows upgrade code is a UUIDv5 built from a fixed namespace,
`6b0f4c1e-6d4a-5a2f-9c3d-1f6b2a8e7d40`, and your resolved `appId`. This
namespace is a published contract: changing it would change every upgrade
code KiteConfig has ever derived, breaking in-place upgrades for everyone who
already installed the app. An `upgradeUuid` a module already set is always
kept.

Windows `.msi` and `.exe` accept only `MAJOR.MINOR.BUILD` versions, with
MAJOR and MINOR capped at 255 and BUILD at 65535. The cap applies only when
Msi or Exe is an enabled target format. Debian and RPM package names must
start with a letter or digit and be at least two characters long; KiteConfig
derives one from `appName` or fails, naming `desktop { linuxPackageName }` as
the escape hatch. The macOS bundle identifier follows the same reverse-DNS
rule every Apple bundle ID in KiteConfig uses: letters, digits, hyphens, and
periods, in at least two dot-separated segments.

`org.jetbrains.compose` refuses to apply without the Kotlin Compose compiler
plugin, `org.jetbrains.kotlin.plugin.compose`, on the same classpath, so both
belong in the root `plugins { }` block with `apply false`, for the same
classloader reason Kotlin and AGP already are. Supported Compose Gradle
plugin releases are `1.11.0` through `1.12.x`. `kiteDoctor` reports the
active version's compatibility.

## Kotlin/Native compiler opt-ins

Native opt-ins are off until you configure `optIns { }`. When configured,
selected native compilations receive these markers:

- `kotlinx.cinterop.ExperimentalForeignApi`
- `kotlin.experimental.ExperimentalObjCName`
- `kotlin.experimental.ExperimentalNativeApi`

`optIns { add("...") }` adds validated fully qualified marker names.
KiteConfig removes duplicates while preserving order.

Only selected KMP projects and native compilations receive these options.
KiteConfig does not add annotations to source files.
`optIns { projects(...) }` limits only this native policy. It does not
limit the shared JVM alignment from `jvm { target }`.

## Explicit Apple project changes

Apple source changes never run as part of normal compilation, linking, or
archiving. Either `autoApply = true` applies them, or you run `kiteApply` yourself.

### Xcode project file

Explicit Xcode sync can update:

- product and display names;
- bundle ID;
- marketing version;
- build number;
- project-level locale regions;
- an existing AppIcon catalog assignment.

KiteConfig follows `PBXNativeTarget` application entries through their
configuration-list IDs to exact `XCBuildConfiguration` entries. It can select
one or more named application targets, but it refuses to assign one bundle ID
to multiple app targets.

There is no global build-settings fallback. Missing targets, malformed graph
links, missing expected settings, duplicate objects, garbage input, and parser
uncertainty stop the complete plan. Text replacement handles special
characters without regex replacement errors.

### Source Info.plist

Plist sanitization runs when you set
the facts you declared. The sanitizer supports source XML
property lists only. It does not convert binary, OpenStep, or generated plists.

It:

- enables secure XML processing;
- disables external entities and DTDs;
- rejects unsafe declarations;
- rejects duplicate or malformed root dictionary entries;
- rejects input larger than 4 MiB when measured as UTF-8;
- requires a lossless baseline round trip;
- can add KiteConfig build-setting references;
- can manage `ITSAppUsesNonExemptEncryption` through
  `ios.rewrite.nonExemptEncryption`;
- can manage `CADisableMinimumFrameDurationOnPhone` through
  `ios.rewrite.proMotion`.

Managed keys carry the value you declared. Every other key is left alone:

| Policy | Result |
|---|---|
| `FAIL` | Stop and preserve the original file. This is the default. |
| `KEEP` | Preserve the conflicting value and report a warning. |
| `REPLACE` | Allow explicit replacement. |

A failure produces no partial plist rewrite.

### Shared-module references

Shared-module migration runs only when you call
`ios { renameSharedModule(from = "OldShared", to = "Shared") }`. Both
names are required. KiteConfig does not infer the old module from a Podfile.

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
  unless installing the icon claims that path, which takes a backed-up
  takeover;
- replaces or removes later files only when their ownership checksums still
  match.

### Apple AppIcon

Apple icon installation requires:

- iOS still selected;
- a configured `logo { }` block;
- `logo.foreground`, a PNG;
- exactly one of `logo.background` or `logo.backgroundColor`;
- an `IPHONEOS_DEPLOYMENT_TARGET` of 12.0 or newer in the Xcode project, when
  the project states one;
- Xcode 14 or newer.

The installer creates an opaque 1024 by 1024 composite and a universal
`Contents.json`. It creates only the default universal appearance. Dark and
Tinted appearances and Icon Composer files remain outside this installer.

Input decoding and output paths are bounded and validated. With backups enabled,
first-contact recovery is stored below `.kiteconfig/recovery`, outside `build/`,
so `clean` does not erase it. Checksum ownership is always enforced.
Unreferenced PNG files are reported but not deleted.

### Ownership state, and whether to commit it

The installers write into your source tree, so each one keeps a small receipt
under `.kiteconfig/`: the list of files it created, with a checksum for each.
That is how a rewrite tells its own output apart from a file you wrote by hand.
A checksum that still matches may be replaced; anything else is left alone.

**Commit these files.** They are small, they are plain text, and they travel with
the outputs they describe. Without them a fresh clone has the generated icons but
no proof of where they came from, so `kiteDoctor` reports the ownership manifest
as missing (`KTCNFG022`, `KTCNFG031`) and a rewrite refuses to touch files it
cannot vouch for. Both are the safe answer, but on a checkout that is actually
fine they read as a failure.

Recovery backups under `.kiteconfig/recovery` are a different matter: they are
local safety copies of your pre-existing files, they can be large, and they are
not worth committing.

```gitignore
.kiteconfig/recovery/
```

### Legacy Android icon takeover

Installing a launcher icon claims the paths it occupies, taking over known legacy
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

## Launch screens

`splash { }` delivers a launch screen from one block. Opening the block is the
switch; it flows to every platform unless you stop it.

```kotlin
splash {
    image = layout.projectDirectory.file("art/splash.png")   // default: logo { foreground }
    backgroundColor = "#101014"                              // default: logo { backgroundColor }
    dark {
        image = layout.projectDirectory.file("art/splash-dark.png")
        backgroundColor = "#000000"
    }
    android { theme = "Theme.App.Splash" }
}
```

| Member | Default | Notes |
| --- | --- | --- |
| `image` | `logo { foreground }` | Art for the launch screen. |
| `backgroundColor` | `logo { backgroundColor }` | Background behind the art. |
| `dark { image }`, `dark { backgroundColor }` | unset | Dark-mode variant. |
| `android { theme }` | unset | Theme name for the generated Android 12 splash. |

Android and desktop splashes are generated into `build/`. The iOS delivery
writes `UILaunchScreen` and asset-catalog entries into your Xcode project, so it
is applied with the rest of the iOS plan. Diagnostics are `KTCNFG090` to `KTCNFG095`.

## Diagnostics

| Task | Behavior |
|---|---|
| `kiteVerify` | Prints the best available resolved values and selected paths. It does not change files. |
| `kiteDoctor` | Runs resilient setup checks and reports findings. Findings do not fail the task. |
| `kiteCheck` | Uses the same checks, writes deterministic JSON or SARIF, then fails on errors or optionally on warnings. |
| `kitePlan` | Reports selected operations, paths, targets, and policies without changing files. |

Configure the strict CI task from the root build:

```kotlin
import io.github.yuroyami.kiteconfig.KiteConfigCheckTask
import io.github.yuroyami.kiteconfig.KiteConfigDiagnosticReportFormat

tasks.named<KiteConfigCheckTask>("kiteCheck") {
    reportFormat.set(KiteConfigDiagnosticReportFormat.SARIF)
    reportFile.set(layout.buildDirectory.file("reports/kiteconfig/diagnostics.sarif"))
    failOnWarnings.set(true)
}
```

Stable diagnostic families are:

| IDs | Area |
|---|---|
| `KTCNFG001` to `KTCNFG003` | Android app label and launcher-icon references |
| `KTCNFG010` to `KTCNFG012` | Source Info.plist references and Apple bundle-name compatibility |
| `KTCNFG020` to `KTCNFG024` | Xcode target selection, AppIcon state, catalog selection, and deployment compatibility |
| `KTCNFG030` to `KTCNFG031` | Android resource and icon state |
| `KTCNFG040` | Locale canonicalization |
| `KTCNFG050` | Build-number derivation |
| `KTCNFG060` to `KTCNFG062` | KGP visibility and active AGP or KGP compatibility |
| `KTCNFG070` to `KTCNFG071` | Exact Android project and Xcode target selectors |
| `KTCNFG080` to `KTCNFG087` | Desktop identity, icons, Compose plugin compatibility, application selection, bundle identifier, package version, and Linux or Windows packaging names |
| `KTCNFG090` to `KTCNFG095` | Splash delivery, automatic application, and Xcode-driven build timing |
| `KTCNFG901` to `KTCNFG952` | Provider, path, and input-fingerprint resolution |
| `KTCNFG960` to `KTCNFG967` | Splash, version-guard, and auto-rewrite provider resolution |
| `KTCNFG999` | Unexpected diagnostic-engine failure |

`KTCNFG011` reports a managed plist key that does not yet carry the declared value. It is a
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
  `.kiteconfig.bak` copy.
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

KiteConfig does not currently provide:

- Gradle Isolated Projects support;
- per-flavor, per-build-type, or per-Xcode-target identity overlays;
- xcconfig generation or automatic Xcode include wiring;
- generated or binary Info.plist conversion;
- automatic project-directory or `settings.gradle` renames;
- Node.js or wasm workers;
- SVG or vector icon input;
- Dark or Tinted Apple icon variants;
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
