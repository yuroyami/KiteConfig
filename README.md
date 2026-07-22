# kmp-ssot

`kmp-ssot` is a root-applied Gradle plugin for declaring one Kotlin Multiplatform
application model and adapting it to Android, Apple projects, generated runtime
configuration, and narrowly selected Kotlin targets.

Its safety boundary is intentional:

- ordinary builds configure Gradle/AGP/KGP and may generate files only below
  `build/generated/kmpssot`;
- Xcode projects, plists, Podfiles, Swift files, and launcher assets change only
  when you run an explicitly named `kmpSsot*` migration or install task;
- ambiguous apps, projects, Xcode targets, paths, ownership, and parser results
  fail closed instead of widening scope.

The plugin is available from the
[Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.yuroyami.kmpssot).

## Mental model

The implementation has five layers:

| Layer | Responsibility |
|---|---|
| Model | Provider-backed root DSL, validation, derived IDs/version codes, and canonical BCP-47 locales |
| Adapters | AGP application/library configuration and selected KGP compiler policy |
| Generators | Owned `commonMain` BuildConfig and selected browser-target worker source below `build/` |
| Checks | Human reports, stable diagnostic IDs, JSON/SARIF CI reports, and a read-only mutation plan |
| Migrations/installers | Explicit, scoped, ownership-checked changes with configurable durable recovery |

The model is not a mutable global dictionary for subprojects. Configure it once
at the root, then let adapters consume it or expose runtime values through the
generated BuildConfig object.

## Compatibility contract

| Component | Supported contract and evidence |
|---|---|
| Gradle | 8.5 or newer. Applying on an older Gradle fails immediately. Real consumer fixtures exercise standalone application and KGP 2.4.0 integration on the 8.5 floor; CI also proves strict configuration-cache reuse on the current wrapper line. |
| JVM running Gradle | Java 17 or 21 in CI. The plugin is built with a Java 21 toolchain and emits Java 17 bytecode. |
| Kotlin Gradle plugin | Stable 2.4.x releases. A published-plugin TestKit consumer compiles source against a generated fields-only BuildConfig with KGP 2.4.0 on Gradle 8.5 and the current wrapper. KGP's numeric `-release-N` stable implementation metadata is recognized; RC, Beta, dev, and arbitrary suffixes remain rejected. |
| Android Gradle plugin | 8.5.2 through 9.1.x. The implementation compiles against 9.1.1 and rejects requested Android integrations below 8.5.2 or at 9.2+. |

Declare KGP and AGP versions in the root `plugins` block with `apply false` when
subprojects use them. That gives the root plugin access to their typed APIs; a
requested integration fails with guidance if the classes are isolated in a
sibling classloader.

The root aggregation design configures other projects and is not compatible
with Gradle Isolated Projects. Configuration cache support is verified
separately.

## Install and declare the model

```kotlin
// root build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.0" apply false
    id("com.android.application") version "9.1.1" apply false
    id("io.github.yuroyami.kmpssot") version "<version>"
}

kmpSsot {
    appName = "Jetzy"
    versionName = "1.4.0"
    bundleIdBase = "com.example.jetzy"

    // Apple release numbers are independent from Android's versionCode.
    iosMarketingVersion = "1.4.0" // defaults to versionName when present
    iosBuildNumber = "42"

    // Exact Gradle project selectors are preferred over directory-name guesses.
    sharedProjectPath = ":shared"
    androidApplicationProjects.add(":androidApp")

    android {
        compileSdk = 36
        minSdk = 26
        targetSdk = 36
        publishedVersionCode = 1_001_003_999 // optional offline release guard
    }

    locales.set(listOf("en", "en-US", "fr"))
}
```

All identity values are optional. An identity value reaches an Android app only
when both its value exists and its `propagate*` toggle is enabled. SDK values
similarly apply only when present. Source-tree behavior has an additional,
explicit opt-in described below.

### Selectors and typed paths

Use selectors whenever a build has more than one plausible destination:

