# kmp-ssot — Feature List & Idea Bank

Two parts:
- **Part A — What it does today** (derived from the code on `main`, v1.6.x working tree — not the README).
- **Part B — Idea bank** (~105 features), tiered by value and flagged where they'd tip into
  overkill for a "single source of truth" plugin.

---

## Part A — Current features (source of truth: the code)

### Identity propagation
- `appName` → Android `manifestPlaceholders["appName"]`; iOS `PRODUCT_NAME` +
  `INFOPLIST_KEY_CFBundleDisplayName`/`CFBundleName` (when present) with pbxproj-safe
  quoting/escaping; plist `$(PRODUCT_NAME)` references ensured by the sanitizer.
- `versionName` → Android `versionName`, iOS `MARKETING_VERSION`.
- Derived `versionCode` (`"1"` + 3-digit-padded dot segments, fail-fast on non-derivable
  names) → Android `versionCode`, iOS `CURRENT_PROJECT_VERSION`; explicit
  `versionCodeOverride` bypass. *(Known gap: override without `versionName` currently no-ops
  on both platforms — see AUDIT §3.4.)*
- `bundleIdBase` + `iosBundleSuffix` / `androidApplicationIdSuffix` → Android
  `applicationId`, iOS `PRODUCT_BUNDLE_IDENTIFIER`.
- Every identity field optional; propagation requires toggle ON **and** value set.

### Localization
- Locale auto-detection from `${sharedModule}/src/commonMain/composeResources/values-*`
  (config-cache-tracked), explicit `locales` override.
- → Android `resourceConfigurations` (app + classic library), iOS `knownRegions`
  (preserves `Base`; warns instead of corrupting when the block is missing).

### Android toolchain (`android { }`)
- `compileSdk` / `minSdk` / `targetSdk` / `ndkVersion` → application + classic library
  modules (eager wiring).
- `compileSdk` / `minSdk` → KMP-native `com.android.kotlin.multiplatform.library` via
  `finalizeDsl` (SSOT-authoritative; AGP types isolated in their own class so the plugin
  loads without AGP).
- `javaVersion` (no default) → Android `compileOptions` source/target compatibility.

### iOS project sync
- pbxproj rewrite (idempotent, literal-safe for `$`/`\`, left-anchored keys, quote-aware
  values): `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`, `PRODUCT_NAME`,
  `INFOPLIST_KEY_CFBundleDisplayName/Name`, `PRODUCT_BUNDLE_IDENTIFIER`, `knownRegions`.
  *(Known limitation: target-blind — every build configuration gets the value; AUDIT §2.1.)*
- `Info.plist` sanitizer on a real XML DOM (XXE-hardened, never corrupts on parse failure):
  SSOT-pointing string keys append-only with divergence warnings; `ios { }` boolean flags
  DSL-wins (`ITSAppUsesNonExemptEncryption`, `CADisableMinimumFrameDurationOnPhone`);
  faithful-prolog re-serialization; indent sniffing.
- Auto-hooked into `linkPod*FrameworkIos*` + `embedAndSignAppleFrameworkForXcode`
  *(CocoaPods-shaped names only; plain `binaries.framework()` link tasks not yet hooked)*.

### Shared-module rename SSOT
- Podfile `pod 'X', :path => '../X'` rewrite — classic `:path =>` and modern `path:`
  syntaxes, nested `../modules/X` paths, prefix preserved.
- Swift `import X` rewrite — exact whole-module only; submodule (`X.Foo`), same-prefix
  (`XKit`), `@testable`, `@_implementationOnly` untouched; vendored/generated dirs pruned
  (`Pods`, `build`, `.build`, `DerivedData`, `xcuserdata`, `.git`).
- Old name auto-detected from the Podfile (name == path tail) or explicit
  `oldSharedModuleName`. *(Known gap: first-match detection can pick the wrong local pod in
  multi-dev-pod Podfiles; AUDIT §3.1.)*

### App logo pipeline
- Inputs: FG PNG + exactly one of BG PNG / `appLogoBackgroundColor` (`#RRGGBB` /
  `#AARRGGBB`, alpha-first); config-time validation of pairing and hex format.
- Android: full launcher tree — adaptive FG (safe-zone padded, tunable
  `appLogoAndroidSafeZoneRatio`, validated in `(0,2]`) + BG at 5 densities, legacy square +
  circle-masked round at 48dp scale, `mipmap-anydpi-v26` wrappers; aspect-fit (contain) /
  cover semantics, never stretched; bicubic quality hints; non-square and low-res warnings;
  hooked to every app module's `preBuild`.
