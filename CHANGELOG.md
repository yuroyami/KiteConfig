# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions track the
Gradle Plugin Portal releases.

## [3.0.2]

### Fixed
- **The diagnostic tasks no longer crash a consumer that declares its Android
  values only in `kiteSsot { }`.** `kiteSsotVerify`, `kiteSsotDoctor`,
  `kiteSsotCheck`, and `kiteSsotPlan` used to skip the Android wiring so a
  broken model could still be reported. But AGP validates its DSL on every
  invocation, so a module with no `compileSdk` of its own failed configuration
  with AGP's "does not specify `compileSdk`" before the diagnostic could say a
  word: the guard caused the exact failure it existed to survive. The wiring
  now always runs. On a diagnostic invocation each value group is applied
  best-effort: a throwing provider is skipped and logged at info, because the
  diagnostic re-resolves the same providers and reports the failure as a
  finding, while every group that does resolve keeps AGP's validation green.
  An SSOT-only consumer can now run every task, not just builds.

### Added
- **A drift warning when a module declares a value the SSOT replaces.** The
  wiring always won silently: `compileSdk = 33` in a module while the SSOT
  says `37` builds with 37 and the module file keeps lying. Each configuring
  Android project now logs one warning naming every replaced declaration
  (`applicationId`, `versionName`, `versionCode`, `compileSdk`, `minSdk`,
  `targetSdk`) so the dead lines get deleted instead of trusted. Identical
  wording on the AGP 9 adapter, the AGP 8 adapter, and the KMP-native library
  adapter; equal values stay silent.

## [3.0.1]

An external audit (SOLAUDIT.md) reviewed the 3.0.0 source. These are the
safety-critical findings from it, with the reported behaviour reproduced as a
failing test first in every case.

### Fixed
- **`-Pkitessot.dryRun` and `-Pkitessot.backups` no longer accept a typo as
  `false`.** Both went through `String.toBoolean()`, which answers `true` only
  for `"true"`, so `-Pkitessot.dryRun=treu` turned a requested preview into a
  real source rewrite, and `-Pkitessot.backups=treu` silently switched backups
  off. Both now accept exactly `true` or `false` and fail the build otherwise.
  Validation happens when the flag is supplied rather than when it is read:
  only some tasks read `backups`, so a lazy parse accepted the typo on one
  invocation and dropped protection on the next. `-Pkitessot.color` uses the
  same parser.
- **The Android logo dry-run previews its deletions.** It listed the files it
  would write, then returned before the legacy and colliding-icon takeover set
  was assembled, so the destructive half of the operation being approved was
  invisible. Preview and transaction now read one shared list.
- **Android logo installation fails closed when no application module is
  found.** The output directory fell back to the root project, so a discovery
  or configuration mistake wrote launcher resources into the repository root,
  where nothing packages them. `kiteSsotSyncAndroidLogo` and
  `kiteSsotCleanupLegacyAppLogoArtifacts` now refuse to run unless the sink is
  a real Android application module or a directory the build named outright.

### Added
- **`modules { shared }` is now truly auto-detected.** 3.0.0 documented the
  detection but never implemented it: the wiring ran per-project, before the
  build could know how many projects apply Kotlin Multiplatform. Resolution now
  happens once every project is evaluated, when that census is complete. One
  KMP project makes it the shared module; zero or several with a shared-scoped
  feature enabled fail with the candidate list and the one line that ends the
  ambiguity. Locale auto-detection rides on it, so the three-line setup from
  the 3.0.0 notes now really does discover locales on its own. An explicit
  `modules { shared }` still beats detection.

### Changed
- **The API reference is scannable.** Every public DSL block, task, and type now
  carries a table of what it actually does: which module types receive each
  Android SDK setting, which of the two Apple version fields must change on a
  re-upload, which task each authorization block unlocks, what happens on a
  plist conflict, and the safety rails shared by every source-writing task.
  Cross-links (`@see`) and documented failure conditions (`@throws`) were added
  alongside them. 28 tables across 22 reference pages, all verified in the
  generated Dokka HTML.
- The logo block no longer claims it renders "every Apple AppIcon slot". It
  writes one `AppIcon-1024.png` plus a single-image universal `Contents.json`,
  which is what Xcode 14 and newer expand from. The old wording described a
  per-slot set the task has never produced.

## [3.0.0]