```kotlin
kmpSsot {
    sharedProjectPath = ":shared"
    androidApplicationProjects.add(":androidApp")
    interopProjectPaths.add(":shared")

    composeResourcesDirectory.set(
        layout.projectDirectory.dir("shared/src/commonMain/composeResources")
    )
    androidAppDirectory.set(layout.projectDirectory.dir("apps/android"))

    iosPbxprojFile.set(
        layout.projectDirectory.file("apps/ios/MyApp.xcodeproj/project.pbxproj")
    )
    iosInfoPlistFile.set(layout.projectDirectory.file("apps/ios/MyApp/Info.plist"))
    iosPodfileFile.set(layout.projectDirectory.file("apps/ios/Podfile"))
    iosAppDirectory.set(layout.projectDirectory.dir("apps/ios"))
    iosAppIconDirectory.set(
        layout.projectDirectory.dir("apps/ios/MyApp/Assets.xcassets/AppIcon.appiconset")
    )

    ios {
        deploymentTarget = "12.0" // required for the Xcode 14+ universal AppIcon installer
        // Empty is safe only when the pbxproj has exactly one application target.
        targetNames.add("MyApp")
    }
}
```

`androidApplicationProjects`, `sharedProjectPath`, `interopProjectPaths`, and
`web.projectPaths` use absolute Gradle paths such as `:shared`. Multiple detected
Android applications require an explicit selection only when an enabled
app-scoped value needs a destination. Native interop opt-ins and generated source
have independent, explicit scopes. By contrast, setting root `javaVersion` is
deliberately root-global toolchain policy: it configures Java compatibility in
every compatible classic Android module and aligns every detected Kotlin/JVM
compile task in projects applying Kotlin Multiplatform, Kotlin/JVM, or Kotlin
Android. `androidApplicationProjects`, `sharedProjectPath`, and
`interopProjectPaths` do not restrict that alignment.

The Android logo installer has one typed `androidAppDirectory` sink, so logo
propagation accepts at most one effective Android application. With no applied
application plugin, an explicit directory (or the legacy fallback) remains
supported. Selecting multiple applications is valid for compatible
identity/locale policy only when the specific value itself can safely fan out
(one bundle ID still cannot).

Typed `DirectoryProperty`/`RegularFileProperty` paths are preferred. Legacy
string properties remain for migration, but they must be relative, contained by
the root project, and free of `.`/`..` path segments. Mutation and install tasks
also reject symlinks, special files, and paths escaping their declared root.

Configure the model during root-project evaluation. The plugin validates and
freezes it at the end of that evaluation so later cross-project writes cannot
silently change values already consumed by platform adapters.

The deprecated `Project.kmpSsot` accessor remains only for source compatibility.
It performs cross-project model access, cannot support Gradle Isolated Projects,
and must not be used to configure the model from subprojects; prefer the root DSL
and generated/read-only outputs.

## Defaults and authority

| Setting | Default | Effect |
|---|---:|---|
| Identity fields, suffixes, `versionCodeOverride`, `iosBuildNumber`, `ios.deploymentTarget`, `javaVersion` | unset | Existing platform values remain unless a value is declared. `ios.deploymentTarget` is a compatibility assertion required only for the Apple universal AppIcon installer; it does not configure Xcode. |
| `iosMarketingVersion` | `versionName` provider | Apple marketing version remains independently overridable. |
| `propagateAppName`, `propagateBundleId`, `propagateVersion`, `propagateLocaleList` | `true` | Present scalar values are authoritative. Apple `knownRegions` is deliberately additive: requested regions are ensured but unrelated existing regions are not deleted. |
| `propagateAndroidSdk` | `true` | Present `android {}` SDK values are authoritative. |
| `filterAndroidResources` | `false` | Locale metadata does not prune packaged Android resources unless explicitly requested. |
| `propagateLogo`, `propagateSharedModule`, `propagateInteropOptIns` | `false` | Branding, rename migration, and compiler policy require opt-in. |
| `syncIos`, `sanitizeIosProject`, `cleanupLegacyLogoArtifacts` | `false` | Source-tree mutation is disabled. |
| `backupBeforeRewrite` | `true` | User-owned text and eligible Apple assets are backed up before first replacement. |
| `dryRun` | `false` | Explicit migrations apply; set true for their bounded unified-style preview. It never suppresses build-owned code generation. |
| `ios.targetNames` | empty | Auto-select only a sole application target; ambiguity is an error. |
| `ios.plistConflictPolicy` | `FAIL` | Existing conflicting plist values abort the complete plist plan. |
| `web.generateIoWorker` | `false` | No browser source is generated. |
| `web.browserTargetNames`, `web.projectPaths` | empty | Browser runtime is never inferred; project scope may fall back only to the unique shared project. |
| `buildConfig.enabled` | `false` | No runtime constants object is generated. |
| `buildConfig.includeIdentity` | `true` | Enabling it requires a complete identity; set false for custom fields only. |
| `buildConfig.allowBuildCache` | `false` | Generated values are excluded from Gradle build-cache storage unless explicitly trusted. |