- iOS: 1024² FG-over-BG composite, flattened opaque (App Store rejects alpha), single-image
  universal `AppIcon-1024.png` + `Contents.json`; hooked to iOS framework link tasks.
- Translucent BG colour flattened over white **identically on both platforms** (with
  warning) so icons match.
- Both icon tasks are `@CacheableTask` with fully declared outputs.
- Opt-in `cleanupLegacyAppLogoArtifacts` migration task (pre-FG/BG artefacts).

### Native & web toolchain gap-closers
- `propagateInteropOptIns` (default ON) — `ExperimentalForeignApi`, `ExperimentalObjCName`,
  `ExperimentalNativeApi` opt-ins added to every Kotlin/Native compilation; `extraOptIns`
  for user markers; de-duplicated, order-stable.
- `web { generateIoWorker }` (default OFF) — generates a zero-dependency inline Blob-Worker
  offload helper (`suspend fun kmpSsotOffload(jobJs, payload): String`) into
  `build/generated/kmpssot/<jsTarget>Main/kotlin`, wired via `srcDir(task.flatMap{…})` so
  compile/sourcesJar/IDE all see it; custom-named `js("web")` targets supported; wasmJs
  detected and skipped with guidance; package name validated.
- Both features guarded by a classloader probe: if KGP isn't visible to the plugin's
  classloader (subproject-only `kotlin("multiplatform")`), they degrade to a warning with
  the exact fix instead of `NoClassDefFoundError`.

### Safety & DX
- `dryRun` (log-only preview), `backupBeforeRewrite` (write-once `<file>.kmpssot.bak`,
  earliest pristine copy survives), atomic temp-file+move writes (truncation-proof),
  idempotent no-op on identical content.
- `kmpSsotVerify` read-only report (identity values + iOS file presence).
- Root-only application enforced; Gradle minimum warned; `Project.kmpSsot` accessor for
  subproject build scripts.
- Config-cache compatible (verified end-to-end, including locale-dir invalidation).
- CI (build + test + validatePlugins), tag-driven publishing to Gradle Plugin Portal +
  GitHub Packages, Apache-2.0, version catalog.

### Honest current-limitations line (from AUDIT)
Target-blind pbxproj; HEAD `validatePlugins` red (`GenerateIoWorkerTask` annotation);
Java-21 bytecode vs README's JDK-17 claim; eager (module-wins) precedence on classic
Android; webp-template collision on first Android logo sync; `Contents.json` overwritten
without backup; region-qualified locale tags unmapped for iOS; JS-only worker.

---

## Part B — Idea bank

Legend: **[P0–P3]** priority · ⭐ high-value · ⚠️ overkill risk · 🧪 experimental.
"Overkill" = bloats the plugin's identity or duplicates a tool that owns the concern.

### B1. Identity & versioning
1. ⭐ **Runtime `KmpSsotBuildInfo` object** generated into `commonMain` (appName, versionName,
   versionCode, bundleId, locales) — closes SSOT to runtime; kills per-platform BuildConfig
   boilerplate. **[P1]**
2. ⭐ **Git-derived versioning** — versionName/code from tag / `git describe` / commit count,
   as a pure testable deriver. **[P2]**
3. **Pluggable versionCode schemes** — `dotPadded` (current), `monotonic`, `dateBased
   (yyMMddNN)`, custom lambda. **[P2]**
4. **Per-flavor / per-variant identity overlays** — `flavors { "pro" { appName = … } }`. **[P2]**
5. **Per-module identity overlays** — phone vs Wear vs TV application modules with distinct
   `applicationIdSuffix`/names (fixes the multi-app collision, AUDIT §3.12). **[P1]** ⭐
6. **`buildType`-aware suffixing** — `.debug`/`.staging` applicationId + iOS bundle suffix +
   icon badge in one knob. **[P2]** ⭐
7. **CI build-number env override** — `KMPSSOT_VERSION_CODE` / `--PkmpSsot.versionCode`
   honored above the derivation, for store pipelines. **[P2]** ⭐
8. **Marketing vs build version decoupling** — explicit `iosBuildNumber`. **[P2]**
9. **Semver validation opt-in** for `versionName`. **[P3]**
10. ⚠️ **Auto-increment versionCode with write-back** — mutating the build file each release is
    footgun-prone; prefer #2/#3/#7. **[P3]**

### B2. iOS (the biggest surface)
11. ⭐ **xcconfig strategy** — emit `kmpssot.xcconfig`, consumer includes it in the app
    target's base config; retires pbxproj regex & target-blindness permanently. Ship as
    `iosStrategy = XCCONFIG | PBXPROJ`. **[P0-arch]**