The DSL is reshaped. The engine, the safety charter, and every task name are
unchanged: ordinary builds still never touch your source, and the mutation
tasks are still explicit-only, fail-closed, backed up, and dry-runnable.

### The shape

Shared facts stay at the root. Anything Android-only or Apple-only moved into
`android { }` or `ios { }`. Feature blocks switch themselves on by being
configured, so the second flag is gone. The root went from 40 properties to 7.

```kotlin
kiteSsot {
    appName = "Jetzy"
    version = "1.4.0"
    appId   = "com.example.jetzy"
}
```

That is a complete setup for identity propagation, and the Android application
project is detected. Shared-scoped features (`buildConfig`, `nativeOptIns`,
`web`, and locale auto-detection) additionally need `modules { shared }`, which
is not detected: the shared project has to be known while KMP source sets are
still being wired, which is earlier than the point where "exactly one KMP
project" can be established.

### Added
- `scheme { v -> ... }` at the root: one build-number formula for **both**
  platforms. It reads `v.major`, `v.minor`, `v.patch`, `v.rebuild` and returns
  an `Int`. Android uses it for `versionCode`, Apple for
  `CURRENT_PROJECT_VERSION`.
- `android { rebuild = 1 }` and `ios { rebuild = 3 }`: the re-upload dial.
  Play Console keeps every uploaded `versionCode` forever, even when you
  discard the release draft, and TestFlight refuses a reused build number.
  Bump `rebuild` instead of faking a patch release.
- `ios { publishedBuildNumber }`: an offline TestFlight monotonicity guard,
  matching the existing `android { publishedVersionCode }`.
- `modules { }`, `propagate { }`, `logo { }`, `nativeOptIns { }`, and
  `ios { sync { } }` blocks.
- `ios { sync { renameSharedModule(from, to) } }` replaces a flag plus two
  properties with one call.
- Colour in the reports. `kiteSsotVerify`, `kiteSsotDoctor`, `kiteSsotCheck`, and
  `kiteSsotPlan` colour their output by severity, so failures stand out instead
  of sitting in a wall of identical grey lines. Colour is off unless a real
  terminal is attached, and `NO_COLOR`, `TERM=dumb`, and Gradle's `--console=plain`
  each disable it. Force either way with `-Pkitessot.color=true|false`.

### Changed
- **The derived `versionCode` formula.** It is now
  `1 | major(3) | minor(3) | patch(2) | rebuild(1)`, so every version owns ten
  codes. `1.4.0` still resolves to `1001004000`, but `1.4.1` moves from
  `1001004001` to `1001004010`. Every new code is **larger** than the code the
  old formula produced for the same version, so Play monotonicity survives the
  change. `patch` is now capped at 99 and `rebuild` at 9; going over fails the
  build with a message that prints the pre-3.0 formula as a one-line
  `scheme { }`.
- **iOS build numbers are written by default.** 2.x only wrote
  `CURRENT_PROJECT_VERSION` when `iosBuildNumber` was set. It now follows the
  scheme like Android does. The write still only happens inside an explicitly
  invoked sync task.
- The Apple build-number validator no longer caps the first component at four
  digits. That limit was KiteSSOT being cautious, not App Store enforcement,
  and it blocked the shared scheme.
- The AGP 8 adapter now receives an already-resolved snapshot instead of the
  extension, so both AGP lines share exactly one resolution path.
- `kiteSsotDoctor` reports the `versionCode` the build will really use,
  including a custom `scheme { }`, instead of recomputing it.
- Report layout. Key/value rows now share one computed column per section, rather
  than four hand-counted widths that never lined up. Paths inside the project
  print relative to it, so a finding no longer runs off the right of the terminal.
  A finding's `Fix:` moved onto its own indented line.
- Runtime messages now name 3.0 properties. Dozens still said things like
  `appLogoPngForeground` or `Configure iosAppiconsetPath`, the latter naming a
  property 3.0 removed outright, which sent readers looking for something that
  no longer exists. A test now scans every message for retired names, so a
  rename cannot leave stale advice behind again.

### Fixed
- `buildConfig { stringField(name, provider) }` given a provider with no value
  (a bare `providers.gradleProperty("x")` with no `-Px` passed) now fails
  naming the field, and says to add `orElse(...)` or pass a plain String.
  Gradle voids an entire `ListProperty` when one added provider is absent, so
  this used to surface as `customFields doesn't have a configured value` on
  `generateKiteSsotBuildConfig`, pointing nowhere near the field that caused it.