Android application identity is applied in AGP `finalizeDsl`, after a module's
own `android {}` block, so the declared SSOT wins. Classic and KMP-native Android
libraries receive only compatible SDK/toolchain values. Leaving a model field
unset preserves the module's value.

## Versioning

Android and Apple release numbers are intentionally separate:

- `versionName` is Android's display version. When consumed, it must be
  non-blank, control-free, and at most 255 characters.
- `versionCodeOverride` is an explicit Android store build number in
  `1..2_100_000_000`.
- when an enabled consumer needs a derived Android code and no override exists,
  `versionName` must be exactly three numeric components, each `0..999` without
  leading zeroes. The fixed-width encoding is
  `"1" + xxx + yyy + zzz`; for example, `1.2.3` becomes `1001002003`.
- `android.publishedVersionCode` is an optional offline baseline; while Android
  version propagation is active for a detected app, the resolved next code must
  be strictly greater. The plugin does not contact a store.
- during explicit iOS synchronization, `iosMarketingVersion` maps to
  `MARKETING_VERSION` and must be `x.y.z`.
- during explicit iOS synchronization, `iosBuildNumber` maps to
  `CURRENT_PROJECT_VERSION`: one to three numeric components, with maximum
  widths 4/2/2 and a positive first component. It never inherits Android's
  version code.

Use `versionCodeOverride` for prerelease names, four-component versions, or a
different release scheme.

## Locales

The model stores a canonical, de-duplicated platform-resource subset of BCP-47:
a 2–3 letter language with optional script, region, and variants. Prefer values
such as `en`, `pt-BR`, and `sr-Latn`. Extensions, private-use, and grandfathered
tags are rejected because they do not map consistently to both Android resource
qualifiers and Xcode regions. Legacy Android forms such as `pt-rBR` and
`b+sr+Latn` are accepted at the boundary and canonicalized immediately.
At most 1,000 configured entries are accepted, and each raw tag is limited to
255 characters before parsing.

When `locales` is not set, discovery scans `values-*` directories below
`composeResourcesDirectory`, or the selected shared project's conventional
`src/commonMain/composeResources` directory. It accepts exact locale-only resource
forms such as `values-en`, `values-pt-rBR`, and `values-b+sr+Latn`. Direct
hyphenated BCP-47 forms belong in the explicit `locales` list; mixed/non-locale
directories such as `values-pt-BR`, `values-en-night`, `values-land`, and
`values-v26` are ignored. Discovery is shallow, does not follow links, and
refuses more than 10,000 immediate resource entries.

Canonical locales are added to Apple `knownRegions` during explicit iOS sync;
`Base` and unrelated existing regions are preserved because this task does not
own or validate every `.lproj` resource. They do **not** automatically restrict
Android resources. Set
`filterAndroidResources = true` to opt into application-level packaging filters;
the plugin replaces the selected application's effective AGP locale-filter set
with the canonical list via AGP 9 `localeFilters` or the compatible AGP 8
qualifier form. On AGP 8, unrelated legacy resource configurations such as
density, ABI, or the ambiguous `car` UI-mode qualifier are preserved; a `car`
locale is emitted unambiguously as `b+car`. An empty list is rejected, and libraries
are not pruned.

## Android adapters

For a selected `com.android.application`, the app-scoped adapter can set:

- `applicationId`, `versionName`, `versionCode`;
- `manifestPlaceholders["appName"]`;
- optional application locale filters.

Independently of that selector, every compatible classic Android module can
receive configured `compileSdk`, `minSdk`, `ndkVersion`, Java source/target
compatibility, and matching Kotlin JVM target. Configured `targetSdk` applies to
application modules only.

