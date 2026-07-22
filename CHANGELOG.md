# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions track the
Gradle Plugin Portal releases.

## [Unreleased]

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
- **pbxproj rewrites are now target-scoped** — identity keys (`PRODUCT_NAME`,
  `PRODUCT_BUNDLE_IDENTIFIER`, `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`,
  `INFOPLIST_KEY_*`) are rewritten only inside the **application target's** build
  configurations, so unit-test targets and app extensions keep their own names and
  distinct bundle ids. Previously every target was overwritten with the app's
  values — breaking test-bundle linkage and producing App-Store-rejectable
  extension bundle ids. Falls back to a global rewrite (with a warning) when no
  application target is found.
- **`GenerateIoWorkerTask` is now cacheable** — the plugin previously failed its
  own `validatePlugins` (the task carried no cacheability annotation).
- **Java-17 bytecode** — the plugin is compiled on JDK 21 but emits Java-17
  bytecode, so a consumer whose Gradle daemon runs on JDK 17 can load it (it was
  shipping Java-21 bytecode against a documented "JDK 17+").
- **`versionCodeOverride` without `versionName`** now writes the Android
  `versionCode` and iOS `CURRENT_PROJECT_VERSION` — it was a silent no-op on both
  platforms.
- **Shared-module auto-detect refuses to guess** when a Podfile has more than one
  local dev-pod, instead of renaming the first (possibly wrong) pod and rewriting
  its Swift imports. Set `oldSharedModuleName` to proceed.
- **Android launcher-icon template collisions** — the sync warns about template
  `ic_launcher.webp` (and friends) that collide with the generated `.png` and fail
  the AAPT2 merge; `cleanupLegacyAppLogoArtifacts` now removes them.
- **iOS `Contents.json` is backed up** before the app-icon sync overwrites it, and
  orphaned icon PNGs the new catalog no longer references are flagged.
- **Region-qualified locales map to the Apple form** for `knownRegions`
  (`pt-rBR` → `pt-BR`, `b+sr+Latn` → `sr-Latn`), and non-locale `values-*` dirs
  (`night`, `v26`, `land`) are excluded from auto-detection.
- **Non-CocoaPods iOS projects sync from Gradle** — the iOS sync now hooks plain
  `linkReleaseFrameworkIos*` / `assemble*XCFramework` tasks, not just `linkPod*`.
- Changing `web { ioWorkerPackage }` no longer leaves a stale generated file
  (duplicate `kmpSsotOffload`); the generated Blob worker revokes its object URL;
  `ioWorkerPackage` rejects Kotlin hard-keyword segments; plist inserts no longer
  leave a blank line; `writeAtomically` sweeps stale temp files; root-only
  enforcement throws a `GradleException`; logo validation is gated on
  `propagateLogo`.

### Added
- **Runtime `buildConfig` codegen** — `kmpSsot { buildConfig { enabled = true } }`
  generates a typed constants object into the shared module's `commonMain`,
  readable from every KMP source set with no `expect/actual` — a single-plugin
  **buildKonfig replacement** for the common case. The object carries the identity
  SSOT (appName, versionName, versionCode, androidApplicationId, iosBundleId,
  locales) plus your own `stringField` / `intField` / `longField` / `booleanField`
  / `doubleField` declarations (each also accepts a `Provider<String>` for lazy
  non-secret configuration). Generated constants are compiled into consumer
  artifacts and therefore must never contain secrets. The object name is
  configurable via `className` (default `BuildConfig`), the package via
  `packageName`, and identity inclusion via `includeIdentity`. Default off.
  Deliberately flat — no per-flavor / per-target value overlays.
- **`kmpSsotDoctor`** — a read-only end-to-end setup diagnostic (manifest
  placeholder, Info.plist SSOT refs, pbxproj application target, appiconset, icon
  collisions, locale sanity, versionCode derivability, KGP visibility).
- **Aggregate tasks** — `kmpSsotSync`, `kmpSsotSyncIos`, `kmpSsotSyncAndroid`.
- **Kotlin `jvmTarget`** is set alongside `javaVersion`, eliminating the
  "Inconsistent JVM-target compatibility" error.
- `kmpSsotVerify` now also reports the Android SDK levels, `javaVersion`, the
  interop/web toggles, and the logo configuration.

### Changed (behaviour)
- **Classic Android modules are now SSOT-authoritative.** `com.android.application`
  and `com.android.library` wiring moved to AGP's `finalizeDsl`, so a value in
  `kmpSsot { }` overrides a module-local `applicationId` / `versionName` /
  `compileSdk` — matching the KMP-native library path. Leave a field unset in
  `kmpSsot { }` to keep the module's own value. (Previously module-local values
  won for these two plugins.)
- **Application locale propagation uses AGP 9 `androidResources.localeFilters`**
  with a runtime fallback to the deprecated `resourceConfigurations` for AGP 8.

## [1.6.0]

### Added
- **Interop opt-in propagation** — `kmpSsot { propagateInteropOptIns = true }`
  (default on) adds the cinterop / Obj-C opt-in markers
  (`kotlinx.cinterop.ExperimentalForeignApi`,
  `kotlin.experimental.ExperimentalObjCName`,
  `kotlin.experimental.ExperimentalNativeApi`) to **every Kotlin/Native
  compilation**, so call sites no longer each need an `@OptIn`. Scoped to native
  targets, where the markers resolve. Add your own with
  `kmpSsot { extraOptIns.add("…") }`.