### Deprecated
Every pre-3.0 **root** property still works and still feeds the same model, now
with a deprecation warning naming its replacement. They are removed in 4.0.
Highlights: `versionName` to `version`, `bundleIdBase` to `appId`,
`javaVersion` to `jvmTarget`, `backupBeforeRewrite` to `backups`,
`propagateLogo` to configuring `logo { }`, `syncIos` to configuring
`ios { sync { } }`.

### Removed
- The pre-2.0 properties, which carried a deprecation warning for a full major:
  `sharedModule`, `oldSharedModuleName`, `androidAppModule`, `iosProjectPath`,
  `iosPodfilePath`, `iosInfoPlistPath`, `iosAppDir`, `iosAppiconsetPath`.
- Properties **inside** the nested blocks moved without a bridge, because you
  are already editing that block when you meet them:
  `ios { targetNames }` to `ios { sync { targets(...) } }`,
  `ios { plistConflictPolicy }` to `ios { sync { onConflict } }`,
  `ios { usesNonExemptEncryption }` and `ios { proMotion120Hz }` into
  `sync { }`, `android { ndkVersion }` to `android { ndk }`, and the four
  `web { }` worker properties into `web { ioWorker { } }`.

## [2.0.3]

### Changed
- Widened the supported Android Gradle plugin range from `8.5.2..9.2.x` to
  `8.5.2..9.3.x`, so the latest stable AGP release is accepted. The plugin now
  compiles against AGP 9.3.1, and the real consumer fixtures exercise AGP 9.3.1
  for the classic and KMP-native adapters (the AGP 8.5.2 floor fixtures are
  unchanged).

## [2.0.2]

### Fixed
- Resilient-diagnostic detection no longer looks up requested tasks in the
  `TaskContainer` nor walks their dependencies. Those lookups ran from
  `plugins.withId` callbacks, while a module's `plugins { }` block was still
  executing, and AGP 9.2's KMP-native library plugin registers its compilation
  tasks at apply time, so the lookup realized the requested compile task and
  observed the module's compile classpaths before its build script body had run.
  Real-world KMP modules then failed configuration with "Cannot mutate the
  dependencies of configuration ... after the configuration's child
  configuration ... was resolved" (and `jvmToolchain { }` writes with "The value
  for property 'languageVersion' is final"), with the failure appearing only
  when the affected module's own Android/JVM tasks were requested directly.
  Task-based detection (the aggregate-alias dependency walk) is now restricted
  to unqualified requests resolved against the root project's task container,
  guarded by the non-realizing `names` view; subproject-qualified invocations
  use plain name matching only.

## [2.0.1]

### Changed
- Widened the supported Android Gradle plugin range from `8.5.2..9.1.x` to
  `8.5.2..9.2.x`. The plugin now compiles against AGP 9.2.1, and the real
  consumer fixtures exercise AGP 9.2.1 for the classic and KMP-native adapters
  (the AGP 8.5.2 floor fixtures are unchanged).
- Compile against Kotlin Gradle plugin 2.4.10 (supported KGP range stays
  `2.4.x`).

## [2.0.0]

### Renamed
- **kmp-ssot is now KiteSSOT**, joining the Kite family of Kotlin Multiplatform
  libraries. Nothing about the charter changed: one root-applied single source
  of truth for app identity, propagated to Android and iOS.
  Migration from 1.x:
  - Plugin id: `io.github.yuroyami.kmpssot` → `io.github.yuroyami.kitessot`
  - DSL block: `kmpSsot { }` → `kiteSsot { }`
  - Task prefix and group: `kmpSsot*` / `kmp-ssot` → `kiteSsot*` / `kitessot`
  - Version properties: `kmpSsot.version` / `kmpSsot.releaseTag` →
    `kiteSsot.version` / `kiteSsot.releaseTag`
  - Maven artifact: `io.github.yuroyami:kmp-ssot` → `io.github.yuroyami:kitessot`
  - Repository: `github.com/yuroyami/kmp-ssot` → `github.com/yuroyami/KiteSSOT`
  - Backup suffix written next to rewritten iOS/Android sources:
    `.kmpssot.bak` → `.kitessot.bak`
  The 1.x line stays published and resolvable under the old id; it will receive
  no further releases.