Use the placeholder in the app manifest:

```xml
<application android:label="${appName}" />
```

Classic `com.android.library` modules receive compatible SDK and JVM values but
not app identity or locale pruning. `com.android.kotlin.multiplatform.library`
receives `compileSdk` and `minSdk`; that DSL has no `targetSdk` or `ndkVersion`.

## BuildConfig generation

BuildConfig is a typed public-client-configuration generator for the selected
shared project's `commonMain`:

```kotlin
kmpSsot {
    sharedProjectPath = ":shared"
    buildConfig {
        enabled = true
        packageName = "com.example.generated"
        className = "BuildConfig"
        includeIdentity = true
        allowBuildCache = false

        stringField("BASE_URL", "https://api.example.com")
        intField("API_TIMEOUT_MS", 30_000)
        longField("CACHE_BYTES", 5_000_000L)
        booleanField("ANALYTICS_ENABLED", true)
        doubleField("SAMPLE_RATE", 0.25)
    }
}
```

The task `:shared:generateKmpSsotBuildConfig` owns
`build/generated/kmpssot/commonMain/kotlin` and wires that directory to
`commonMain`. Duplicate names, invalid identifiers, arbitrary Kotlin fragments,
non-finite doubles, and collisions with identity fields are rejected. Generation
is bounded to 512 custom fields, 10,000 characters per String value, 65,536
characters per legacy transport entry, and 1,048,576 transport characters in
total. Integer extrema are emitted as `Int.MIN_VALUE` and `Long.MIN_VALUE`, so
their source representation remains valid Kotlin.

This is **not a secret store**. A provider can keep a value out of the build
script, but the resolved value still enters generated source, task inputs, build
scans, KLIBs, application binaries, and—if explicitly enabled—build caches. Do
not put credentials or signing material here.

## Browser worker generation

The optional worker is intentionally browser-only and explicitly scoped:

```kotlin
kmpSsot {
    sharedProjectPath = ":shared"
    web {
        generateIoWorker = true
        projectPaths.add(":shared")
        browserTargetNames.add("js") // exact Kotlin/JS target name
        ioWorkerPackage = "com.example.generated"
    }
}
```

For target `js`, `:shared:generateKmpSsotIoWorkerJs` emits a single-shot
`kmpSsotOffload` helper into
`build/generated/kmpssot/jsMain/kotlin`. Custom target names produce matching
task/source-set names. The consumer must provide `kotlinx-coroutines-core`.

The helper validates browser Worker/Blob APIs, applies a 30-second default
timeout, and terminates on completion, failure, timeout, or coroutine
cancellation. It does not support Node.js-only or `wasmJs` targets. `jobJs` is
trusted executable JavaScript—not data—and must never be constructed from user
input. A deployed Content Security Policy must allow Blob workers, normally with
`worker-src blob:`.

## Native interop compiler policy

`propagateInteropOptIns = true` adds these markers only to native compilations in
the selected `interopProjectPaths` (or the uniquely selected shared project):

- `kotlinx.cinterop.ExperimentalForeignApi`
- `kotlin.experimental.ExperimentalObjCName`
- `kotlin.experimental.ExperimentalNativeApi`

Add validated fully qualified names through `extraOptIns`. This is compiler
policy, so it is disabled by default and never edits source. This selector scopes
only the Native opt-ins; it does not scope the root-global Kotlin/JVM alignment
activated by `javaVersion`.

## Explicit Apple migrations

Apple source synchronization is never attached to link, archive, or ordinary
build tasks. First configure exact paths/targets, opt in, inspect, and invoke it:

```kotlin
kmpSsot {
    syncIos = true
    sanitizeIosProject = true

    ios {
        targetNames.add("MyApp")
        usesNonExemptEncryption = false
        proMotion120Hz = true
        plistConflictPolicy =
            io.github.yuroyami.kmpssot.PlistConflictPolicy.FAIL
    }
}
```

`kmpSsotSyncIosConfig` walks the pbxproj object graph and changes build settings
only for the selected application target configurations. An empty selector is
accepted only for exactly one application target. Missing graph links, malformed
settings, no application target, or ambiguity abort the plan—there is no global
fallback. Locale `knownRegions` remains project-level.

