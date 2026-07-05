# FABLE5_AUDIT.md — Repair Guide for Opus 4.8

> **Execution status (2026-07-05): COMPLETE.** T01–T17 and P3.1 were carried out
> and committed to `main`; the plugin is at **1.7.0**, build + `validatePlugins`
> green, config-cache clean, **50 → 81 tests**. Remaining P3 rigor (Gradle-version
> matrix, golden image tests, Dokka / binary-compatibility-validator / ktlint) and
> all of **§P3.5** (xcconfig strategy, settings-plugin/Isolated-Projects,
> generated-res dir for Android icons, per-target iOS rules) are intentionally
> **not** done — they need explicit user sign-off per §0/§P3.5. The rest of this
> document is the original plan, retained as the record.

*Written by Claude Fable 5 after a full fresh-eyes audit of kmp-ssot (every source, test,
build, CI and doc file read; test suite executed; several findings proven by live runs).
This file is the **work order**: follow it top to bottom and the plugin goes from ~66% to
~95% of its horizon. Companion docs: [AUDIT.md](AUDIT.md) (evidence & reasoning),
[FEATURES.md](FEATURES.md) (feature bank). When this file and the README disagree, this file
wins — the README itself is one of the things being fixed.*

---

## 0. Ground rules (read first, non-negotiable)

1. **Work directly on `main`.** The user hates branches. No feature branches, no release
   branches. Commit in small, labeled increments straight to `main`.
2. **Verification command** after every task: `./gradlew build validatePlugins --stacktrace`.
   It must be GREEN before you move to the next task (it is currently RED — see T01).
   Don't pipe through `tail`/`head` when checking exit status — pipes mask the exit code.
3. **Version/changelog discipline**: this batch ships as **1.7.0**. Update `CHANGELOG.md`
   (Added/Fixed/Changed) and `gradle.properties` (`kmpSsot.version=1.7.0`) once at the end,
   and keep README synchronized with every behavior change you make.
4. **Style**: match the existing code. This codebase writes KDoc that explains *constraints
   and why*, not *what the next line does*. Pure logic goes in top-level internal functions
   in their own file with unit tests (see `PbxprojRewrite.kt`, `VersionCode.kt` as templates).
   Tasks stay thin.
5. **Every fix ships with its regression test.** The pattern of this codebase is
   pure-function extraction precisely so tests don't need Gradle; keep that.

### 0.1 Traps — things that look wrong but are VERIFIED CORRECT. Do not "fix".

- **Config cache works.** `onlyIf { ext.… }` capturing the extension (4 sites in
  `KmpSsotPlugin.kt`) serializes fine (managed type), and `autoDetectLocales` reading the
  filesystem inside `provider {}` IS invalidation-tracked by Gradle 9.x instrumentation.
  Both were experimentally verified. Do not rewrite them "for CC-safety"; you'd be churning
  verified-good code. (A `ValueSource` for locales is optional polish, not a fix.)
- **`@get:Internal` on all `SyncIosConfigTask`/`SanitizeIosProjectTask` properties +
  `outputs.upToDateWhen { false }`** is deliberate: these tasks rewrite user-owned files in
  place and must always run. Don't convert to `@Input`/`@OutputFile` — that turns a
  user-owned file into a task output Gradle may delete or cache.
