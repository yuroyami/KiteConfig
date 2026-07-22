# kmp-ssot — Deep Audit (v2, consolidated)

> **Historical snapshot — not current authority.** This document audits the
> 1.6-era tree and intentionally remains unchanged as evidence of that review.
> Several findings and line references were superseded by later implementation.
> For current behavior, read the code; for current audit status, use the top
> implementation overlay in [SOL_AUDIT.md](SOL_AUDIT.md), with
> [README.md](README.md) and [FEATURES.md](FEATURES.md) as code-aligned guides.

*Fresh-eyes review of the working tree on `main` (1.6.0 + uncommitted 1.6.x WIP). Code is the
only source of truth; README/CHANGELOG treated as claims to be verified. Findings are tagged:*

- **PROVEN** — demonstrated by a live experiment or a live build run in this audit.
- **CONFIRMED** — the defective code path is fully traced in source; no counter-path exists.
- **LIKELY** — depends on a consumer-environment fact (e.g. which template generated the app);
  the code path is confirmed, the trigger frequency is estimated.

Scope: all 23 `src/main` files, all 9 test files, `build.gradle.kts`, `settings.gradle.kts`,
`libs.versions.toml`, `gradle.properties`, wrapper, both CI workflows, `.gitignore`, LICENSE,
README, CHANGELOG. Full test suite executed during the audit (50 tests, 0 failures) — but see
§2.3: the build as a whole is **red**.

---

## 0. Verdict

A **well-crafted plugin with genuinely excellent I/O safety and error messaging, sitting on
one structurally dangerous core** (target-blind regex pbxproj rewriting) plus a second tier of
correctness gaps that single-target test fixtures cannot expose — and, right now, a working
tree that **fails its own `validatePlugins`**.

If 0% = lowest form and 100% = horizon: **~66%**. The craft ceiling is high (atomic writes,
write-once backups, XXE-hardened XML, classloader guards, literal-safe replacement). The score
is capped by one proven data-corruption class (§2.1), one proven red build (§2.3), and an
architecture (root-applied regex rewriting of a structured file format) that cannot reach 100%
without the xcconfig redesign the roadmap already names.

| Axis | Grade | One-line |
|---|---|---|
| I/O safety | A | Atomic move, write-once backup, dry-run — genuinely careful. |
| Error messages | A | Every failure is actionable with remediation. Best-in-class. |
| Testability of pure logic | A− | Rewriters extracted as pure fns; 50 green unit/functional tests. |
| pbxproj correctness | **D** | **PROVEN**: clobbers every non-primary target (§2.1). |
| Release engineering | **D** | **PROVEN**: HEAD fails `validatePlugins`; CI would be red (§2.3). |
| Consumer compatibility | C− | Ships Java-21 bytecode while README claims JDK 17+ (§3.1). |
| Test realism | C− | Fixtures single-target, no AGP applied, no CC assertion. |
| SSOT authority | C | Classic Android modules silently override the "single" source (§3.5). |
| Config cache | A | **PROVEN** working end-to-end incl. locale auto-detect invalidation (§2.2). |
| API/DSL ergonomics | B | Flat, optional-everything is right; no aggregate task, thin verify. |
| Scope discipline | B− | Web-Worker codegen is runtime infra inside a config plugin. |
| Toolchain modernity | C+ | Deprecated `resourceConfigurations`; `javaVersion` misses Kotlin. |

---

## 1. What is genuinely excellent — DO NOT TOUCH

Load-bearing and correct. Preserve through any refactor.