`kmpSsotSanitizeIosProject` supports source XML plists. It uses hardened XML
parsing, rejects duplicate/malformed/unsafe input or content over 4 MiB of UTF-8,
and requires a lossless baseline round trip. Conflict policies are:

- `FAIL`: abort and preserve the file byte-for-byte;
- `KEEP`: preserve the conflicting value and warn;
- `REPLACE`: explicitly authorize replacement.

It can manage the `$(PRODUCT_NAME)`, `$(MARKETING_VERSION)`, and
`$(CURRENT_PROJECT_VERSION)` references plus the two optional boolean flags.
Binary/OpenStep and Xcode-generated plists are not converted; configure their
build settings instead.

### Explicit shared-module reference migration

The plugin does not rename a directory and does not infer an old module from a
Podfile. After performing the Gradle project rename yourself, declare both ends:

```kotlin
kmpSsot {
    syncIos = true
    propagateSharedModule = true
    iosPreviousSharedModuleName = "shared"
    iosSharedModuleName = "composeApp"
}
```

The iOS config task then updates at most one exact local-pod declaration and
plain exact Swift `import shared` statements under the selected iOS tree.
Vendored, dependency, build, checkout, and symlink trees are skipped. Qualified,
testable, implementation-only, bridging-header, and same-prefix imports are not
rewritten. Comment, string, raw-string, and extended-regex contents are masked;
unterminated lexical regions abort the complete migration rather than widening it.

Run `pod install` yourself after reviewing a CocoaPods migration.

## Logo installers and ownership

Set `propagateLogo = true`, a foreground PNG, and exactly one background PNG or
`#RRGGBB`/`#AARRGGBB` color. The Apple installer additionally requires
`syncIos = true`; the Android installer does not. PNGs are decoded with
strict limits of 32 MiB, 4,096 pixels per dimension, and 16,777,216 decoded
pixels; inputs cannot live inside the output tree.