- **`KGP_ON_CLASSPATH` reflective guard** (`KmpSsotPlugin.kt:478`) and **the AGP-type
  isolation pattern** (`KmpAndroidLibraryWiring` being its own class so AGP types never
  enter `KmpSsotPlugin`'s method descriptors) are load-bearing. Any new AGP- or KGP-typed
  wiring you add MUST live in its own class/file the same way, or the plugin will fail to
  decorate in AGP-less/KGP-less builds (the functional tests run without AGP — they will
  catch you).
- **`writeTextSafely`/`writeAtomically`/`backupOnce`** semantics (idempotent no-op,
  write-once backup, temp+atomic-move) are correct and tested. Extend, don't rewrite.
- **The `srcDir(genTask.flatMap { it.outputDir })`** wiring for generated sources is the
  correct dependency-carrying idiom. Reuse it for any new codegen.
- The **eager `.get()` calls on `ext` inside `subprojects { plugins.withId { } }`** are safe
  *sequencing-wise* (root project fully evaluates before subprojects), so don't add
  afterEvaluate wrappers for ordering reasons. The *precedence* problem they cause is real
  and handled deliberately in T12 — via `finalizeDsl`, not via afterEvaluate.

---

## 1. P0 — Red build & corruption class (do these first, in order)

### T01 — Fix `validatePlugins` (HEAD is currently red)

**Proven by live run:** `./gradlew build validatePlugins` exits 1:
`Type 'GenerateIoWorkerTask' must be annotated either with @CacheableTask or with
@DisableCachingByDefault.`

**Fix** in [GenerateIoWorkerTask.kt](src/main/kotlin/io/github/yuroyami/kmpssot/GenerateIoWorkerTask.kt):
- Annotate the class `@CacheableTask` (it is pure codegen: `@Input workerPackage`,
  `@Input dryRun`, `@OutputDirectory outputDir` — a textbook cacheable task).
- While in the file, do **T02** (same file, same test run).

**Accept:** `./gradlew validatePlugins` green.

### T02 — Clean stale generated worker files on package change

**Confirmed:** changing `web { ioWorkerPackage }` leaves the previously generated file in
`outputDir` (Gradle does not clean `@OutputDirectory` contents on re-execution), producing
two top-level `kmpSsotOffload` declarations in one source set → "Conflicting overloads"
compile error with no hint of the cause.

**Fix:** at the start of `generate()`, when not in dry-run, delete the contents of
`outputDir.get().asFile` (`deleteRecursively()` then recreate). It is a plugin-owned
directory under `build/` — safe by construction.

**Accept:** new functional-test step — run `generateKmpSsotIoWorkerJs` with package A, then
with package B, assert only B's file exists. (Extend the existing
`web generateIoWorker wires and generates in a real KMP js module` test in
[KmpSsotPluginFunctionalTest.kt](src/test/kotlin/io/github/yuroyami/kmpssot/KmpSsotPluginFunctionalTest.kt)
rather than duplicating the expensive fixture.)

### T03 — Target-scoped pbxproj rewrites (the #1 proven bug)

**Proven by live experiment** (see AUDIT §2.1): all identity keys are rewritten in EVERY
`XCBuildConfiguration`, so unit-test targets and app extensions get the app's
`PRODUCT_NAME`/`PRODUCT_BUNDLE_IDENTIFIER` → broken test linkage, App-Store-rejectable
extension bundle ids. This is the highest-blast-radius defect in the plugin.

**Design (implement exactly this shape):**

New file `PbxprojTargetScope.kt` with pure, unit-testable functions:

1. `internal fun findObjectSpans(text: String): Map<String, IntRange>` — scan the file for
   top-level object entries inside the `objects = { … }` dict. Implementation: walk the
   text with a brace-depth counter (respecting double-quoted strings and `/* */` comments);
   at the depth where object entries live, match entry starts with
   `Regex("""([0-9A-F]{24})(?:\s*/\*[^*]*\*/)?\s*=\s*\{""")` and record the full
   brace-balanced span of each object. No full parser — just spans.
2. `internal fun applicationBuildConfigSpans(text: String): List<IntRange>` —
   - ids of objects whose span contains `isa = PBXNativeTarget` **and**
     `productType = "com.apple.product-type.application"`;
   - for each, extract `buildConfigurationList = <ID>`;
   - in that XCConfigurationList object's span, extract the ids inside
     `buildConfigurations = ( … )`;
   - return the spans of those XCBuildConfiguration objects.
