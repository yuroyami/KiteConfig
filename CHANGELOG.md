# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions track the
Gradle Plugin Portal releases.

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