These tasks install assets, while strict diagnostics prove that the selected
applications consume them. The Android application manifest remains user-owned;
it must reference the generated names (including `roundIcon` when the application
uses one):

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round" />
```

The selected Xcode application target must likewise declare the catalog named by
`iosAppIconDirectory`—normally `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` for
the default `AppIcon.appiconset`. `kmpSsotSyncIosConfig` aligns an existing
assignment in every selected application configuration and fails closed when the
setting is absent; it never inserts a guessed build setting. `kmpSsotDoctor` and
`kmpSsotCheck` validate both platform references (`KMPS003`, `KMPS021`, and
`KMPS024`). Verify the merged Android manifest and the selected target's effective
Xcode build settings as the final application-level check.

`kmpSsotSyncAndroidLogo` installs adaptive and legacy launcher images at all
densities. The foreground is aspect-contained within the configurable safe zone
(default `66/108`); backgrounds aspect-cover the canvas. When the SSOT
`android.compileSdk` is explicitly 33 or newer, v33 wrappers reuse the foreground
as Android's supported monochrome layer. See Android's [adaptive-icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).
Existing unowned files at requested paths or same-stem Android Studio WebP icons
cause a failure.

Set `cleanupLegacyLogoArtifacts = true` to authorize reversible takeover of
legacy/colliding Android icon files and, on first contact, unowned paths the
current installer will claim. `kmpSsotSyncAndroidLogo` renders and validates the
complete replacement first, then backs up, removes, and installs as one
rollback-capable operation. `kmpSsotCleanupLegacyAppLogoArtifacts` exposes only
the backup/removal half for an explicitly requested recovery workflow. It shares
the installer's ownership lock and never removes current manifest-owned outputs.

`kmpSsotSyncIosLogo` installs an opaque 1024×1024 composite and universal
`Contents.json`. This single-size catalog requires Xcode 14 or newer and an
explicit `ios.deploymentTarget` of at least 12.0. That property asserts
compatibility; it does not write Xcode's `IPHONEOS_DEPLOYMENT_TARGET`. With the
default `backupBeforeRewrite = true`, first contact backs up existing outputs
below the durable `.kmpssot/recovery/ios-appicon` tree, which `clean` does not erase. Android
takeover uses `.kmpssot/recovery/android-logo`. Archive or commit these verified
recovery records until the migration is accepted. Unreferenced icon PNGs are
reported, not silently deleted.

This installer emits only the universal default appearance. It does not generate
the optional Dark/Tinted appearances or an Icon Composer file; keep those assets
under a separate workflow. Apple documents the current single-size and appearance
options in [Configuring your app icon using an asset catalog](https://developer.apple.com/documentation/xcode/configuring-your-app-icon/).

Both installers maintain checksum ownership manifests. Later runs replace or
delete only unchanged files the manifest proves the plugin owns. Manual changes,
unowned targets, symlinks, and path escapes fail closed.

Commit those small ownership manifests together with the installed icon assets:
they are required provenance, not disposable build output. A fresh clone without
them correctly treats committed icons as unowned. Empty coordination lock files
below `.kmpssot` are ignored and must not be committed.

## Diagnostics and task reference

Start with the read-only tasks:

```bash
./gradlew kmpSsotVerify
./gradlew kmpSsotDoctor
./gradlew kmpSsotCheck
./gradlew kmpSsotPlan
```

| Task | Behavior |
|---|---|
| `kmpSsotVerify` | Prints resolved model values and selected path presence; never mutates files. |
| `kmpSsotDoctor` | Runs the resilient aggregate checks and reports PASS/SKIP/WARN/FAIL without gating on findings. |
| `kmpSsotCheck` | Writes deterministic JSON by default, then fails on ERROR findings; can emit SARIF and fail on warnings. |
| `kmpSsotPlan` | Prints enabled operations, exact selectors/paths, policies, and available change summaries without mutation. |
| `kmpSsotSanitizeIosProject` | Applies the opted-in source XML plist plan. |
| `kmpSsotSyncIosConfig` | Applies the optional plist, selected pbxproj, and optional Podfile/Swift text migration as one recoverable batch. |
| `kmpSsotSyncIosLogo` | Installs the opted-in Apple app icon. |
| `kmpSsotSyncAndroidLogo` | Installs the opted-in Android launcher-icon tree. |
| `kmpSsotCleanupLegacyAppLogoArtifacts` | Backs up and removes selected legacy/colliding files and, on first contact, unowned paths the current installer will claim. |

There is deliberately no “apply everything” aggregate: text and binary platform
installers have different ownership and rollback domains. Invoke each reviewed
plan explicitly so a later unrelated installer cannot turn an earlier success
into a misleading partial global sync.

The default check report is
`build/reports/kmpssot/diagnostics.json`. Configure CI output in the root build:

```kotlin
import io.github.yuroyami.kmpssot.KmpSsotCheckTask
import io.github.yuroyami.kmpssot.KmpSsotDiagnosticReportFormat