3. Change `rewritePbxproj` ([PbxprojRewrite.kt](src/main/kotlin/io/github/yuroyami/kmpssot/PbxprojRewrite.kt)):
   - Compute `applicationBuildConfigSpans`. If **non-empty**: apply the per-key
     `settingRegex` replacements **only inside those spans** (replace back-to-front so
     offsets stay valid).
   - If **empty** (flat fixtures, exotic projects): fall back to the current global
     behavior **and append a warning**: "no application target found in pbxproj — applying
     settings globally; multi-target projects should verify test/extension targets."
   - `knownRegions` stays global (it is project-level, single occurrence). Version keys
     (`MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`) scope to app-target spans like the
     rest.
4. Keep the existing quoting/escaping/literal-replacement machinery untouched.

**Accept:**
- New `PbxprojTargetScopeTest` covering: span scanner on a realistic pbxproj snippet
  (nested braces, quoted strings containing `{`/`;`, `/* … */` comments); app-target
  resolution; multi-app-target files (both scoped).
- New test in `PbxprojRewriteTest`: fixture with app + `iosAppTests`
  (`productType = "com.apple.product-type.bundle.unit-test"`, distinct
  `PRODUCT_BUNDLE_IDENTIFIER = com.demo.app.tests`) + widget extension
  (`com.apple.product-type.app-extension`, `com.demo.app.widget`) — assert after rewrite:
  app configs updated; **tests and widget configs byte-identical**.
- Existing flat-fixture tests still pass via the fallback (they'll now also emit the
  fallback warning — assert it where convenient).
- README "Multi-target iOS projects" section rewritten: scoping is now automatic; the
  fallback and its warning documented; the stale claim that only `PRODUCT_BUNDLE_IDENTIFIER`
  is affected corrected.

### T04 — `versionCodeOverride` alone must work (both platforms)