12. ⭐ **Primary-target scoping** for the pbxproj path (target → configList → config spans)
    with graceful global fallback — the interim fix for AUDIT §2.1. **[P0]**
13. ⭐ **Per-target iOS rules** — `iosTargets { "Widget" { bundleIdSuffix = ".widget" } }` so
    tests/extensions get *correct* values instead of being skipped. **[P1]**
14. **Non-Pod link-task hooking** — `link{Release,Debug}FrameworkIos*`,
    `assemble*XCFramework`. **[P0]**
15. **SPM / XCFramework rename support** — Package.swift + project refs for the
    no-CocoaPods world. **[P1]**
16. **Region-tag mapping** — `pt-rBR`→`pt-BR`, `b+sr+Latn`→`sr-Latn` for `knownRegions`
    (AUDIT §3.6), plus qualifier filtering. **[P0]**
17. **`developmentRegion` SSOT** — set pbxproj `developmentRegion` from the default locale. **[P2]**
18. ⭐ **iOS 18+ dark & tinted icon variants** — accept optional dark/tinted FGs, emit the
    multi-appearance asset catalog. **[P2]** 🧪
19. **`LaunchScreen.storyboard` logo injection** (roadmap item). **[P2]**
20. **More `ios { }` flags** — `UIRequiresFullScreen`, orientations,
    `ITSEncryptionExportComplianceCode`, `UIBackgroundModes`, ATS presets. **[P2]**
21. **Privacy manifest (`PrivacyInfo.xcprivacy`) scaffolding** — App-Store-required and pure
    boilerplate. **[P2]** 🧪
22. **`CFBundleURLTypes` / deep-link scheme SSOT** — one scheme declaration → iOS URL types +
    Android `intent-filter` doc/snippet. **[P2]**
23. **Associated Domains / entitlements SSOT** — applinks + Android asset-links from one
    list. **[P2]** ⚠️ (security-adjacent; opt-in only)
24. **`IPHONEOS_DEPLOYMENT_TARGET` SSOT** — `ios { deploymentTarget = "15.0" }`. **[P2]**
25. **Appiconset orphan pruning** — after writing the universal icon, list/optionally remove
    unreferenced legacy icon PNGs (fixes the Xcode "unassigned children" noise, AUDIT §3.3). **[P1]**
26. **Bridging-header / `@_implementationOnly` rename** (currently excluded by design). **[P3]** ⚠️
27. ⚠️ **`pod install` auto-exec** after rename — shelling out is fragile; keep manual. **[P3]**

### B3. Android
28. ⭐ **`localeFilters` migration** (AGP 9) with version-gated fallback to
    `resourceConfigurations` (AGP 8). **[P0/P1]**
29. ⭐ **Kotlin `jvmTarget` wired with `javaVersion`** (+ optional full JVM-toolchain mode). **[P1]**
30. **Authoritative classic-module wiring via `androidComponents.finalizeDsl`** (SSOT wins
    everywhere, matching the KMP-library path; AGP types kept in an isolated class). **[P1]**
31. **Template-asset takeover** — detect & (with `cleanupLegacyLogoArtifacts` or a new
    `adoptTemplateIcons = true`) remove colliding `ic_launcher*.webp` / stray launcher XMLs
    that break the resource merge (AUDIT §3.2). **[P0]**
32. **Themed / monochrome adaptive icon** (`<monochrome>`, Android 13+). **[P1]** ⭐
33. **Per-app language `localeConfig`** — generate `locales_config.xml` + manifest wiring for
    Android 13 per-app language settings, from the same locale list. **[P2]** ⭐
34. **`resValue` / `buildConfigField` injection** of SSOT values for legacy Android code. **[P2]**
35. **`namespace` SSOT** across Android modules. **[P2]**
36. **NDK ABI filter SSOT.** **[P3]**
37. **Round-icon-less mode** — optionally skip legacy/round outputs for minSdk ≥ 26. **[P3]**
38. ⚠️ **Signing-config SSOT** — secrets adjacent; at most a pointer to env vars, never storage. **[P3]**
39. ⚠️ **Play Store metadata / fastlane scaffolding** — different product. **[P3]**

### B4. Assets & branding
40. **SVG/vector FG input** rasterized per density (needs a rasterizer dep — weigh cost). **[P2]** 🧪
41. **Notification / status-bar monochrome icon** generation from the FG. **[P2]**
42. **Splash-screen SSOT** — Android 12 `SplashScreen` attrs + iOS launch storyboard colour/logo
    from the same FG/BG. **[P2]** ⭐