tasks.named<KmpSsotCheckTask>("kmpSsotCheck") {
    reportFormat.set(KmpSsotDiagnosticReportFormat.SARIF)
    failOnWarnings.set(true)
}
```

Diagnostics expose stable IDs:

| IDs | Area |
|---|---|
| `KMPS001`–`KMPS003` | Android manifest app-name and launcher-icon reference contracts |
| `KMPS010`–`KMPS012` | source Info.plist references and Apple bundle-name compatibility |
| `KMPS020`–`KMPS024` | pbxproj target selection, Apple app-icon state/selection, and deployment compatibility |
| `KMPS030`–`KMPS031` | Android resource/icon state |
| `KMPS040` | locale canonicalization |
| `KMPS050` | monotonic Android version derivation |
| `KMPS060`–`KMPS062` | KGP classloader visibility and active AGP/KGP compatibility |
| `KMPS070`–`KMPS071` | exact Android project and iOS target selector validity |
| `KMPS901`–`KMPS940` | provider/path/input-fingerprint resolution failures |
| `KMPS999` | unexpected diagnostic-engine failure |

`KMPS011` honors `ios.plistConflictPolicy`: `FAIL` conflicts are errors,
`KEEP` conflicts are warnings describing the intentionally preserved drift, and
`REPLACE` conflicts remain actionable errors until the explicit migration has
overwritten the source plist.

Selector findings retain the configured list exactly: ambiguous implicit app
selection and malformed, unknown, or duplicate `androidApplicationProjects`
entries report `KMPS070`, while blank, control-bearing, or duplicate
`ios.targetNames` entries report `KMPS071`.
Compatibility findings are actionable only when the corresponding typed AGP or
KGP integration is required by the detected projects and enabled features.

For a reviewable unified-style text preview, set `dryRun = true` and invoke the
selected migration task. User-owned text changes are staged before commit;
multi-file iOS migration uses locks, atomic replacement, and rollback if a later
commit fails. The default `backupBeforeRewrite = true` also creates write-once
`.kmpssot.bak` recovery copies.

## Migrating from 1.7

This unreleased safety line intentionally changes behavior. Upgrade in a clean
working tree and make these changes before running any sync task:

1. Replace directory guesses with `sharedProjectPath`,
   `androidApplicationProjects`, typed file/directory properties, and
   `ios.targetNames` where the destination is not unique.
2. Opt back into behavior that used to be broad or automatic:
   `syncIos`, `sanitizeIosProject`, `propagateLogo`,
   `propagateSharedModule`, `propagateInteropOptIns`, and
   `filterAndroidResources` now default to false.
3. Split release numbers. Keep Android `versionCodeOverride` independent, set
   `iosMarketingVersion`, and provide `iosBuildNumber` when Apple build-number
   propagation is wanted.
4. Change derived versions to exact `x.y.z` without leading zeroes, or set an
   explicit `versionCodeOverride`. Optionally set
   `android.publishedVersionCode` to guard monotonic releases.
5. Canonicalize locale configuration to BCP-47. Android resource filtering is a
   separate opt-in and no longer prunes libraries.
6. For shared-module reference migration, set both
   `iosPreviousSharedModuleName` and `iosSharedModuleName`; automatic Podfile
   inference is removed. The legacy `oldSharedModuleName`/`sharedModule` pair is
   retained only as a compatibility fallback.
7. For worker generation, select exact `web.projectPaths` and
   `web.browserTargetNames`. Node and wasm targets are rejected.
8. Rename direct task invocations:

   | 1.7 task | Current task |
   |---|---|
   | `sanitizeIosProject` | `kmpSsotSanitizeIosProject` |
   | `syncIosConfig` | `kmpSsotSyncIosConfig` |
   | `syncIosLogo` | `kmpSsotSyncIosLogo` |
   | `syncAndroidLogo` | `kmpSsotSyncAndroidLogo` |
   | `cleanupLegacyAppLogoArtifacts` | `kmpSsotCleanupLegacyAppLogoArtifacts` |

9. Run `kmpSsotVerify`, `kmpSsotDoctor`, `kmpSsotCheck`, and `kmpSsotPlan`.
   Then use `dryRun = true` with only the migration you intend to execute.

BuildConfig caching is now denied by default. If the generated object contains
only public client configuration and every configured cache is trusted, opt in
with `allowBuildCache = true`.

## Boundaries

The plugin does not provide per-flavor identities, per-Xcode-target identity
overlays, xcconfig generation, automatic Gradle-directory renames, generated
Info.plist conversion, store API integration, signing configuration, secret
storage, Node workers, or wasm workers. It does not run `pod install`.

Those boundaries keep the central model deterministic and prevent the plugin
from becoming an implicit release system.

## Contributing

```bash
./gradlew clean build validatePlugins \
  --configuration-cache --configuration-cache-problems=fail
```

CI runs compatibility jobs on Linux, macOS, and Windows and checks that an
ordinary build does not modify tracked source. Release publication requires an
exact `v<version>` tag matching the artifact version and changelog. The release
workflow first builds an unprivileged, immutable unsigned candidate. That job
runs the real AGP matrix, proves strict configuration-cache reuse for the
cacheable release graph, and emits a runtime-surface CycloneDX SBOM (the plugin
currently has no published runtime dependencies). Only the protected publish
job receives signing or repository secrets and write permissions. It restages
the signed Maven repository, proves every core payload is byte-identical to the
reviewed candidate, and requires a detached signature for each payload before
publication and provenance attestation. Secret-bearing Gradle invocations run
with the configuration cache disabled in single-use daemons so credentials
cannot be persisted in a cache entry.

## License

Licensed under the [Apache License 2.0](LICENSE).