**Confirmed:** with `versionCodeOverride` set and `versionName` unset, nothing is written
anywhere:
- Android: gate is `ext.versionName.isPresent` ([KmpSsotPlugin.kt:408](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L408)).
- iOS: task passes `versionCode` only when `versionName.isPresent`
  ([SyncIosConfigTask.kt:76-77](src/main/kotlin/io/github/yuroyami/kmpssot/SyncIosConfigTask.kt#L76)), and
  `rewritePbxproj` only writes `CURRENT_PROJECT_VERSION` inside the `versionName != null`
  branch ([PbxprojRewrite.kt:46-51](src/main/kotlin/io/github/yuroyami/kmpssot/PbxprojRewrite.kt#L46)).

**Fix:**
- `rewritePbxproj`: hoist the `versionCode` block out — `if (versionCode != null)` rewrites
  `CURRENT_PROJECT_VERSION` regardless of `versionName`.
- `SyncIosConfigTask.syncPbxproj`: pass
  `versionCode = if (propagateVersion.get() && versionCode.isPresent) versionCode.get() else null`
  (the task property is fed from `ext.versionCode`, which already resolves override-first).
- `wireAndroidApp`: split the gate —
  `if (propagateVersion) { versionName only when versionName.isPresent; versionCode from
  ext.versionCode.orNull when non-null }`.

**Accept:** unit test (rewriter: versionCode-only input updates CURRENT_PROJECT_VERSION and
nothing else) + functional test (`versionCodeOverride = 42`, no `versionName` → pbxproj
contains `CURRENT_PROJECT_VERSION = 42;`, `MARKETING_VERSION` untouched).

### T05 — Podfile auto-detect must refuse to guess among multiple local pods

**Confirmed corruption path** (AUDIT §3.1): `detectPodSharedModule` returns the FIRST
`pod 'X', :path => '../X'` match ([SharedModuleRewrite.kt:24](src/main/kotlin/io/github/yuroyami/kmpssot/SharedModuleRewrite.kt#L24)).
With two local dev-pods and the wrong one first, the plugin renames an unrelated pod and
rewrites `import Utils` → `import composeApp` across the Swift tree.

**Fix:**
- Change detection to collect **all** matches where name == path tail:
  `internal fun detectPodSharedModuleCandidates(podfileText: String): List<String>`
  (keep `detectPodSharedModule` as a thin `singleOrNull()` wrapper for API stability).
- In `SyncIosConfigTask.syncSharedModuleReferences`: if candidates.size > 1 and
  `oldSharedModuleName` is unset → log warning listing the candidates and instructing
  `oldSharedModuleName = "…"`, then **return without rewriting**.
- Bonus honesty fix in the same function: only log
  "Shared module references migrated …" when `rewritePodfile`/`rewriteSwiftImports`
  actually wrote something (have them return a Boolean/count — the info is already there).

**Accept:** unit test (two local pods → candidates size 2; single → size 1) + functional
test (two-pod Podfile, no explicit old name → files untouched, warning printed).

### T06 — Android template icon collision (webp) must not break the consumer build

**Likely-frequent failure** (AUDIT §3.2): plugin writes `mipmap-*/ic_launcher.png` while
current templates ship `mipmap-*/ic_launcher.webp` → duplicate resource → AAPT2 merge
failure on the first sync in a fresh project.

**Fix (two layers):**
1. `SyncAndroidLogoTask`: before writing, scan each `mipmap-*` dir for files with the same
   stem as any output but a different extension (`ic_launcher.webp`,
   `ic_launcher_round.webp`, `ic_launcher_foreground.webp`, `ic_launcher_background.webp`,
   also `.jpg`/`.xml` stems in mipmap dirs). If found: emit ONE clear warning listing exact
   paths and the remedy ("delete these template icons or set
   `cleanupLegacyLogoArtifacts = true`").
2. Extend [CleanupLegacyAppLogoArtifactsTask.kt](src/main/kotlin/io/github/yuroyami/kmpssot/CleanupLegacyAppLogoArtifactsTask.kt)
   to also delete those colliding launcher-stem files under `mipmap-*/` (extend the list; the
   ownership argument is identical — they are template launcher assets the plugin replaces).
   Keep `drawable/ic_launcher.xml` + `values/ic_launcher_background.xml` entries.

**Accept:** functional or unit-level test: seed `mipmap-hdpi/ic_launcher.webp`, run cleanup
(dry-run and real), assert listed/removed; sync task warning asserted on seeded fixture.
README "Migrating older versions" section extended to cover template webp collisions.

### T07 — Stop clobbering user `Contents.json` without backup; flag orphaned icons

**Confirmed** (AUDIT §3.3): `SyncIosLogoTask` overwrites the appiconset's `Contents.json`
with `backup = false` ([SyncIosLogoTask.kt:95](src/main/kotlin/io/github/yuroyami/kmpssot/SyncIosLogoTask.kt#L95)) —
on first contact that file is user-owned (often a full multi-size catalog).

**Fix:**
- Add `@get:Input abstract val backup: Property<Boolean>` to the task, wire from
  `ext.backupBeforeRewrite` in `registerSyncIosLogoTask`.
- `Contents.json` write becomes `backup = backup.get()` (AppIcon-1024.png stays
  no-backup — it's plugin-named output).
- After writing, list `*.png` files in the appiconset not in `OUTPUT_FILE_NAMES`; if any,
  warn: "N icon file(s) no longer referenced by Contents.json: … — delete them to silence
  Xcode's unassigned-children warning."

**Accept:** unit-ish test via a tmp dir invoking the task action (or extend the functional
fixture): pre-existing `Contents.json` gets a `.kmpssot.bak`; orphan PNG produces the
warning. Note: `@CacheableTask` + new `@Input` is fine; do NOT model the `.bak` as a task
output.

---

## 2. P1 — Compatibility & authority

### T08 — Ship Java-17-loadable bytecode (proven major=65 today)

**Proven:** `javap` shows `major version: 65` (Java 21) because of the toolchain-21 block in
[build.gradle.kts:13-17](build.gradle.kts#L13). README claims "JDK 17+". A JDK-17 Gradle
daemon dies with `UnsupportedClassVersionError` loading the plugin.

**Fix** in `build.gradle.kts`: keep compiling on 21 but target 17 —
```kotlin
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
tasks.withType<JavaCompile>().configureEach { options.release = 17 }
```
(If the embedded kotlin-dsl compiler objects to the `kotlin {}` accessor shape, fall back to
`tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }`.)
kotlin-dsl also pins its own jvmTarget from the toolchain on some versions — verify the
RESULT, not the config: add a small CI-friendly check task (or at minimum run
`javap -v build/classes/kotlin/main/io/github/yuroyami/kmpssot/VersionCodeKt.class | grep major`)
expecting **61**. If some constraint truly forces 21, then instead update README/docs to
"JDK 21+" — but 17 is strongly preferred and nothing in the source uses 21-only APIs.

**Accept:** major version 61 in the built jar's classes; README requirements line matches
reality either way.

### T09 — Hook non-CocoaPods iOS link tasks

**Confirmed** (AUDIT §3.7): filter matches only `linkPod*FrameworkIos*` +
`embedAndSignAppleFrameworkForXcode` ([KmpSsotPlugin.kt:376-380](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L376)).
Plain `binaries.framework()` projects produce `linkReleaseFrameworkIosArm64` /
`linkDebugFrameworkIosSimulatorArm64` / … — never hooked, so `./gradlew build` on CI never
syncs iOS for them.

**Fix:** broaden the predicate:
```kotlin
val iosTaskFilter: (Task) -> Boolean = {
    (it.name.startsWith("link") && it.name.contains("FrameworkIos")) ||
        it.name == "embedAndSignAppleFrameworkForXcode" ||
        (it.name.startsWith("assemble") && it.name.endsWith("XCFramework"))
}
```
This subsumes the two Pod prefixes (delete them). Name-based matching is deliberate (type-based
would need KGP types in the method signature — see Traps).

**Accept:** unit-style test of the predicate over a name list:
`linkPodReleaseFrameworkIosArm64`, `linkReleaseFrameworkIosArm64`,
`linkDebugFrameworkIosSimulatorArm64`, `embedAndSignAppleFrameworkForXcode`,
`assembleSharedReleaseXCFramework` → true; `linkReleaseFrameworkMacosArm64`,
`linkReleaseExecutableIosArm64`(!), `compileKotlinIosArm64` → false. (Extract the predicate
to a pure function to test it.)

### T10 — Map region-qualified locale tags for iOS; filter non-locale dirs

**Confirmed** (AUDIT §3.6): `values-pt-rBR` propagates `pt-rBR` verbatim into `knownRegions`
(invalid — Xcode wants `pt-BR`); `values-b+sr+Latn` → `b+sr+Latn` (invalid).

**Fix:** new pure file `LocaleTags.kt`:
- `internal fun androidTagToAppleTag(tag: String): String` — `pt-rBR`→`pt-BR`;
  `b+sr+Latn`→`sr-Latn` (strip `b+`, `+`→`-`); plain tags pass through.
- `internal fun looksLikeLocaleQualifier(tag: String): Boolean` — accept
  `[a-z]{2,3}`, `[a-z]{2,3}-r[A-Z]{2}`, `b\+…` shapes; reject anything else so a stray
  `values-foo` directory never becomes a "locale".
- Apply `looksLikeLocaleQualifier` in `autoDetectLocales` ([KmpSsotPlugin.kt:245](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L245))
  (auto-detected only — an explicit `locales` list is the user's own business), and
  `androidTagToAppleTag` at the iOS boundary — in `SyncIosConfigTask` where `locales` feeds
  `rewritePbxproj` (Android keeps raw Android-style tags).

**Accept:** `LocaleTagsTest` (mapping table incl. pass-through, region, `b+` script,
rejection cases) + a `rewritePbxproj` assertion that input `["en","pt-rBR"]` lands as
`pt-BR` in `knownRegions`.

### T11 — `javaVersion` must also set Kotlin `jvmTarget`

**Confirmed** (AUDIT §3.8): only `compileOptions` is set; Kotlin keeps its default →
"Inconsistent JVM-target compatibility" — the exact error the knob should kill.

**Fix:** where `applyJavaVersion` runs for a module, additionally (KGP-guarded, in a
separate `KotlinJvmTargetWiring` object/file per the isolation trap):
`project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java)
.configureEach { compilerOptions.jvmTarget.set(JvmTarget.fromTarget(jv.toString())) }` —
lazy, covers android-kotlin and kotlin-jvm compilations in that module. Skip silently when
`KGP_ON_CLASSPATH` is false (warning already exists for the other KGP features). Note
`JvmTarget.fromTarget("17")` wants the string form Gradle's `JavaVersion` gives via
`.toString()` for 9+ ("17"), but "1.8" for 8 — use `JavaVersion.toVersion(n).toString()`
and unit-test 8 and 17 inputs.

**Accept:** pure mapping test (8→"1.8", 17→"17", 21→"21"); functional smoke optional.

### T12 — Make classic Android modules SSOT-authoritative (align with KMP-library path)

**Confirmed inconsistency** (AUDIT §3.5): classic app/library wiring is eager
(module-local values win); KMP-library wiring uses `finalizeDsl` (SSOT wins).

**Fix:** move `wireAndroidApp`/`wireAndroidLibrary` bodies into a new isolated
`ClassicAndroidWiring` object (AGP-typed — own file, per the trap) using
`project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java).finalizeDsl { … }`
(and `LibraryAndroidComponentsExtension` for libraries). Same property logic, now running
after the module's own `android { }` block → SSOT wins uniformly.

**Document the semantics change prominently** in CHANGELOG ("Changed (behaviour)") and the
README Scope section: "SSOT values now override module-local declarations for ALL Android
module shapes; leave a field unset in `kmpSsot { }` to keep the module's own value."
That sentence is the contract; it must be true everywhere after this task.

**Accept:** compile + existing tests green (no AGP in test fixtures, so coverage here is
compile-level; a real AGP functional fixture is T18). Manual smoke on a consumer project if
available.

### T13 — `localeFilters` for AGP 9 with graceful AGP 8 fallback

**Confirmed** (AUDIT §3.9): `resourceConfigurations` is deprecated on the AGP the catalog
pins (9.2.1).

**Fix:** inside the (now `finalizeDsl`-based, T12) wiring:
try `android.androidResources.localeFilters.addAll(l)` first; on `NoSuchMethodError`/
`Throwable` from the accessor (older AGP at runtime), fall back to
`defaultConfig.resourceConfigurations.addAll(l)` wrapped in `@Suppress("DEPRECATION")`.
Because AGP is `compileOnly`, compile against the new API and guard the call with
`runCatching` — the catch IS the version gate.

**Accept:** compiles against AGP 9.2.1 with zero deprecation warnings on the new path;
fallback path unit-untestable without AGP 8 on classpath — document the guard inline.

### T14 — Small-fix batch (one commit, all CONFIRMED in AUDIT §3.11–§3.13)

1. **Gate logo validation on the toggle** ([KmpSsotPlugin.kt:101-123](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L101)):
   wrap the FG/BG pairing + hex checks in `if (ext.propagateLogo.get()) { … }`.
2. **Multi-app-module warning** — in `wireAndroidApp`, count wired application modules
   (plugin-level `AtomicInteger`/set); on the second one warn: "N application modules
   receive the same applicationId/version — per-module overlays are not yet supported."
3. **`check(...)` → `GradleException`** for root-only enforcement
   ([KmpSsotPlugin.kt:21](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L21)).
4. **Fix the false comment in `writeAtomically`** ([RewriteSafety.kt:74-78](src/main/kotlin/io/github/yuroyami/kmpssot/RewriteSafety.kt#L74))
   AND make it true: before staging, delete sibling files matching
   `"${target.name}.*${BACKUP_SUFFIX}.tmp"` (crash leftovers).
5. **Plist blank-line fix** ([PlistSanitize.kt:201-210](src/main/kotlin/io/github/yuroyami/kmpssot/PlistSanitize.kt#L201)):
   before appending, if the dict's last child is a `\n`-only text node, replace it instead
   of stacking a second newline. Assert exact output text in `PlistSanitizeTest` for a
   double-insert (4 SSOT keys → no blank lines between them).
6. **Reject Kotlin hard keywords in `ioWorkerPackage`** — extend the validation at
   [KmpSsotPlugin.kt:214-220](src/main/kotlin/io/github/yuroyami/kmpssot/KmpSsotPlugin.kt#L214) with a
   keyword set (`fun`, `val`, `var`, `object`, `class`, `in`, `is`, `as`, `if`, `else`,
   `when`, `for`, `while`, `do`, `return`, `null`, `true`, `false`, `typeof`, `package`,
   `interface`, `typealias`, `this`, `super`, `throw`, `try`).
7. **`kmpSsotVerify` Android coverage** — add lines for `android { }` SDK values,
   `javaVersion`, logo config (FG/BG/color set-ness), interop/web toggles. Keep it
   read-only.
8. **knownRegions indent preservation** ([PbxprojRewrite.kt:72-81](src/main/kotlin/io/github/yuroyami/kmpssot/PbxprojRewrite.kt#L72)):
   capture the whitespace run after the original `(` and reuse it instead of hardcoded
   `\n\t\t\t\t`; fall back to tabs when absent.
9. **Generated worker hygiene** ([IoWorkerGen.kt](src/main/kotlin/io/github/yuroyami/kmpssot/IoWorkerGen.kt)):
   capture the blob URL, `URL.revokeObjectURL(url)` after Worker construction; keep the
   duplicate-with-KiteCore comment in sync if the protocol changes (it doesn't here).
   Update `IoWorkerGenTest` accordingly.

**Accept:** all existing tests + new assertions green; `validatePlugins` green.

---

## 3. P2 — Flagship features (each is its own commit with README + CHANGELOG sections)

### T15 — `KmpSsotBuildInfo` runtime codegen (highest value per line)

New opt-in block:
```kotlin
kmpSsot { buildInfo { enabled = true; packageName = "com.acme.app" } }  // default pkg: kmpssot.generated
```
New `GenerateBuildInfoTask` (`@CacheableTask`, mirror `GenerateIoWorkerTask` incl. the T02
cleanup) emitting into `build/generated/kmpssot/commonMain/kotlin/<pkg>/KmpSsotBuildInfo.kt`:
```kotlin
public object KmpSsotBuildInfo {
    public const val appName: String = "…"
    public const val versionName: String = "…"
    public const val versionCode: Int = …
    public const val androidApplicationId: String = "…"   // "" when unset
    public const val iosBundleId: String = "…"             // "" when unset
    public val locales: List<String> = listOf(…)
}
```
Escape string literals properly (reuse a small `internal fun kotlinStringLiteral(s: String)`
— test `$`, `"`, `\`, newline). Wire onto the shared module's `commonMain` via the same
`srcDir(flatMap)` idiom inside the existing `plugins.withId("org.jetbrains.kotlin.multiplatform")`
block, KGP-guarded, `afterEvaluate` like `wireWebIoWorker` (source sets exist by then; gate
on `project.name == ext.sharedModule.get()` so only the shared module gets it).

**Accept:** pure-generator unit test (content, escaping, unset fields) + functional test à
la the IO-worker one asserting the file lands and contains the values.

### T16 — Aggregate tasks + `kmpSsotDoctor`

- Register `kmpSsotSync` (dependsOn sanitize, syncIosConfig, syncIosLogo, syncAndroidLogo),
  `kmpSsotSyncIos`, `kmpSsotSyncAndroid` — plain lifecycle tasks, group `kmp-ssot`.
- New `KmpSsotDoctorTask` (read-only, modeled on `KmpSsotVerifyTask`) checking, each with
  PASS/WARN/FAIL + one-line remedy:
  manifest has `${appName}` placeholder when `propagateAppName` (scan
  `${androidAppModule}/src/main/AndroidManifest.xml` for `android:label="${appName}"`);
  Info.plist SSOT keys point at `$(…)`; pbxproj exists & contains an application target
  (reuse T03's scanner); appiconset dir exists; webp collisions (reuse T06 scan);
  multiple application modules; locale tags all pass `looksLikeLocaleQualifier`;
  versionCode derivable; KGP visibility.
  Print a table; never throw (it's a doctor, not a gate).

**Accept:** functional test running `kmpSsotDoctor` on the iOS fixture asserting a PASS and
a deliberate FAIL line appear.

### T17 — README/docs truth pass

After T01–T16, rewrite the affected README sections so **every claim matches code**:
requirements (JDK per T08 outcome), multi-target section (T03), migration section (T06),
precedence contract (T12), region tags (T10), new tasks/features (T15/T16), and add a
"Scope & boundaries" paragraph naming the interop/web features explicitly as toolchain
gap-closers distinct from identity propagation. Update CHANGELOG for 1.7.0 with
Added/Fixed/Changed; bump `gradle.properties`.

---

## 4. P3 — Rigor (do as time allows, in this order)

1. **CI**: add `--configuration-cache` to the build job's Gradle invocation (locks in the
   verified-good CC state); bump `com.gradle.plugin-publish` to latest 1.3.x.
2. **TestKit Gradle matrix**: parameterize the functional tests over
   `GradleRunner.withGradleVersion` for `8.5` and current — or, if 8.5 fails for
   environmental reasons, change `MIN_GRADLE` and README to the floor you actually verify.
   The claim and the tests must agree.
3. **Golden icon tests**: feed a generated 64×64 two-color FG/BG through
   `SyncAndroidLogoTask`'s pure helpers (extract `padToSafeZone`/`coverCanvas`/
   `legacyComposite` to `ImageOps.kt` as internal functions first) and assert output
   dimensions + corner/center pixel values; same for the iOS composite flattening
   (alpha → white).
4. **Tooling**: Dokka on release, `binary-compatibility-validator`, ktlint or detekt +
   `.editorconfig` (match existing style: 4-space indent, ~120 col).
5. **Deferred architecture** (design notes in AUDIT §4 — do NOT start without the user):
   xcconfig `iosStrategy`, settings-plugin/BuildService for Isolated Projects, generated-res
   dir for Android icons, per-target iOS rules DSL.

---

## 5. Definition of done

- [ ] `./gradlew build validatePlugins --stacktrace` green (checked by reading the exit
      code, not a piped tail).
- [ ] All NEW tests listed in T02–T16 exist and pass; total suite grows from 50 to ≥ 75.
- [ ] Multi-target pbxproj fixture proves tests/extensions untouched (the audit's #1 bug is
      regression-locked).
- [ ] `javap` major version matches the README requirements line (61 ↔ "JDK 17+").
- [ ] CHANGELOG 1.7.0 section complete; `kmpSsot.version=1.7.0`; README truth pass done.
- [ ] No new AGP/KGP types leaked into `KmpSsotPlugin` method descriptors (functional tests
      without AGP still pass — they are the canary).
- [ ] Everything committed to `main` in reviewable increments. No branches.