### Breaking changes
- **Source mutation is explicit-only.** Ordinary Android/KMP/iOS build and link
  tasks no longer depend on launcher-icon, Xcode, plist, Podfile, or Swift
  migration tasks. `syncIos`, `sanitizeIosProject`, `propagateLogo`,
  `propagateSharedModule`, `propagateInteropOptIns`, and Android locale filtering
  now default off. The individual migration tasks are consistently prefixed:
  `kiteSsotSanitizeIosProject`, `kiteSsotSyncIosConfig`, `kiteSsotSyncIosLogo`,
  `kiteSsotSyncAndroidLogo`, and `kiteSsotCleanupLegacyAppLogoArtifacts`.
  The former broad `kiteSsotSync*` aggregate tasks are removed so each reviewed
  text or asset transaction is invoked within its own ownership/rollback domain.
- **Ambiguity now fails closed.** Android applications use exact
  `androidApplicationProjects`; KMP generators/compiler policy use
  `sharedProjectPath`, `interopProjectPaths`, and `web.projectPaths`; browser
  generation also requires exact `web.browserTargetNames`; iOS rewrites use
  `ios.targetNames` or auto-select only a sole application target. pbxproj
  rewriting no longer falls back to every build configuration.
- **Release numbers are platform-correct and independent.** Derived Android
  codes now require exactly `x.y.z` with `0..999` segments and no leading zeroes,
  preserving fixed-width monotonic ordering. Explicit codes are range-checked.
  Apple now uses independent `iosMarketingVersion` and `iosBuildNumber` instead
  of reusing Android's version code.
- **Shared-module reference migration is never inferred.** Set both
  `iosPreviousSharedModuleName` and `iosSharedModuleName`; the plugin no longer
  guesses the old CocoaPod from a Podfile. The previous
  `oldSharedModuleName`/`sharedModule` pair remains a compatibility fallback.
- **Locales have one canonical model.** Inputs are canonicalized and
  de-duplicated as BCP-47 before platform rendering. Android resource filtering
  is a separate `filterAndroidResources=false` opt-in and no longer prunes
  library resources.
- **BuildConfig cache storage defaults off.** Set
  `buildConfig.allowBuildCache=true` only for public client configuration and
  trusted caches.
- **Cross-project mutable access is deprecated.** Configure the root `kiteSsot {}`
  model during root evaluation; the compatibility `Project.kiteSsot` accessor is
  frozen afterward and remains unavailable to Isolated Projects.

### Added
- **Typed scope and path APIs:** `sharedProjectPath`,
  `iosSharedModuleName`, `iosPreviousSharedModuleName`,
  `androidApplicationProjects`, `interopProjectPaths`, `web.projectPaths`,
  `web.browserTargetNames`, `ios.targetNames`, `ios.deploymentTarget`,
  `composeResourcesDirectory`, `androidAppDirectory`, and typed Apple
  file/directory properties. The deployment target is a compatibility assertion,
  not an Xcode-setting writer.
- **Release guardrails:** `android.publishedVersionCode` verifies the next
  resolved Android code is greater than an explicit offline store baseline;
  runtime compatibility guards enforce Gradle 8.5+, KGP 2.4.x, and AGP
  8.5.2–9.1.x when their typed integrations are requested. Untested AGP/KGP
  prereleases are rejected rather than treated as their eventual stable release.
- **Signed, attestable publication staging:** release CI creates and verifies the
  complete PGP-signed Maven publication (implementation, marker, POMs, Gradle
  metadata, and signatures) before remote publishing. Checksums and GitHub
  provenance cover that staged repository plus both CycloneDX SBOM formats.
- **Strict diagnostics:** `kiteSsotCheck` shares the resilient doctor engine,
  writes deterministic JSON or SARIF, and fails after report creation on errors
  (or warnings when configured). Findings have stable `KMPSnnn` IDs, retain
  duplicate/invalid Android and iOS selectors, and report unsupported active
  AGP/KGP versions when an enabled integration requires them.
- **Read-only mutation planning:** `kiteSsotPlan` reports enabled operations,
  selectors, source paths, policies, and available change summaries without
  executing an installer.
- **Output provenance:** generated source, Android icons, and Apple AppIcon files
  use checksum ownership manifests and locks. Unknown or modified content is
  never deleted or overwritten.
- **Reversible logo takeover:** legacy/colliding Android icons and unowned
  first-contact installer targets are fully backed up with SHA-256 provenance
  before removal and restored if the batch fails.
- Android 13 adaptive-icon wrappers include a monochrome layer when
  `compileSdk >= 33`.