- **Web Worker IO generation** — `kmpSsot { web { generateIoWorker = true } }`
  (default off) generates an inline Blob-Worker offload helper
  (`suspend fun kmpSsotOffload(jobJs, payload): String`) into a plugin-owned
  generated `jsMain` source dir (`build/generated/kmpssot/jsMain/kotlin`, wired
  onto the `jsMain` source set — never your hand-authored tree). Closes the "no
  `Dispatchers.IO` on the web target" gap by packaging the runtime-worker pattern.
  Generated code depends only on `kotlinx-coroutines-core`. Configure the package
  with `web { ioWorkerPackage = "…" }` (default `kmpssot.generated`). **JS target
  only** in this release — a wasmJs-only module is logged and skipped. Pairs with
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
- **KMP-native Android library support** — the `kmpSsot { android { … } }` SDK
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
  library modules. Locale propagation is unchanged — the application module owns
  the locale list.
- Wiring goes through the components extension's `finalizeDsl` hook, so a value
  set in `kmpSsot { android { } }` wins over a `compileSdk` declared in the
  module itself.

## [1.4.0]

### Added
- **`kmpSsot { android { … } }` block** — propagate `compileSdk`, `minSdk`,
  `targetSdk`, and `ndkVersion` to every Android module (application + library).
  All optional; gated by the new `propagateAndroidSdk` toggle (default true).
  `targetSdk` is applied to application modules only (libraries have none).
- **`versionCodeOverride`** — set an explicit Android `versionCode` instead of
  deriving it from `versionName`. Required for non-`x.y.z` version strings.
- **`oldSharedModuleName`** — explicitly name the previous shared-module name
  for the rename SSOT, for projects where the Podfile `pod`/path can't be
  auto-detected (pod name ≠ directory name, nested paths).
- **`dryRun` toggle** — when true, every file-rewriting task logs the change it
  *would* make and writes nothing. Preview edits before committing to them.
- **`backupBeforeRewrite` toggle** (default true) — user-owned files (pbxproj,
  Info.plist, Podfile, Swift) are copied to `<file>.kmpssot.bak` before the
  first real rewrite, so a mis-detected edit is always recoverable.
- **`kmpSsotVerify` task** — prints the resolved SSOT values and which iOS
  target files exist. Modifies nothing.
- **Tests** — unit tests for versionCode derivation, hex-colour parsing,
  pbxproj rewrites, the plist sanitizer, and the shared-module rewrites, plus a
  GradleRunner functional test. **CI** (GitHub Actions) now builds, tests, and
  runs `validatePlugins` on every push/PR; a tag-triggered workflow publishes.
- **LICENSE** (Apache-2.0) and POM licence metadata. **Version catalog**
  (`gradle/libs.versions.toml`).

### Fixed
- **Build crash on `$`/`\` in identity values** — app name, version, or bundle
  id containing `$` (e.g. `"Cost$ Money"`) no longer throws
  `IllegalArgumentException: Illegal group reference` during the pbxproj
  rewrite. Replacements are now treated as literals.
- **versionCode crash/overflow** — a non-numeric or 4+-segment `versionName`
  (`1.2.3-rc1`, `2.0.0.1`, segment > 999) used to throw a raw
  `NumberFormatException` or silently overflow `Int`. It now fails fast at
  configuration with a clear message, or you set `versionCodeOverride`.
- **Distorted icons from non-square sources** — foreground and background
  layers are now aspect-fit (FG *contained*, BG *cover*) instead of stretched
  to a square. A non-square source is letterboxed/cropped, never squashed.
- **Duplicate plist keys** — the Info.plist sanitizer is now a real XML parser.
  A key whose value is a `<dict>`/`<array>`/CDATA/`<integer>` is correctly seen
  as present (the old regex inserted a second key).
- **Cross-platform colour mismatch** — a semi-transparent
  `appLogoBackgroundColor` (e.g. `#80FF0000`) is now flattened over white on
  **both** Android and iOS, with a warning, so the two platforms match.
- **Silent locale drop** — a missing `knownRegions` block in the pbxproj now
  logs a warning instead of silently discarding the locale list.
- **safeZoneRatio out of range** — values ≤ 0 (blank foreground) or > 2 now
  fail validation at configuration instead of producing a broken icon.
- **Swift import over-match** — `import shared.Submodule` / `import sharedKit`
  are no longer rewritten by the shared-module rename; only exact whole-module
  imports are.

### Changed (behaviour)
- **`javaVersion` no longer defaults to 21.** It is now applied only when you
  set it explicitly, so the plugin stops silently overriding a module's own
  `compileOptions`. Set `kmpSsot { javaVersion = 21 }` to restore the old
  behaviour.
- **Image tasks are now properly cacheable/incremental** (`@InputFile` /
  `@OutputFiles`), so the launcher icons are no longer decoded and re-encoded on
  every build — only when the source PNGs, colour, or ratio actually change.
- Removed the stale "iOS launcher name" manual `INFOPLIST_KEY_CFBundleName`
  patch advice from the README — `PRODUCT_NAME` propagation covers it.

### Known limitations
- Cross-project configuration (the root plugin reaching into each Android
  module) is **not** compatible with Gradle's Isolated Projects feature. This is
  inherent to the root-applied, zero-per-module-boilerplate design. Standard
  builds and the configuration cache are unaffected.
- iOS pbxproj keys are still rewritten in **every** target/build-config section.
  Multi-target projects needing divergent values should set the relevant
  `propagate*` toggle to false. An xcconfig-include strategy is on the roadmap.