43. **Store marketing icon export** (Play 512², App Store 1024²) to a chosen dir. **[P3]**
44. **Web favicon + PWA `manifest.json` icons** for js/wasm targets. **[P2]**
45. ⭐ **Debug/staging icon badging** — corner ribbon ("DEBUG", "β") per build type; devs love
    it and it's pure compositing the pipeline already does. **[P2]**
46. **Gradient/vector BG source** (linear gradient spec in DSL) beyond flat colour. **[P3]**
47. **Safe-zone content linter** — warn when FG pixels exceed the safe circle. **[P3]** 🧪
48. ⚠️ **Full brand-kit generation** (palettes→themes→typography) — a design-system tool, not
    this plugin. **[out]**

### B5. Web & desktop targets
49. **wasmJs IO worker** — finish the story (same Blob pattern compiled for wasm). **[P2]**
50. ⭐ **Compose Desktop identity** — `nativeDistributions` packageName/version/vendor/
    copyright from the SSOT (identical copy-paste pain, zero risk). **[P1]**
51. **Desktop app icons** — `.icns` / `.ico` / linux PNG from the same FG/BG. **[P2]**
52. **Web `manifest.json` + `<meta>` identity** (name, theme colour) from the SSOT. **[P2]**
53. **Blob-URL hygiene in the generated worker** — `URL.revokeObjectURL` after spawn + optional
    timeout param (AUDIT §3.13). **[P1]**
54. ⚠️ **Broader web runtime helpers** — belongs in KiteCore. **[out]**

### B6. Diagnostics, safety & DX
55. ⭐ **`kmpSsotSync` aggregate task** (+ `kmpSsotSyncIos` / `kmpSsotSyncAndroid`) — one entry
    point instead of six task names. **[P1]**
56. ⭐ **`kmpSsotDoctor`** — full-setup validation with pass/fail table and exact fixes:
    manifest placeholder present, plist keys point at `$(…)`, pbxproj/appiconset found, webp
    collisions, multiple app modules, KGP visibility, region tags mappable, versionCode
    derivable. **[P1]**
57. ⭐ **`kmpSsotCheck` drift gate** — fails when on-disk files diverge from SSOT (CI/pre-commit
    guard; verify-but-strict). **[P2]**
58. **Unified-diff dry-run** — print the exact patch, not just "would update". **[P2]** ⭐
59. **`kmpSsotUndo`** — restore all `.kmpssot.bak` files. **[P2]**
60. **Gradle Problems API** for every rewriter warning (build scans, IDE). **[P2]**
61. **JSON `--report` output** from verify/doctor for CI consumption. **[P2]**
62. **Backup hygiene** — `.gitignore` hint for `*.kmpssot.bak`, stale-tmp sweep on task entry. **[P3]**
63. **Task-name coherence** — `kmpSsot*` prefix everywhere, old names as deprecated aliases. **[P2]**
64. **Structured log levels** — `quiet`/`verbose` toggle; demote per-file lifecycle chatter. **[P3]**
65. **Warning-as-error toggle** — `strictWarnings = true` turns rewriter warnings into failures. **[P3]**

### B7. Architecture & compatibility
66. ⭐ **Settings-plugin + BuildService** → Isolated-Projects compatibility; per-project
    companion plugin pulls values locally. **[P2]**
67. ⭐ **Generated-source model for Android icons** — emit into `build/generated/kmpssot/res`
    registered as a res source dir; source tree stays clean; template collisions vanish. **[P1]**
68. **`ValueSource` for locale detection** — explicit CC input (today works via
    instrumentation; stylistic hardening only). **[P3]**
69. **Gradle-version TestKit matrix** — 8.5 floor … current, parameterized functional tests. **[P2]**
70. **`binary-compatibility-validator`** on the public DSL. **[P2]**
71. **Dokka API docs** published per release. **[P2]**
72. **ktlint/detekt + `.editorconfig`.** **[P2]**
73. **Java-17 bytecode target** (compile on 21, target 17) so JDK-17 daemons can load the
    plugin (AUDIT §2.4). **[P0]**
74. **CI: `--configuration-cache` on the build job** — lock in the verified-good CC state. **[P1]**
75. **Convention-plugin companion id** (`io.github.yuroyami.kmpssot.module`) for the IP-clean
    per-module model. **[P3]**

### B8. Reading/writing the SSOT from elsewhere
76. ⭐ **`kmpssot.toml` / version-catalog as the source** — CI, fastlane, Danger, scripts read
    the same file the DSL reads. **[P2]**