- **Release engineering hardening:** CI covers Gradle 8.5 and the current wrapper
  on JDK 17/21 across Linux, macOS, and Windows with configuration-cache reuse
  and a no-source-mutation assertion. Publishing derives the artifact version
  from an exact matching tag, validates changelog metadata, runs the real AGP
  matrix, and uploads an immutable unsigned candidate from an unprivileged job.
  The protected job restages and signs it, verifies byte-identical Maven
  payloads plus one detached signature per payload, then publishes and attests
  the complete repository. CycloneDX SBOMs describe the published runtime
  surface instead of the build/test toolchain. Checked-in dependency locks,
  SHA-256 dependency verification metadata, API validation, wrapper checksum
  verification, complete POM metadata, and snapshot-safe local versions are on.
  Configuration-cache reuse is verified before secret-bearing signing and
  publication steps, which run explicitly uncached in single-use daemons.

### Fixed
- **Generated browser worker hardening.** The protocol uses `{id, payload}` and
  `{id, ok, result|error}` envelopes; results/errors normalize safely; browser
  APIs are checked; object URLs are revoked; creation/posting errors surface;
  and success, failure, timeout, or coroutine cancellation terminates the
  single-shot worker. Node.js and wasm targets are rejected rather than receiving
  browser-only source.
- **BuildConfig source injection is closed.** The public typed field methods and
  legacy transport are parsed through a restricted literal grammar; invalid or
  duplicate identifiers, identity collisions, arbitrary Kotlin fragments, and
  non-finite numbers fail before generation. Field count/string/transport sizes
  are bounded, integer extrema receive compilable canonical literals, and
  identity inputs disappear when `includeIdentity=false`.
- **Apple rewrites are transactional and target-scoped.** Malformed/ambiguous
  pbxproj graphs, missing expected settings, unsafe paths, symlinks, concurrent
  changes, duplicate plist keys, unsafe XML, and non-lossless plist baselines
  abort without partial writes. The plist budget is measured as 4 MiB of UTF-8,
  and Swift import migration masks extended multiline regex literals as well as
  comments/strings. Multi-file commit failures trigger rollback.
- **Concurrent edits fail closed.** Text and owned-output commits verify the
  exact bytes and ownership snapshots used to create their plans immediately
  before mutation; rollback touches only paths the current transaction changed.
- **pbxproj parsing validates the project graph.** The root dictionary,
  `objects`, `rootObject`, `PBXProject`, target configuration lists, and
  `knownRegions` must be structurally unique and syntactically complete. Garbage
  tokens and malformed duplicate entries are rejected instead of filtered.
- **Logo installers no longer pretend source-tree outputs are cache artifacts.**
  They are non-cacheable installers that validate current ownership, paths, PNG
  bounds, collisions, backup state, and orphan state on every invocation.
- **Installer namespaces and diagnostics are ownership-aware.** Apple AppIcon
  manifests/backups are keyed by the catalog's project-relative identity, so
  same-named catalogs cannot collide; Android and Apple checks verify the exact
  expected manifest, file set, and checksums for every selected application.
- **Classic AGP compatibility is tested against published consumers.** A
  floor-compiled package-private adapter preserves AGP 8 binary compatibility,
  while the AGP 9 implementation uses current typed APIs. Application selectors
  gate only app-scoped values; compatible SDK/JVM policy still reaches other
  applications and libraries. AGP 8 locale replacement preserves unrelated
  density, ABI, and other legacy resource configurations, including the
  locale-shaped `car` UI-mode token; the `car` locale is emitted as `b+car`.
- **Root-project and classloader coverage.** Root modules participate in project
  discovery; missing AGP/KGP visibility now fails requested features with the
  exact root `plugins { ... apply false }` remedy instead of warning and
  continuing.
- **Validation is comprehensive:** application/bundle IDs, Apple versions,
  project/relative paths, SDK relationships, NDK syntax, Java levels, opt-in
  markers, bounded locale tags/lists, selector duplicates, and logo configuration
  fail with actionable messages.
- **Locale discovery is qualifier-safe.** Only exact locale-only Compose resource
  directories are auto-detected; direct BCP-47 and mixed/non-locale qualifier
  names can no longer be misread as a script or variant.
- **Integration contracts are explicit in the public docs.** Native opt-in
  selectors are distinguished from the root-global Kotlin/JVM alignment applied
  by `javaVersion`; branding docs now state the Android manifest and Xcode
  app-icon catalog selections required to consume installed assets; and plist
  task KDoc describes its conditional, independently gated entries.