- **`RewriteSafety.writeTextSafely` / `writeAtomically`** — stage-to-temp + `ATOMIC_MOVE` with
  fallback, idempotent no-op on identical content, **write-once** backup so the earliest
  pristine copy always survives ([RewriteSafety.kt:27](src/main/kotlin/io/github/yuroyami/kmpssot/RewriteSafety.kt#L27)).
- **`sanitizeInfoPlist`** — real DOM parser, external DTD/entity loading disabled (XXE-safe),
  distinguishes absent / unpaired / wrong-type keys, never corrupts on parse failure
  ([PlistSanitize.kt:46](src/main/kotlin/io/github/yuroyami/kmpssot/PlistSanitize.kt#L46)).
- **Literal replacement** via the lambda overload of `Regex.replace` — `$`/`\` in identity
  values cannot throw "Illegal group reference" ([PbxprojRewrite.kt:32](src/main/kotlin/io/github/yuroyami/kmpssot/PbxprojRewrite.kt#L32)). Tested.
- **Left-anchored key regex + quoted-value alternation** — `MY_PRODUCT_NAME` never hit by a
  `PRODUCT_NAME` rewrite; a quoted `;` can't split a value; a missing `;` can't swallow lines
  ([PbxprojRewrite.kt:43](src/main/kotlin/io/github/yuroyami/kmpssot/PbxprojRewrite.kt#L43)). Tested.
- **`KGP_ON_CLASSPATH` guard** — compileOnly-KGP-in-a-sibling-classloader is real and subtle;
  the reflective probe + graceful degradation is correct
  ([KmpSsotPlugin.kt:478](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L478)).
- **`KmpAndroidLibraryWiring` isolated in its own class** so the AGP type never enters
  `KmpSsotPlugin`'s method descriptors — the plugin decorates even when AGP is absent
  ([KmpAndroidLibraryWiring.kt:29](src/main/kotlin/io/github/yuroyami/kmpssot/KmpAndroidLibraryWiring.kt#L29)).
  **Any new AGP-typed wiring must copy this pattern.**
- **`deriveVersionCode` fail-fast** with actionable message, 32-bit ceiling rationale,
  `versionCodeOverride` escape hatch ([VersionCode.kt:22](src/main/kotlin/io/github/yuroyami/kmpssot/VersionCode.kt#L22)).
- **Optional-everything DSL** — propagate iff toggle AND value set. Right model for adopting
  the plugin on a live app. Keep.
- **`srcDir(genTask.flatMap { outputDir })` wiring** for the IO worker — dependency carried to
  every consumer of the source set ([KmpSsotPlugin.kt:237](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L237)).

---

## 2. PROVEN findings (live experiments / live runs)

### 2.1 🔴 CRITICAL — pbxproj rewrites are target-blind; every non-primary target is corrupted

`rewritePbxproj` replaces `PRODUCT_NAME`, `PRODUCT_BUNDLE_IDENTIFIER`,
`INFOPLIST_KEY_CFBundleDisplayName/Name`, `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`
**globally** — every `XCBuildConfiguration` in the file, no notion of target ownership
([PbxprojRewrite.kt:21](src/main/kotlin/io/github/yuroyami/kmpssot/PbxprojRewrite.kt#L21)).

Live experiment against the published 1.6.0 artifact on a realistic multi-target pbxproj
(app + unit-test target + widget extension):

```
Unit-test target:   PRODUCT_NAME = iosAppTests             → "Probe"
                    PRODUCT_BUNDLE_IDENTIFIER = com.demo.app.tests  → com.probe.app
Widget extension:   PRODUCT_NAME = WidgetExtension          → "Probe"
                    PRODUCT_BUNDLE_IDENTIFIER = com.demo.app.widget → com.probe.app
```

Consequences on a real project (virtually all have ≥1 test target):
- **Test bundle breaks** — `TEST_HOST`/`BUNDLE_LOADER` linkage and signing go wrong once the
  test target's bundle id equals the host's.
- **App becomes un-submittable** — an extension MUST have a distinct dot-suffixed child bundle
  id; collapsing it to the host id fails App Store validation.

The README documents only the bundle-id case with an all-or-nothing escape
(`propagateBundleId = false`) and says nothing about `PRODUCT_NAME`, which has **no**
independent switch (rides `propagateAppName`). The tests never catch it — every fixture is a
flat file of single-occurrence keys.

**Fix path (cheapest → best):** (1) document + per-key opt-outs; (2) scope rewrites to the
application target's `buildConfigurationList` via a small object-graph walk (target →
configList → config ids → spans); (3) retire pbxproj editing entirely for an xcconfig (§4.1).

### 2.2 🟢 Config cache WORKS — verified; do not "fix" it

Two failure hypotheses were tested against Gradle 9.5.1 and **disproven**:
- `onlyIf { ext… }` capturing the extension does NOT break CC storage — `KmpSsotExtension` is
  a managed type and serializes cleanly (`stored` → `Reusing configuration cache`).
- `autoDetectLocales` reading the filesystem in a plain `provider {}` IS tracked — adding
  `values-fr/` invalidated the entry with `directory '…/composeResources' has changed`.

CC is a strength. The only residual is stylistic (a `ValueSource` would make the input
explicit). **Any "fix CC" change here is a regression risk with zero payoff.** Isolated
Projects remains genuinely incompatible (root reaches into subprojects) — separate issue, §4.2.

### 2.3 🔴 PROVEN — the working tree fails its own `validatePlugins`; CI would be red

Live run in this audit:

```
Execution failed for task ':validatePlugins'.
> Type 'io.github.yuroyami.kmpssot.GenerateIoWorkerTask' must be annotated either with
  @CacheableTask or with @DisableCachingByDefault.
```

`GenerateIoWorkerTask` ([GenerateIoWorkerTask.kt:20](src/main/kotlin/io/github/yuroyami/kmpssot/GenerateIoWorkerTask.kt#L20))
has `@Input`/`@OutputDirectory` but no cacheability annotation. Every other task in the plugin
carries one. `./gradlew build validatePlugins` — the exact CI command — exits 1. Unit tests
themselves are green (50/50). One-line fix (`@CacheableTask` fits: pure codegen, declared
inputs/outputs), but it means **HEAD is currently unreleasable**.

### 2.4 🟠 PROVEN — published artifact is Java-21 bytecode; README claims "JDK 17+"

`javap` on the compiled output: `major version: 65` (= Java 21), driven by
`java.toolchain = 21` ([build.gradle.kts:14](build.gradle.kts#L14)). A consumer whose Gradle
daemon runs on JDK 17 — still the most common Android setup — gets
`UnsupportedClassVersionError` the moment the plugin class loads. The README's
"Requirements: JDK 17+" is false as shipped. Either target 17 bytecode (compile on 21, set
`jvmTarget`/`options.release` = 17) or change the documented floor to 21. Targeting 17 is the
consumer-friendly choice and costs nothing (no 21-only APIs are used).

---

## 3. CONFIRMED / LIKELY findings (inspection; ordered by blast radius)

### 3.1 🟠 CONFIRMED — Podfile auto-detect takes the FIRST local pod; wrong-pod rename corrupts unrelated code

`detectPodSharedModule` returns the **first** `POD_LINE` match whose name equals its path tail
([SharedModuleRewrite.kt:24](src/main/kotlin/io/github/yuroyami/kmpssot/SharedModuleRewrite.kt#L24)).
A Podfile with more than one local dev-pod, e.g.

```ruby
pod 'Utils',  :path => '../Utils'     # ← detected as the "old shared module"
pod 'shared', :path => '../shared'
```

with `kmpSsot { sharedModule = "composeApp" }` detects `oldName = "Utils"`, then rewrites the
`Utils` pod line to `composeApp` **and rewrites every `import Utils` in the Swift tree to
`import composeApp`**. That is corruption of an unrelated dependency, with backups as the only
safety net. Fix: when more than one candidate local pod exists, refuse to guess — warn and
require `oldSharedModuleName`. (Also: `syncSharedModuleReferences` logs
"Shared module references migrated" even when zero files changed —
[SyncIosConfigTask.kt:116](src/main/kotlin/io/github/yuroyami/kmpssot/SyncIosConfigTask.kt#L116) — misleading.)

### 3.2 🟠 LIKELY — `ic_launcher.png` collides with template `ic_launcher.webp` → resource-merge failure

`SyncAndroidLogoTask` writes `mipmap-*/ic_launcher.png` + `ic_launcher_round.png`
([SyncAndroidLogoTask.kt:119](src/main/kotlin/io/github/yuroyami/kmpssot/SyncAndroidLogoTask.kt#L119)).
Current Android Studio / KMP wizard templates ship those same resources as **`.webp`** (and an
`anydpi-v26` XML). Two files named `ic_launcher.*` in the same `mipmap-*` bucket is a duplicate
resource — AAPT2 fails the merge. So on a fresh-template app the first logo sync **breaks the
Android build** until the user manually deletes the webps. Nothing warns; the cleanup task only
knows two ancient files ([CleanupLegacyAppLogoArtifactsTask.kt:38](src/main/kotlin/io/github/yuroyami/kmpssot/CleanupLegacyAppLogoArtifactsTask.kt#L38)).
Fix: pre-scan for same-stem conflicts (`.webp`, stray drawable XMLs), warn with the exact list,
and extend the cleanup task to remove them (they're template-owned launcher assets being
replaced by the plugin's — same ownership argument as the legacy cleanup).

### 3.3 🟠 CONFIRMED — iOS logo task clobbers a user-owned `Contents.json` with no backup, orphaning existing icons

`SyncIosLogoTask` overwrites `AppIcon.appiconset/Contents.json` with its single-universal
variant using `backup = false` ([SyncIosLogoTask.kt:95](src/main/kotlin/io/github/yuroyami/kmpssot/SyncIosLogoTask.kt#L95)).
On first contact with an existing project that catalog is **user-owned** (often a full
multi-size set). The plugin's own backup philosophy ("copy a user-owned file before the first
rewrite" — [KmpSsotExtension.kt:196](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotExtension.kt#L196))
is violated at exactly the spot where data loss is a redesign away. Old PNGs are left orphaned
(Xcode "unassigned children" warnings). Fix: honour `backupBeforeRewrite` for the first
`Contents.json` overwrite + warn about (or prune) unreferenced icon files.

### 3.4 🟠 CONFIRMED — `versionCodeOverride` without `versionName` is a silent no-op on BOTH platforms

- Android: the whole version block is gated on `ext.versionName.isPresent`
  ([KmpSsotPlugin.kt:408](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L408)).
- iOS: `versionCode` is passed to the rewriter only when `versionName.isPresent`, and
  `rewritePbxproj` itself only writes `CURRENT_PROJECT_VERSION` inside the
  `versionName != null` branch ([SyncIosConfigTask.kt:76](src/main/kotlin/io/github/yuroyami/kmpssot/SyncIosConfigTask.kt#L76),
  [PbxprojRewrite.kt:46](src/main/kotlin/io/github/yuroyami/kmpssot/PbxprojRewrite.kt#L46)).

Someone bumping only the build number gets nothing, silently. Gate on
`versionName.isPresent || versionCodeOverride.isPresent` and decouple the two keys in the
rewriter.

### 3.5 🟠 CONFIRMED — SSOT authority is inconsistent: classic Android modules override the "single source"

`wireAndroidApp`/`wireAndroidLibrary` run eagerly inside `plugins.withId` — before the
module's own `android { }` block — so module-local `applicationId`/`versionName`/`compileSdk`
**win over the SSOT** ([KmpSsotPlugin.kt:401](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L401)).
The KMP-native library path uses `finalizeDsl`, where the **SSOT wins**
([KmpAndroidLibraryWiring.kt:46](src/main/kotlin/io/github/yuroyami/kmpssot/KmpAndroidLibraryWiring.kt#L46)).
Same plugin, opposite precedence per module shape. Either move classic wiring to
`androidComponents.finalizeDsl` (in an isolated class, per §1) or document loudly that the
plugin only fills gaps for classic modules.

### 3.6 🟠 CONFIRMED — region-qualified locales propagate an Android-only tag into iOS `knownRegions`

`autoDetectLocales` strips `values-` and passes the raw tag to both platforms
([KmpSsotPlugin.kt:245](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L245)).
Compose resources use Android-style region qualifiers: `values-pt-rBR` → tag `pt-rBR`.
Android `resourceConfigurations` accepts `pt-rBR`; iOS `knownRegions` needs `pt-BR`. The
pbxproj ends up with a bogus region. Same for `values-b+sr+Latn` (BCP-47 `b+` syntax). Fix: a
pure mapping fn (`pt-rBR`→`pt-BR`, `b+sr+Latn`→`sr-Latn`) applied on the iOS side only, plus a
filter so non-locale qualifier dirs never leak in.

### 3.7 🟠 CONFIRMED — non-CocoaPods iOS projects never auto-sync from Gradle

`hookIosFrameworkTasks` matches `linkPod*FrameworkIos*` + `embedAndSignAppleFrameworkForXcode`
([KmpSsotPlugin.kt:376](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L376)).
Plain `binaries.framework()` projects (the non-CocoaPods direction KMP is moving) produce
`linkReleaseFrameworkIosArm64` etc. — unmatched. They sync only via the embed task, which runs
inside an Xcode build — so `./gradlew build` on CI never syncs iOS. Add the non-Pod
`link{Release,Debug}FrameworkIos*` matchers (and consider `assemble*XCFramework`).

### 3.8 🟡 CONFIRMED — `javaVersion` sets Java compileOptions but not Kotlin `jvmTarget`

`applyJavaVersion` writes only `compileOptions` ([KmpSsotPlugin.kt:460](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L460)).
Kotlin keeps its own default → the exact "Inconsistent JVM-target compatibility" error this
knob exists to kill. Set Kotlin `jvmTarget` alongside (KGP-guarded), or wire a JVM toolchain.

### 3.9 🟡 CONFIRMED — deprecated `resourceConfigurations` on the AGP the plugin pins

Locale propagation uses `defaultConfig.resourceConfigurations`
([KmpSsotPlugin.kt:417](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L417), [:442](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L442))
— deprecated in AGP 9 (catalog pins 9.2.1) in favour of `androidResources.localeFilters`.
Consumers see deprecation warnings today; the API will vanish. Version-gate the new path.
Note the current call is also **additive only** — stale locales are never removed, so the
list isn't authoritative.

### 3.10 🟡 CONFIRMED — changing `ioWorkerPackage` leaves the old generated file → duplicate `kmpSsotOffload` → JS compile error

`GenerateIoWorkerTask.generate` writes the new package path but never cleans `outputDir`
([GenerateIoWorkerTask.kt:42](src/main/kotlin/io/github/yuroyami/kmpssot/GenerateIoWorkerTask.kt#L42)).
Gradle does not auto-delete stale files inside an `@OutputDirectory` on re-execution. Two
files, two top-level `kmpSsotOffload` declarations, one source set → "Conflicting overloads"
compile failure with no hint of the cause. Fix: wipe `outputDir` at the start of the action
(plugin-owned build dir — safe).

### 3.11 🟡 CONFIRMED — logo validation fires even when `propagateLogo = false`

The FG/BG pairing checks in `afterEvaluate` are not gated on `propagateLogo`
([KmpSsotPlugin.kt:101](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L101)).
Setting a foreground while disabling logo propagation still fails the build. The sync tasks
themselves gate on the toggle; validation should too.

### 3.12 🟡 CONFIRMED — every `com.android.application` module gets the same `applicationId`

`wireAndroidApp` applies to **all** application subprojects
([KmpSsotPlugin.kt:134](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L134)) —
a phone + Wear/TV pair collides on `applicationId` (install-blocking). Meanwhile the logo
pipeline scopes to `androidAppModule` only. At minimum warn when ≥2 application modules are
wired; per-module overlays are the real feature (FEATURES B1.4).

### 3.13 🟢 Minor (all CONFIRMED)

- `check(target == target.rootProject)` throws `IllegalStateException`; `GradleException`
  frames it as user error, not plugin crash ([KmpSsotPlugin.kt:21](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L21)).
- `writeAtomically`'s comment claims a crashed temp file is "cleaned up next run via
  overwrite" — false: `createTempFile` generates a fresh random name each run; orphans persist
  ([RewriteSafety.kt:74](src/main/kotlin/io/github/yuroyami/kmpssot/RewriteSafety.kt#L74)). Sweep `*.kmpssot.bak.tmp` siblings on entry, or fix the comment.
- Back-to-back plist inserts produce a blank line between entries (each append emits a
  trailing `\n`, the next prepends `\n<indent>`) — cosmetic, but the module promises "faithful,
  minimal diff" ([PlistSanitize.kt:201](src/main/kotlin/io/github/yuroyami/kmpssot/PlistSanitize.kt#L201)).
- `knownRegions` replacement hardcodes tab indentation ([PbxprojRewrite.kt:72](src/main/kotlin/io/github/yuroyami/kmpssot/PbxprojRewrite.kt#L72)).
- `PACKAGE_NAME_RE` accepts Kotlin hard keywords (`web { ioWorkerPackage = "my.fun.gen" }`
  generates an uncompilable file) ([KmpSsotPlugin.kt:469](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L469)).
- `kmpSsotVerify` reports identity + iOS files only — nothing about `android { }` SDK levels,
  `javaVersion`, logo config, or interop/web toggles ([KmpSsotVerifyTask.kt:38](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotVerifyTask.kt#L38)).
- `MIN_GRADLE = "8.5"` is claimed, only 9.5.1 is ever exercised (no TestKit version matrix).
- CI never runs with `--configuration-cache` — the verified-good CC state (§2.2) is unlocked
  against regressions. `com.gradle.plugin-publish` 1.3.0 is one patch behind.
- Generated Blob-worker never calls `URL.revokeObjectURL` — one leaked object URL per
  `kmpSsotOffload` call ([IoWorkerGen.kt:45](src/main/kotlin/io/github/yuroyami/kmpssot/IoWorkerGen.kt#L45)); no timeout either.

---

## 4. Architecture — the revolutionary remakes

### 4.1 ★ Stop rewriting pbxproj. Emit one xcconfig. (Permanently retires §2.1's whole class.)

Write a generated `kmpssot.xcconfig` (`MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`,
`PRODUCT_NAME`, bundle id) into `build/generated/kmpssot/`; the consumer includes it from the
**app target's** base configuration once. Per-target by construction, zero pbxproj edits, no
backups needed, diff-free, IP/CC-trivial. Ship behind `iosStrategy = XCCONFIG | PBXPROJ` with
the doctor verifying the include. This should be the next major's flagship.

### 4.2 ★ Settings-plugin + BuildService → Isolated-Projects compatibility

Root plugin reaching into `subprojects { }` is the exact pattern IP forbids. Flip it: a
`Plugin<Settings>` resolves the SSOT once into a `BuildService`; a tiny per-project plugin
pulls values locally. Also fixes §3.5 (authority becomes local and explicit).

### 4.3 ★ Generated-source model everywhere

Everything generated should land in `build/generated/kmpssot/**` and be wired via source
sets/res dirs — the IO-worker path already does this correctly; the Android icon tree
(22 files into `src/main/res/`) and the appiconset should follow. Clean `git status`, real
incrementality, no template-file collisions (§3.2 disappears structurally).

### 4.4 ★ Close the loop to runtime — `KmpSsotBuildInfo` codegen into `commonMain`

The plugin already knows appName/versionName/versionCode/bundleIds/locales. Emit them as a
generated `object KmpSsotBuildInfo` so the About screen, crash tags, and analytics read the
same truth the build writes. Highest-value new feature per line of code; pairs with §4.3.

### 4.5 DSL/API revamp

- **Aggregate tasks**: `kmpSsotSync` (+ `kmpSsotSyncIos` / `kmpSsotSyncAndroid`) — today users
  must know six task names.
- **`kmpSsotDoctor`** — one command validating the full setup (manifest placeholder present?
  plist keys point at `$(…)`? webp collisions? multiple app modules? KGP visible? region tags
  mappable?) with pass/fail + fix hints. `kmpSsotVerify` is the seed.
- **Gradle Problems API** for every warning the rewriters emit (build scans / IDE surface).
- **Naming coherence** — `syncIosConfig` / `sanitizeIosProject` / `kmpSsotVerify` /
  `cleanupLegacyAppLogoArtifacts` / `generateKmpSsotIoWorkerJs` share no prefix; unify under
  `kmpSsot*` (keep old names as deprecated aliases for one minor).

---

## 5. Scope discipline

**Overdone / smuggled scope**
- `web { generateIoWorker }` is runtime coroutine infrastructure inside a configuration
  plugin, duplicating KiteCore's worker byte-for-byte on purpose ("fix any worker-protocol bug
  in BOTH" — [IoWorkerGen.kt:17](src/main/kotlin/io/github/yuroyami/kmpssot/IoWorkerGen.kt#L17)). Keep it if the
  "gap toolkit" identity is intentional, but say so in the README's scope section, and consider
  the runtime living in KiteCore with the plugin only *installing* it.
- `propagateInteropOptIns` **defaults ON** and silently opts every native compilation out of
  three experimental-API warning classes. Powerful, but a silent project-wide `@OptIn` is an
  opinionated default; `ExperimentalNativeApi` in particular hides real API-stability signal.
  Consider default-off, or defaulting to the cinterop marker only.
- The plist prolog normalizer (undoing JDK Transformer quirks) is clever but fragile surface
  bought for diff cosmetics; the xcconfig direction shrinks how much it matters.

**Underdone**
- `kmpSsotVerify` (§3.13), single-strategy iOS integration (§3.7), no aggregate/doctor tasks,
  no wasmJs worker, no desktop-target identity (Compose Desktop `nativeDistributions` is the
  same copy-paste problem this plugin exists to kill), no themed/monochrome Android 13 icon,
  no dark/tinted iOS 18 icon variants.

---

## 6. Test gaps (why §2.1/§3.x survived)

- **No multi-target pbxproj fixture** — the §2.1 hole. Add app+tests+extension, assert
  non-primary configs untouched.
- **No AGP applied anywhere** — `wireAndroidApp`, `KmpAndroidLibraryWiring`, `javaVersion`,
  locale wiring: all unexercised.
- **No `--configuration-cache` functional run** — the proven-good state isn't locked in.
- **No image-content assertions** — icon tasks proven only "doesn't crash"; assert dimensions,
  adaptive XML, opaque flattening, circle mask.
- **No end-to-end rename test** through `SyncIosConfigTask` (Podfile + Swift walk incl.
  `PRUNED_DIRS` behaviour); the multi-pod ambiguity (§3.1) has no test.
- **No TestKit Gradle-version matrix** (8.5 floor claimed, only 9.5.1 tested).

---

## 7. Prioritized roadmap

**P0 — red/corruption (before anything else)**
1. §2.3 `@CacheableTask` on `GenerateIoWorkerTask` (+ output-dir cleanup, §3.10).
2. §2.1 Target-scoped pbxproj rewrites + multi-target regression fixture.
3. §3.1 Multi-pod detection guard (refuse to guess).
4. §3.4 `versionCodeOverride`-alone fixed on both platforms.
5. §3.2 webp/template collision pre-scan + cleanup coverage.
6. §3.3 `Contents.json` first-contact backup.

**P1 — compatibility & authority**
7. §2.4 Java-17 bytecode (or documented 21 floor — prefer 17).
8. §3.7 Non-Pod link-task hooks.
9. §3.6 iOS region-tag mapping + qualifier filter.
10. §3.8 Kotlin `jvmTarget` with `javaVersion`.
11. §3.9 `localeFilters`, version-gated.
12. §3.5 Classic-module authority via `finalizeDsl` (isolated class) or loud docs.
13. §3.11/§3.12/§3.13 batch (validation gating, multi-app warn, ISE→GradleException, verify
    coverage, log truthfulness, stale-tmp sweep, plist blank line, keyword package check).

**P2 — architecture & flagship features**
14. §4.4 `KmpSsotBuildInfo` codegen. 15. §4.5 aggregate tasks + doctor + Problems API.
16. §4.3 generated-res model for Android icons. 17. §4.1 xcconfig strategy (next major).

**P3 — platform & rigor**
18. §4.2 settings-plugin/IP. 19. §6 test matrix + CC lock-in + golden images. 20. Dokka,
binary-compatibility-validator, ktlint/detekt, CI polish.

---

*Bottom line: workmanship A-grade, risk concentrated in (a) a target-blind rewrite that richer
fixtures would have caught, (b) a red `validatePlugins` on HEAD, and (c) four first-contact
hazards (multi-pod rename, webp collision, Contents.json clobber, Java-21 bytecode) that all
bite precisely when a real, existing app adopts the plugin. Fix the P0 list and this jumps
from "careful but dangerous on real apps" to production-safe; ship §4.1+§4.4 and it becomes
the category-defining tool.*