77. **Export `kmpssot.env` / JSON** (`APP_VERSION`, `BUNDLE_ID`, …) for pipeline tooling. **[P2]** ⭐
78. **Read identity from `libs.versions.toml` keys** (`appVersion = "1.2.3"`) as an opt-in
    convention. **[P3]**
79. ⚠️ **Two-way sync** (edit pbxproj → update DSL) — destroys unidirectional truth; never. **[out]**

### B9. Store / release
80. **Release-tag helper task** aligned with the version scheme. **[P3]**
81. **Changelog-driven release-notes plumbing** (Play `whatsnew/`, ASC). **[P3]** ⚠️
82. **Store version pre-check** (is versionCode free?) via store APIs. **[P3]** 🧪 ⚠️ (network+secrets)

### B10. Testing & quality (of the plugin itself)
83. ⭐ **Multi-target pbxproj fixture** asserting non-primary targets untouched — the §2.1
    regression lock. **[P0]**
84. ⭐ **AGP-applied functional tests** (applicationId/version/SDK/javaVersion/locale wiring,
    KMP-library `finalizeDsl` path). **[P1]**
85. **CC assertions in functional tests** (`--configuration-cache`, assert reuse). **[P1]**
86. **Golden-image icon tests** — dimensions, adaptive XML, opaque flatten, circle mask,
    safe-zone geometry. **[P2]**
87. **End-to-end rename test** through the task (Podfile+Swift+PRUNED_DIRS+multi-pod
    ambiguity). **[P0 with the §3.1 fix]**
88. **Property-based tests** for `deriveVersionCode` / hex / region mapping. **[P3]**
89. **Mutation testing** on the pure rewriters. **[P3]** 🧪
90. **Worker-package change test** — stale-file cleanup regression (AUDIT §3.10). **[P0 with fix]**

### B11. Ecosystem niceties
91. ⭐ **Sample/starter repo** (real KMP app) CI-built against the plugin — living integration
    test + adoption showcase. **[P2]**
92. **Migration guide + `kmpSsotMigrate`** from hand-rolled version-sync scripts. **[P3]**
93. **GitHub Action** running `kmpSsotCheck` on PRs. **[P3]**
94. **Gradle init template / `gradle init` integration snippet.** **[P3]**
95. **IDE (KTS) sample completions** — rich KDoc on every DSL property (mostly done; keep the
    bar). **[P3]**

### B12. Moonshots 🧪
96. **Typed pbxproj mini-parser** — ~200-line OpenStep tokenizer producing an id→object graph
    with span-preserving surgical edits; middle ground between regex and a full xcodeproj
    port; unlocks per-target everything. **[P2]**
97. **`kmpSsotDoctor --fix`** — doctor findings with safe auto-remediation (insert manifest
    placeholder, add xcconfig include, delete webp collisions) behind per-fix prompts. **[P2]**
98. **Baseline `Info.plist`/manifest templating** — generate both from one declarative
    identity block for brand-new projects (greenfield mode). **[P3]** ⚠️
99. **Multi-app monorepos** — N application "profiles" each with its own identity block,
    sharing the toolchain SSOT. **[P3]**
100. **Watch/TV/Auto companion identity** — extension-target identity blocks (pairs with #13). **[P3]**
101. **Crash-reporter/analytics tag emission** — optional generated constants file for
     Sentry/Crashlytics release tagging (rides #1). **[P3]**
102. **Screenshot-test identity stubs** — inject app name/icon into Paparazzi/preview configs. **[P3]** ⚠️
103. **License/about screen data** — generated `KmpSsotBuildInfo.licenses` from Gradle dependency
     metadata. **[P3]** ⚠️ (aboutlibraries owns this)
104. **Remote SSOT** — fetch identity from a URL at config time. **[out]** ⚠️ (non-hermetic builds)
105. **AI icon generation hook** — out; the plugin propagates truth, it doesn't invent it. **[out]**

---

### Deliberately out of scope (overkill)
- Design-system/theming generation (#48), broad runtime helpers (#54) — different products.
- Secrets/signing/keystore storage (#38), store-API calls by default (#82) — security- and
  network-bound; never default, never stored.
- Two-way pbxproj↔DSL sync (#79) — breaks the single unidirectional truth invariant.
- Non-hermetic remote config (#104).

**Guiding rule:** every feature must either (a) propagate one truth to more places, or
(b) make that propagation safer or more observable. Anything else is scope creep — no matter
how cool.