- **Branding consumption is now release-checkable.** Diagnostics verify that the
  selected Android manifest consumes `ic_launcher`/`ic_launcher_round`; the iOS
  source transaction aligns an existing `ASSETCATALOG_COMPILER_APPICON_NAME`
  assignment with `iosAppIconDirectory` and fails closed instead of guessing when
  that setting is absent.

## [1.7.0]

The entries below describe the behavior shipped by that historical release.
The Unreleased section above intentionally supersedes several of these contracts.

### Fixed
- **pbxproj rewrites are now target-scoped**: identity keys (`PRODUCT_NAME`,
  `PRODUCT_BUNDLE_IDENTIFIER`, `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`,
  `INFOPLIST_KEY_*`) are rewritten only inside the **application target's** build
  configurations, so unit-test targets and app extensions keep their own names and
  distinct bundle ids. Previously every target was overwritten with the app's
  values, breaking test-bundle linkage and producing App-Store-rejectable
  extension bundle ids. Falls back to a global rewrite (with a warning) when no
  application target is found.
- **`GenerateIoWorkerTask` is now cacheable**: the plugin previously failed its
  own `validatePlugins` (the task carried no cacheability annotation).
- **Java-17 bytecode**: the plugin is compiled on JDK 21 but emits Java-17
  bytecode, so a consumer whose Gradle daemon runs on JDK 17 can load it (it was
  shipping Java-21 bytecode against a documented "JDK 17+").
- **`versionCodeOverride` without `versionName`** now writes the Android
  `versionCode` and iOS `CURRENT_PROJECT_VERSION`. It was a silent no-op on both
  platforms.
- **Shared-module auto-detect refuses to guess** when a Podfile has more than one
  local dev-pod, instead of renaming the first (possibly wrong) pod and rewriting
  its Swift imports. Set `oldSharedModuleName` to proceed.
- **Android launcher-icon template collisions**: the sync warns about template
  `ic_launcher.webp` (and friends) that collide with the generated `.png` and fail
  the AAPT2 merge; `cleanupLegacyAppLogoArtifacts` now removes them.
- **iOS `Contents.json` is backed up** before the app-icon sync overwrites it, and
  orphaned icon PNGs the new catalog no longer references are flagged.
- **Region-qualified locales map to the Apple form** for `knownRegions`
  (`pt-rBR` → `pt-BR`, `b+sr+Latn` → `sr-Latn`), and non-locale `values-*` dirs
  (`night`, `v26`, `land`) are excluded from auto-detection.
- **Non-CocoaPods iOS projects sync from Gradle**: the iOS sync now hooks plain
  `linkReleaseFrameworkIos*` / `assemble*XCFramework` tasks, not just `linkPod*`.
- Changing `web { ioWorkerPackage }` no longer leaves a stale generated file
  (duplicate `kmpSsotOffload`); the generated Blob worker revokes its object URL;
  `ioWorkerPackage` rejects Kotlin hard-keyword segments; plist inserts no longer
  leave a blank line; `writeAtomically` sweeps stale temp files; root-only
  enforcement throws a `GradleException`; logo validation is gated on
  `propagateLogo`.

### Added
- **Runtime `buildConfig` codegen**: `kmpSsot { buildConfig { enabled = true } }`
  generates a typed constants object into the shared module's `commonMain`,
  readable from every KMP source set with no `expect/actual`. This is a single-plugin
  **buildKonfig replacement** for the common case. The object carries the identity
  SSOT (appName, versionName, versionCode, androidApplicationId, iosBundleId,
  locales) plus your own `stringField` / `intField` / `longField` / `booleanField`
  / `doubleField` declarations (each also accepts a `Provider<String>` for lazy
  non-secret configuration). Generated constants are compiled into consumer
  artifacts and therefore must never contain secrets. The object name is
  configurable via `className` (default `BuildConfig`), the package via
  `packageName`, and identity inclusion via `includeIdentity`. Default off.
  Deliberately flat: no per-flavor or per-target value overlays.
- **`kmpSsotDoctor`**: a read-only end-to-end setup diagnostic (manifest
  placeholder, Info.plist SSOT refs, pbxproj application target, appiconset, icon
  collisions, locale sanity, versionCode derivability, KGP visibility).
- **Aggregate tasks**: `kmpSsotSync`, `kmpSsotSyncIos`, `kmpSsotSyncAndroid`.
- **Kotlin `jvmTarget`** is set alongside `javaVersion`, eliminating the
  "Inconsistent JVM-target compatibility" error.
- `kmpSsotVerify` now also reports the Android SDK levels, `javaVersion`, the
  interop/web toggles, and the logo configuration.

### Changed (behaviour)
- **Classic Android modules are now SSOT-authoritative.** `com.android.application`
  and `com.android.library` wiring moved to AGP's `finalizeDsl`, so a value in
  `kmpSsot { }` overrides a module-local `applicationId` / `versionName` /
  `compileSdk`, matching the KMP-native library path. Leave a field unset in
  `kmpSsot { }` to keep the module's own value. (Previously module-local values
  won for these two plugins.)
- **Application locale propagation uses AGP 9 `androidResources.localeFilters`**
  with a runtime fallback to the deprecated `resourceConfigurations` for AGP 8.

## [1.6.0]

### Added
- **Interop opt-in propagation**: `kmpSsot { propagateInteropOptIns = true }`
  (default on) adds the cinterop / Obj-C opt-in markers
  (`kotlinx.cinterop.ExperimentalForeignApi`,
  `kotlin.experimental.ExperimentalObjCName`,
  `kotlin.experimental.ExperimentalNativeApi`) to **every Kotlin/Native
  compilation**, so call sites no longer each need an `@OptIn`. Scoped to native
  targets, where the markers resolve. Add your own with
  `kmpSsot { extraOptIns.add("…") }`.
- **Web Worker IO generation**: `kmpSsot { web { generateIoWorker = true } }`
  (default off) generates an inline Blob-Worker offload helper
  (`suspend fun kmpSsotOffload(jobJs, payload): String`) into a plugin-owned
  generated `jsMain` source dir (`build/generated/kmpssot/jsMain/kotlin`, wired
  onto the `jsMain` source set, never your hand-authored tree). Closes the "no
  `Dispatchers.IO` on the web target" gap by packaging the runtime-worker pattern.
  Generated code depends only on `kotlinx-coroutines-core`. Configure the package
  with `web { ioWorkerPackage = "…" }` (default `kmpssot.generated`). **JS target
  only** in this release. A wasmJs-only module is logged and skipped. Pairs with
  the `io.github.yuroyami:kitecore` runtime library (`KiteWorker`, `ioDispatcher()`).

### Notes
- The plugin now compiles against the full `kotlin-gradle-plugin` (`compileOnly`)
  in addition to `-api`, for the concrete `KotlinMultiplatformExtension` used by
  both new injectors. No change to what consumers ship.
- Both KGP-touching features are guarded on KGP being visible to kmp-ssot's own
  classloader. If `kotlin("multiplatform")` is declared only inside a subproject,
  KGP lands in a sibling classloader and the features degrade to a warning with
  guidance (declare it `apply false` in the ROOT plugins block) instead of
  crashing the build with `NoClassDefFoundError`.
- Worker generation is wired at `afterEvaluate` (targets don't exist yet when
  the KMP plugin applies), and the generated dir is attached via the task's
  declared output, so compile, sourcesJar, dokka and IDE import all depend on
  generation automatically. Covered by a GradleRunner functional test that
  applies real KGP with a `js()` target end-to-end.

## [1.5.0]

### Added
- **KMP-native Android library support**: the `kmpSsot { android { … } }` SDK
  block now also propagates `compileSdk`/`minSdk` to modules using AGP's
  `com.android.kotlin.multiplatform.library` plugin (the Android target of a
  Kotlin Multiplatform module: `kotlin { androidLibrary { } }`), not just the
  classic `com.android.library`. This is the standard shared-module shape under
  AGP 9, where `com.android.library` + `org.jetbrains.kotlin.multiplatform` is no
  longer allowed. Previously such modules were silently skipped.

### Notes
- The KMP library DSL exposes no `targetSdk` (libraries never had one) nor
  `ndkVersion`, so those are skipped for these modules even when set (logged at
  `info`); `targetSdk`/`ndkVersion` still apply to application and classic
  library modules. Locale propagation is unchanged: the application module owns
  the locale list.
- Wiring goes through the components extension's `finalizeDsl` hook, so a value
  set in `kmpSsot { android { } }` wins over a `compileSdk` declared in the
  module itself.

## [1.4.0]

### Added
- **`kmpSsot { android { … } }` block**: propagate `compileSdk`, `minSdk`,
  `targetSdk`, and `ndkVersion` to every Android module (application + library).
  All optional; gated by the new `propagateAndroidSdk` toggle (default true).
  `targetSdk` is applied to application modules only (libraries have none).
- **`versionCodeOverride`**: set an explicit Android `versionCode` instead of
  deriving it from `versionName`. Required for non-`x.y.z` version strings.
- **`oldSharedModuleName`**: explicitly name the previous shared-module name
  for the rename SSOT, for projects where the Podfile `pod`/path can't be
  auto-detected (pod name ≠ directory name, nested paths).
- **`dryRun` toggle**: when true, every file-rewriting task logs the change it
  *would* make and writes nothing. Preview edits before committing to them.
- **`backupBeforeRewrite` toggle** (default true): user-owned files (pbxproj,
  Info.plist, Podfile, Swift) are copied to `<file>.kmpssot.bak` before the
  first real rewrite, so a mis-detected edit is always recoverable.
- **`kmpSsotVerify` task**: prints the resolved SSOT values and which iOS
  target files exist. Modifies nothing.
- **Tests**: unit tests for versionCode derivation, hex-colour parsing,
  pbxproj rewrites, the plist sanitizer, and the shared-module rewrites, plus a
  GradleRunner functional test. **CI** (GitHub Actions) now builds, tests, and
  runs `validatePlugins` on every push/PR; a tag-triggered workflow publishes.
- **LICENSE** (Apache-2.0) and POM licence metadata. **Version catalog**
  (`gradle/libs.versions.toml`).

### Fixed
- **Build crash on `$`/`\` in identity values**: app name, version, or bundle
  id containing `$` (e.g. `"Cost$ Money"`) no longer throws
  `IllegalArgumentException: Illegal group reference` during the pbxproj
  rewrite. Replacements are now treated as literals.
- **versionCode crash/overflow**: a non-numeric or 4+-segment `versionName`
  (`1.2.3-rc1`, `2.0.0.1`, segment > 999) used to throw a raw
  `NumberFormatException` or silently overflow `Int`. It now fails fast at
  configuration with a clear message, or you set `versionCodeOverride`.
- **Distorted icons from non-square sources**: foreground and background
  layers are now aspect-fit (FG *contained*, BG *cover*) instead of stretched
  to a square. A non-square source is letterboxed/cropped, never squashed.
- **Duplicate plist keys**: the Info.plist sanitizer is now a real XML parser.
  A key whose value is a `<dict>`/`<array>`/CDATA/`<integer>` is correctly seen
  as present (the old regex inserted a second key).
- **Cross-platform colour mismatch**: a semi-transparent
  `appLogoBackgroundColor` (e.g. `#80FF0000`) is now flattened over white on
  **both** Android and iOS, with a warning, so the two platforms match.
- **Silent locale drop**: a missing `knownRegions` block in the pbxproj now
  logs a warning instead of silently discarding the locale list.
- **safeZoneRatio out of range**: values ≤ 0 (blank foreground) or > 2 now
  fail validation at configuration instead of producing a broken icon.
- **Swift import over-match**: `import shared.Submodule` / `import sharedKit`
  are no longer rewritten by the shared-module rename; only exact whole-module
  imports are.

### Changed (behaviour)
- **`javaVersion` no longer defaults to 21.** It is now applied only when you
  set it explicitly, so the plugin stops silently overriding a module's own
  `compileOptions`. Set `kmpSsot { javaVersion = 21 }` to restore the old
  behaviour.
- **Image tasks are now properly cacheable/incremental** (`@InputFile` /
  `@OutputFiles`), so the launcher icons are no longer decoded and re-encoded on
  every build, only when the source PNGs, color, or ratio actually change.
- Removed the stale "iOS launcher name" manual `INFOPLIST_KEY_CFBundleName`
  patch advice from the README. `PRODUCT_NAME` propagation covers it.

### Known limitations
- Cross-project configuration (the root plugin reaching into each Android
  module) is **not** compatible with Gradle's Isolated Projects feature. This is
  inherent to the root-applied, zero-per-module-boilerplate design. Standard
  builds and the configuration cache are unaffected.
- iOS pbxproj keys are still rewritten in **every** target/build-config section.
  Multi-target projects needing divergent values should set the relevant
  `propagate*` toggle to false. An xcconfig-include strategy is on the roadmap.
