# kitessot: Fresh-Slate, Code-Truth Audit

- **Audit date:** 2026-07-14
- **Audited release:** `1.7.0`
- **Base revision:** `cce499f`
- **Scope:** every production source, test, build file, workflow, publication artifact,
  and repository document
- **Authority:** implementation first; repository prose was used only after behavior was
  reconstructed from code

## Post-audit remediation status: 2026-07-18 working tree

> **How to read this document now:** Sections 1–14 below are the preserved,
> point-in-time audit of release `1.7.0` at `cce499f`. Their “current” wording,
> release decision, maturity scores, line references, and 86-test baseline describe
> that audited snapshot: not this remediation worktree. This section is the only
> current-state overlay. The verification results in this overlay supersede the
> historical release decision; no historical finding text was rewritten after the
> implementation changed.

The working tree has been substantially remade around an explicit principle:
continuous Gradle configuration may be automatic, but mutation of user-owned source
must be opt-in, narrowly selected, fail closed, ownership-aware, and recoverable.
The identity model is also optional-by-construction: a capability validates only the
values it consumes, while a configured capability may not silently no-op.

### P0 remediation map

| Historical finding | Remedy present in the working tree | Current status |
|---|---|---|
| **P0-01: non-monotonic Android version codes** | `VersionCode.kt` accepts exactly canonical `x.y.z`, uses fixed-width monotonic encoding, rejects aliases/out-of-range segments, validates overrides in `1..2_100_000_000`, and can compare against `android.publishedVersionCode`. | **Closed;** boundary, monotonicity, overflow, override, and store-baseline regressions pass. |
| **P0-02: inferred Pod/Swift rename** | Migration defaults off and requires explicit validated old/new Swift module identifiers. Podfile inference is removed; legacy properties are compatibility inputs to the same validated model, not a validation bypass. | **Closed;** explicit, legacy-fallback, malformed-input, and no-op cases pass. |
| **P0-03: fail-open pbxproj rewrite** | The parser validates the complete project/target/configuration graph once. Rewriting is limited to selected application targets (or one unambiguous app target) and rejects missing, duplicate, malformed, garbage, or ambiguous structure without writing. | **Closed in code and representative Xcode-project fixtures;** an actual signed Xcode/App Store build remains external validation. |
| **P0-04: cache restore bypasses AppIcon backup** | Logo tasks are non-cacheable source installers. No-follow containment, checksum manifests, verified first-contact takeover, locks, bounded snapshots, create-only commit, concurrent-change detection, and rollback protect every batch. Diagnostics also verify the Android manifest references and selected Xcode catalog setting that consume the installed assets. | **Closed;** ownership, takeover, tamper, symlink, race, rollback, and platform-consumption tests pass. |
| **P0-05: release tag/version split** | Publication version is explicit; `verifyReleaseMetadata` requires exact `v<version>` and changelog agreement; remote publication requires signing material. CI stages, byte-compares, signs, checksums, and attests the complete payload/SBOM set. | **Closed locally;** metadata passed and the six-payload candidate was inspected. Ephemeral encrypted and unencrypted signing rehearsals passed; no remote publication is claimed. |
| **P0-06: compilation mutates Xcode state** | Normal compilation has no dependency on plist, pbxproj, Podfile, Swift, or icon installers. Apple source changes require an explicit named task after opt-in. | **Closed;** task-graph and source-non-mutation fixtures pass. |
| **P0-07: unsafe recursive generated-output cleanup** | Generated and source-installed outputs use separate ownership domains. Paths are root-contained and no-follow checked; traversal, file count, file size, total size, manifests, snapshots, and previews are bounded. Mutation is transactional rather than recursive deletion. | **Closed on the local filesystem suite;** CI defines Linux, macOS, and Windows coverage. |

### Broader P1/P2 remediation

| Audit area | Current implementation | Status / remaining work |
|---|---|---|
| **P1-01/04/05 and P2-12: model and selection** | Android and Apple build numbers are independent; identifiers, SDKs, paths, locale tags, opt-ins, worker packages, and BuildConfig fields have bounded centralized validation. Gradle projects, Android apps, Xcode targets, web targets, Native opt-in projects, resources, and Swift modules have separate selectors. The root project participates in discovery. | Closed for the current one-application model. Deprecated strings remain validated compatibility fallbacks; per-target identity overlays are a deliberate 2.0 boundary. |
| **P1-02/07/08/20: defaults and lifecycle** | Logo installation, iOS sync/sanitization, shared-module migration, compiler opt-ins, and Android locale filtering default off. The complete managed model is finalized after root configuration; late cross-project mutation cannot change behavior by evaluation order. BuildConfig cache reuse is explicit opt-in, while source installers always execute their safety checks. | Implemented. |
| **P1-06/14/19: AGP/KGP integration** | Compatibility is resolved before typed adapter access. Requested features fail with stable guidance when peer classes/versions are unavailable. App-scoped and global SDK/JVM policies are separated; AGP 8 uses a floor-compiled adapter and replaces only unambiguous locale entries while preserving density, ABI, and the locale-shaped `car` UI-mode qualifier (`car` locale emits as `b+car`); AGP 9 uses current typed APIs. | Closed for the advertised range by six real consumer builds: Gradle 8.5/9.5.1, AGP 8.5.2/9.1.1, and stable KGP 2.4.0. |
| **P1-09/10/11/13/15: mutation correctness** | One iOS config invocation plans plist, pbxproj, Podfile, and Swift changes before a single locked transaction; commit failure rolls prior files back. Missing configured inputs fail explicitly. Plist parsing is hardened, its 4 MiB budget is measured in UTF-8 bytes, and duplicate/conflicting values follow an explicit `FAIL`/`KEEP`/`REPLACE` policy. Swift comments, strings, raw strings, and extended regex literals are masked before exact import migration. Android cleanup and replacement share one rollback domain. Parked entries remain lexical, no-follow handles even under concurrent symlink substitution. Logo ownership prevents overwriting unknown or manually modified files. | Core safety remedy implemented. The plugin still edits source formats; generated xcconfig and syntax-tree migrations remain preferable future architecture. |
| **P1-12: locales** | Locale inputs use one bounded, duplicate-free platform-resource BCP-47 subset. Auto-discovery accepts only locale-only `values-en`, `values-pt-rBR`, and `values-b+sr+Latn` forms, never mixed qualifiers. Apple regions are additive; Android filtering is separate, exact, opt-in, and rejects an empty set. | Implemented and regression-tested; on-device language-picker behavior remains application integration testing. |
| **P1-16/17: worker and BuildConfig** | Both generators write only owned `build/generated` trees. BuildConfig uses validated Kotlin identifiers/literals, rejects duplicates/collisions/non-finite numbers, scopes identity inclusion, and warns against cache-backed secrets. The browser worker adds explicit target selection, startup/error normalization, timeout and coroutine-cancellation termination, and clearer browser/CSP contracts. | Hardened, but intentionally still generated convenience APIs. The worker remains browser-only and executes caller-supplied JavaScript text; BuildConfig is not a secret store. |
| **P1-18: diagnostics and UX** | `kiteSsotDoctor` is resilient and observational; `kiteSsotCheck` writes deterministic JSON or SARIF then fails on policy; `kiteSsotPlan` reports operations, targets, paths, policies, notes, and bounded reviewable unified-style previews without mutation. Stable IDs and structural inspections replace best-effort messages, including checksum-owned icon completeness, Android manifest consumption, and selected Xcode AppIcon alignment. | Implemented and covered for malformed providers, aliases/abbreviations, mixed invocations, report escaping, fail-after-report behavior, and installed-but-unused branding. |
| **P2-01/02/10: resource, error, and ownership hardening** | PNG and text inputs, traversal, generated fields, manifests, snapshots, and diff logs have explicit budgets. Errors sanitize hostile text and preserve useful root causes. Text/asset batches are lock-coordinated and rollback-capable. | Implemented with hostile-input, limit, symlink, concurrency, and recovery coverage. |
| **P2-03/04/07: publication and API discipline** | Dokka-backed javadocs, license/manifest metadata, complete POMs, ABI validation, locks, dependency verification, CycloneDX SBOMs, PGP signing, pinned actions, source-cleanliness checks, deterministic archives, and staged provenance are present. | Locally verified: ABI passes; six Maven payloads stage; all archive times/orders are normalized; the runtime SBOM has zero dependencies; signing and clean-build reproducibility rehearsals pass. |
| **P2-05/06/08: compatibility tests and documentation** | Coverage spans validation, ownership/rollback/races, parser ambiguity, diagnostics, generated-source compilation, source non-mutation, multi-project selection, real AGP/KGP consumers, and release tasks. README, FEATURES, CHANGELOG, DSL KDocs, and generated Dokka were reconciled against code. | Locally release-gated. Browser/CSP execution, Xcode signing/upload, and remote OS CI remain environment-level checks, not unimplemented code paths. |
| **P2-09/11: performance and public coupling** | Work is provider-backed, selected by capability/project, generated into owned directories, and source mutation is removed from normal task graphs. `KiteSsotAccess` documents the finalized read-only model. | Improved. The root aggregation plugin and convenience accessor remain public architectural coupling rather than a split adapter/runtime ecosystem. |

### Intentional boundaries after remediation

- This remains a root aggregation plugin. Gradle isolated-project support and a
  family of independently versioned platform adapters have not been implemented.
- The model represents one application identity with explicit consumers/selectors;
  target-specific identity overlays and application matrices remain a 2.0 design.
- Source installers are explicit and transactional, but they still edit pbxproj,
  plist, Podfile, Swift, and launcher-resource source. Generated xcconfig and
  parser-native migration tools remain the safer long-term Apple direction.
- The worker and BuildConfig generators remain optional conveniences rather than
  separately versioned runtime/compiler plugins. Their documented browser, CSP,
  raw-code, cache, and secret-handling limitations are deliberate.
- Compatibility fallbacks (`sharedModule`, `oldSharedModuleName`, and legacy path
  strings) remain to avoid an immediate flag day; new code should use semantic
  project/module selectors and typed file/directory properties.

### Verification status of this working tree

The following gates were run against the frozen remediation tree on 2026-07-18:

| Gate | Exact result |
|---|---|
| `./gradlew test --no-daemon --no-parallel --max-workers=1 --stacktrace` | **Final exact-tree pass in 1m15s:** 233 tests across 22 suites, 0 failures, 0 errors, 0 skipped. An earlier cold pass also completed in 5m34s. |
| Focused safety/API regression selection | **Passed:** validation, versions, locales, BuildConfig, worker, pbxproj, shared rewrite, diagnostics, owned output, and text transaction suites plus key TestKit cases. |
| `./gradlew agpCompatibilityTest --no-configuration-cache --no-daemon --no-parallel --max-workers=1 --stacktrace` | **Passed in 10m01s:** 6 real consumer builds covering Gradle 8.5/9.5.1, AGP 8.5.2/9.1.1, KGP 2.4.0, classic/KMP-native Android, published loading, fields-only BuildConfig, strict cache reuse, and AGP 8 preservation of both `xxhdpi` and ambiguous `car` UI-mode filters. |
| `./gradlew updateKotlinAbi checkKotlinAbi --no-daemon --stacktrace` | **Passed in 39s;** the 357-line public ABI baseline was refreshed and validated. |
| Clean `build validatePlugins verifyReleaseMetadata cyclonedxBom` with `--configuration-cache-problems=fail` | **Passed;** the identical final confirmation **passed in 4s** with “Configuration cache entry reused,” 16 tasks up-to-date and 2 policy tasks executed. |
| `verifyReleaseMetadata`, CycloneDX, JAR/source/Dokka/POM generation | **Passed;** exact `1.7.0`/`v1.7.0`/changelog agreement, complete POM metadata, normalized archives, and a CycloneDX 1.6 runtime SBOM with 0 shipped dependencies. |
| `stageUnsignedReleaseCandidate` | **Passed twice from clean state;** exactly six core Maven payloads staged: implementation JAR, sources, Dokka/Javadoc, Gradle module metadata, implementation POM, and marker POM. Every corresponding SHA-256 digest matched across the two candidates. |
| Isolated release rehearsal | **Passed:** the final two clean candidates were byte-identical. In a separate signing rehearsal, encrypted and unencrypted ephemeral in-memory PGP keys each produced one detached signature for all six payloads. This was not a production signature or publish. |
| Static repository checks | **Passed:** `actionlint`, workflow YAML loading, `git diff --check`, and TODO/FIXME blocker scan. |

The original 86-test result below belongs only to `cce499f`. The local code-level
release hold caused by its seven P0 findings is lifted for this remediation tree.
Actual publication remains correctly gated by protected CI, production signing
material, and remote credentials.

Not claimed by this local audit: a real Xcode archive/sign/App Store upload, live
browser/CSP execution of the optional worker, remote Plugin Portal/GitHub Packages
publication, GitHub provenance issuance, or completed Linux/macOS/Windows hosted-CI
runs. Those require external environments or credentials; their workflows and
contracts are present, but configuration is not evidence that those external runs
occurred.

> The “not an implementation patch” note and pre-existing-edit statement below are
> part of the preserved 2026-07-14 audit snapshot. This remediation overlay records
> the subsequent implementation and verification work.

---

## 1. Executive verdict

`kitessot` has a valuable central idea and noticeably careful local workmanship,
but the current release is not yet safe enough to be the authoritative layer for
real, multi-target KMP applications.

The plugin currently combines four different products behind one root DSL:

1. an immutable app-identity model;
2. continuous Gradle/platform configuration adapters;
3. destructive, source-tree migration and rewrite tooling;
4. unrelated runtime/toolchain conveniences (global opt-ins, a JavaScript worker,
   and a general-purpose BuildConfig generator).

Those four modes have different safety, lifecycle, caching, and UX requirements.
Treating them as one automatic “sync” is the root cause of most high-severity
findings.

### Release decision

**Hold the next public release until all P0 items are resolved.** In particular:

- an allowed version transition can lower Android `versionCode` by roughly three
  orders of magnitude;
- a normal build can rename the only unrelated local CocoaPod and matching Swift
  imports;
- failure to understand a pbxproj broadens the rewrite to every target instead of
  failing closed;
- a build-cache hit can overwrite a user-owned `Contents.json` without executing
  the promised backup logic;
- a symlink placed at a generated output directory can redirect recursive cleanup
  into handwritten source outside that output;
- a `v*` release tag is not tied to the artifact version being published;
- Xcode project mutation is wired into framework tasks even though those files are
  neither inputs to the Kotlin linker nor guaranteed to affect the current Xcode
  build.

### Maturity scorecard

These scores are directional, not mathematical. “100” means category-defining,
safe-by-default, documented, compatibility-tested, and excellent across common KMP
topologies.

| Area | Current | Main limiter |
|---|---:|---|
| Product thesis | 72 | Excellent problem, blurred scope |
| Public DSL/API | 43 | Strings conflate paths, projects, modules, and IDs |
| Correctness | 38 | Version ordering, target ambiguity, silent no-ops |
| Destructive-change safety | 24 | Fail-open rewrite and cache/backup conflicts |
| Gradle architecture | 42 | Root cross-project mutation, `afterEvaluate`, classloaders |
| Android integration | 45 | No real AGP tests, multi-app and resource-filter hazards |
| Apple integration | 31 | Regex/OpenStep mutation, no target selector, timing problem |
| Web/runtime helper | 27 | Browser-only string-code API, false cancellation contract |
| Generated BuildConfig | 46 | Useful seed, unsafe validation and secret guidance |
| Performance/incrementality | 51 | Some good cacheable generators; source rewrites always run |
| KDocs/documentation | 48 | Abundant prose, but several material contradictions |
| Tests/compatibility | 44 | 86 green tests; highest-risk adapters are not exercised |
| Publishing/supply chain | 34 | Tag/version split, thin metadata, no provenance/signing |
| Developer UX/diagnostics | 50 | Helpful doctor/verify seed, human-only and non-gating |
| **Overall horizon** | **40** | Strong prototype/early product, not yet an authority layer |

---

## 2. What the code actually does

The root plugin creates a managed `kiteSsot` extension and then reaches into every
**subproject** to configure detected plugins. The implemented sinks are:

- classic Android application and library DSLs through AGP `finalizeDsl`;
- AGP's KMP-native Android library DSL;
- every Kotlin/Native compilation's compiler opt-ins;
- generated `commonMain` BuildConfig Kotlin source;
- generated Kotlin/JS Blob-worker source;
- root tasks that rewrite an Xcode pbxproj, Info.plist, Podfile, and Swift files;
- root tasks that overwrite Android launcher resources and an iOS app-icon set;
- human-readable verify and doctor reports.

This is broader than “one source of truth for app identity.” It is an application
policy and project-transformation framework. The plugin description and API should
either embrace that identity explicitly or split the capabilities into cohesive
adapters/companion plugins.

### The key conceptual mismatch

| Capability | Correct lifecycle | Current lifecycle |
|---|---|---|
| Identity values | Immutable configuration | Root extension, partly lazy |
| AGP/KGP configuration | Configure each target project locally | Root mutates all subprojects |
| Generated code/resources | Deterministic build output | Mixed build output and source-tree output |
| Drift verification | Read-only, CI-gating | Doctor prints but succeeds |
| Source migration | Explicit, reviewed, transactional | Runs as an automatic build dependency |
| Experimental compiler policy | Explicit project decision | Enabled globally by default |
| Runtime worker library | Versioned/tested runtime artifact | Source string generated by identity plugin |

The 2.0 architecture should make those lifecycles explicit rather than adding more
booleans to the current root object.

---

## 3. Audit method and verified baseline

The following checks were run against the inspected working tree:

- a forced `clean build validatePlugins` with configuration-cache problems set to
  fail: **passed**;
- all **86** declared tests: **passed**;
- plugin validation: **passed**;
- an identical build reused the outer build's configuration cache;
- a Gradle **8.9** consumer smoke applied the built plugin, ran
  `kiteSsotVerify`, stored configuration cache, and correctly invalidated it when a
  new `values-fr` locale directory appeared;
- a configured-but-missing logo input was tested: Gradle rejected the missing
  `@InputFile` before task action, proving the task's “not found: skipping” branch
  is unreachable for that state;
- applying the plugin with app name and version unset, then running
  `sanitizeIosProject`, inserted all four identity references into Info.plist,
  disproving the documented “identity fields only propagate when set” contract;
- two clean artifact builds in the same environment produced byte-identical jars;
- the produced implementation jar targets Java 17 bytecode;
- the generated Maven POM was inspected and contains coordinates plus a license,
  but no project name, description, URL, SCM, developers, or issue tracker;
- the published javadoc jar is effectively empty (manifest only).

### What this baseline does **not** prove

The green build does not apply this plugin to itself. The functional runner injects
KGP into its plugin classpath and does not run consumer builds with configuration
cache. No test applies a real AGP application/library plugin, compiles generated
source, runs the worker in a browser, or validates a real Xcode project with Xcode.

The code has a good regression suite for pure helpers, but the green badge currently
measures the low-risk center better than the high-risk integration boundary.

---

## 4. What is already good and should be retained

This audit is intentionally severe, but the following choices are solid foundations:

- Managed `Property`, `ListProperty`, `RegularFileProperty`, task registration,
  and `TaskProvider` are used widely instead of ad-hoc eager state.
- Classic Android authority is applied at AGP's `finalizeDsl`, which correctly
  establishes precedence over module-local defaults.
- Native target and compilation containers use `configureEach`.
- Pure transformations are extracted from Gradle tasks and have focused unit tests.
- Generated Kotlin source is placed under `build/generated` and attached with
  `srcDir(task.flatMap { outputDir })`, preserving task dependencies.
- Generated-source tasks clear their owned directory when performing a real
  generation, avoiding stale package duplicates.
- Text replacement uses a literal-producing regex callback, avoiding `$` replacement
  group bugs.
- The rewrite layer attempts idempotence, one-time backup, and sibling-temp atomic
  replacement instead of blind writes.
- The plist code uses an XML parser and disables external DTD resolution rather than
  applying a broad XML regex.
- The pbxproj code makes a real attempt to scope settings to application targets.
- Icon composition preserves aspect ratio and makes the iOS output opaque.
- The plugin is Java-17-loadable even though its own build toolchain is JDK 21.
- CI runs tests and Gradle plugin validation before publication.
- The repository has a changelog, Apache-2.0 license, sources jar, and extensive
  narrative documentation.

The correct direction is to keep these implementation habits while changing the
ownership model and safety defaults.

---

## 5. P0: release blockers

### P0-01: Allowed versions can make Android `versionCode` go backwards

**Evidence:** `VersionCode.kt:22-39`; the short forms are deliberately accepted by
`VersionCodeTest.kt:17-20`.

The encoding prepends `1` and pads only the segments that were supplied. Total width
therefore changes with segment count:

```text
1.2.1 -> 1,001,002,001
1.3   ->     1,001,003
```

That perfectly normal version upgrade produces a dramatically smaller code, which
Google Play will reject. Other failures include:

- `0.999.999` followed by `1` decreases the code;
- `1.2.3` and `001.002.003` collide;
- `versionCodeOverride` accepts zero, negatives, and values above Play's
  `2_100_000_000` ceiling.

The platform ceiling is documented by [Android's versioning guide](https://developer.android.com/studio/publish/versioning).

**Required fix:** preserve the existing result for already-supported three-segment
versions, but either reject one/two-segment names or pad missing segments to three.
Reject leading-zero ambiguity, validate overrides in `1..2_100_000_000`, and ship a
migration note because changing a published app's version-code scheme is itself
dangerous. Longer term, model a pluggable, explicitly named Android code strategy and
offer a task that proves the next code is greater than a supplied/store baseline.

### P0-02: Automatic shared-module detection can rename an unrelated local Pod

**Evidence:** `SharedModuleRewrite.kt:13-38`, `SyncIosConfigTask.kt:102-149`, and
the default `propagateSharedModule = true` at `KiteSsotPlugin.kt:43`.

Any single Podfile entry whose pod name equals its path tail is assumed to be the old
shared module. If the project's only local development pod is `Utils`, but the KMP
framework is integrated directly or through SPM, setting `sharedModule = "composeApp"`
rewrites:

```ruby
pod 'Utils', :path => '../Utils'
```

and every plain `import Utils` found by the recursive Swift walk. The multi-pod guard
does not protect the more dangerous single-unrelated-pod case.

This is a migration heuristic with insufficient evidence, enabled by default and
attached to normal build work.

**Required fix:** remove auto-detection and make rename a separate, explicitly invoked
migration requiring both `from` and `to`. It should produce a unified diff first,
require an explicit apply mode, validate Swift/CocoaPods identifiers, refuse paths
outside the selected tree, and never run from framework compilation. Ideally use
SwiftSyntax/Ruby-aware parsing or narrowly edit a user-selected Pod declaration.

### P0-03: pbxproj parser uncertainty broadens writes instead of failing closed

**Evidence:** `PbxprojRewrite.kt:73-91`; the fallback is explicitly cemented by
`PbxprojRewriteTest.kt:192-201`.

When `applicationBuildConfigSpans` returns no spans: whether because the project truly
has no app target or because the minimal parser did not understand valid syntax: the
rewriter replaces matching settings globally. Test bundles, extensions, widgets,
watch targets, and framework targets can receive the app's name and bundle ID. The
warning is issued only after the mutated output has already been produced.

For destructive editing, parse uncertainty must reduce authority, never expand it.

**Required fix:** fail closed. A missing/ambiguous target is an error unless the user
selects a target/configuration explicitly. Never provide an implicit global fallback.
The durable solution is to stop editing pbxproj for continuous sync and emit a generated
xcconfig consumed by selected targets. If legacy pbx editing remains, use a real
OpenStep tokenizer/parser, preserve formatting, and require exact target/config IDs.

### P0-04: Build-cache restore can bypass the promised AppIcon backup

**Evidence:** `SyncIosLogoTask.kt:33, 49, 53, 94-101` and
`KiteSsotPluginFunctionalTest.kt:209-233`.

`SyncIosLogoTask` is cacheable and declares the user-owned `Contents.json` as an
output. Its `.kitessot.bak` is created only inside `@TaskAction` and is not a declared
output. On a local or remote cache hit, Gradle can restore/replace `Contents.json`
without running the action, so `backupBeforeRewrite = true` produces no backup.

The same task's orphan scan and Android's collision scan inspect undeclared sibling
files, so `UP-TO-DATE`/`FROM-CACHE` paths also suppress warnings. The existing test
covers only a locally executed action.

This violates the central recovery promise precisely on the optimized execution path.
Gradle's own [build-cache guidance](https://docs.gradle.org/current/userguide/build_cache.html)
requires a complete, repeatable input/output model.

**Required fix:** never make a source-tree installer/rewrite task cacheable. Split icon
work into:

1. a pure cacheable renderer under `build/generated/kitessot/...`;
2. an explicit, non-cacheable installer/merge task with transactional backup; or
3. preferably, an AGP/Xcode integration that consumes generated assets without copying
   over user-owned source files.

### P0-05: A release tag is unrelated to the version being published

**Evidence:** `build.gradle.kts:12-13`, `gradle.properties:5`, and
`.github/workflows/publish.yml:6-43`.

Any `v*` tag triggers publishing, but the artifact version comes from the independent
`kiteSsot.version` property and silently falls back to the valid-looking version `1.0.0`.
A `v1.8.0` tag currently attempts to publish `1.7.0`. If the property is accidentally
absent, a release job can attempt `1.0.0`.

Publishing to the Portal and GitHub Packages is sequential. If the Portal succeeds and
GitHub Packages fails, channels split; a naive retry may fail at the already-published
Portal step.

**Required fix:** add a release gate that requires strict SemVer, tag equals artifact
version, matching changelog section, tagged commit, non-snapshot version, and successful
publication to a temporary repository. Derive version from the tag or fail if the
property is absent; local fallback should be `0.0.0-SNAPSHOT`, never a valid release.
Build once, attest that artifact, then publish the same bytes to each channel with
independent retry/recovery.

### P0-06: Normal compilation is the wrong place to mutate Xcode project state

**Evidence:** `KiteSsotPlugin.kt:481-491`, `IosTaskMatching.kt:19-22`, and all
in-place iOS task properties in `SyncIosConfigTask.kt:38-57`.

Every matching link/embed/XCFramework task depends on source-tree mutation. This has
three fundamental problems:

- the Kotlin linker does not consume `project.pbxproj`, Info.plist, Podfile, or Swift
  imports, so these are unrelated side effects of linking;
- when Xcode invokes `embedAndSignAppleFrameworkForXcode`, Xcode has already loaded and
  evaluated project build settings, so rewriting during that invocation cannot be
  relied upon to configure the same build;
- Gradle and Xcode (or two concurrent Gradle builds) can read/write the same project
  files without a lock or transaction spanning all files.

Even a perfectly parsed rewrite is lifecycle-incorrect here.

**Required fix:** normal builds should be read-only with respect to project sources.
Generate an xcconfig/plist fragment/assets deterministically, make Xcode include them
before build evaluation, and provide explicit `plan`, `apply`, and `checkDrift` tasks.
One-shot Podfile/Swift migration must be completely separate from continuous identity
configuration.

### P0-07: generated-output cleanup can recursively delete handwritten source

**Evidence:** `GenerateBuildConfigTask.kt:42-45` and
`GenerateIoWorkerTask.kt:49-59`.

Both generators recursively delete the configured output root before writing. They do
not canonicalize it, enforce containment under the owning project's build directory,
reject symlinks in any path component, or check the deletion result. Kotlin's
`File.deleteRecursively()` traverses through `FileTreeWalk`; a directory symlink is
treated as a directory and followed through `listFiles`.

A symlink such as
`shared/build/generated/kitessot/commonMain/kotlin -> shared/src/commonMain/kotlin`
therefore directs generation cleanup through the handwritten source tree. Files are
deleted before the symlink itself. If deletion only partly succeeds, stale generated
declarations can also remain and be compiled beside the new one. This can arise from a
malicious checkout, a mistaken local link, or another build tool that virtualizes output
paths; “under build/” is not a sufficient safety boundary.

**Required fix:** inspect every path component without following links, canonicalize and
prove containment under a plugin-owned build directory, reject any symlink/reparse
point, and delete only files recorded in a plugin ownership manifest. Treat incomplete
deletion as fatal before writing. Add adversarial tests on Unix symlinks and Windows
junctions/reparse points.

---

## 6. P1: major correctness, API, and safety findings

### P1-01: Cross-platform version and identifier types are missing

**Evidence:** `KiteSsotExtension.kt:25-41, 265-272`,
`ClassicAndroidWiring.kt:34-42`, and `PbxprojRewrite.kt:51-70`.

The API accepts raw strings and appends suffixes without validating either platform.
Android requires an application ID with at least two segments, each starting with a
letter; the plugin does not enforce that [documented grammar](https://developer.android.com/build/configure-app-module).
Apple bundle-ID rules and build-setting escaping are also not enforced.

More importantly, one `versionName` is treated as valid for both ecosystems. The KDoc
recommends `versionCodeOverride` for prerelease names such as `1.2.3-rc1`, then the iOS
sink writes that value to `MARKETING_VERSION`. Apple's current
[CFBundleShortVersionString contract](https://developer.apple.com/documentation/BundleResources/Information-Property-List/CFBundleShortVersionString)
requires three period-separated integers. Android's version code is also copied into
Apple `CURRENT_PROJECT_VERSION`, despite the platforms having different build-number
models ([CFBundleVersion](https://developer.apple.com/documentation/bundleresources/information-property-list/cfbundleversion)).

Raw `versionName`, `bundleId`, locale, shared-module, and app-name values can contain
newlines, semicolons, quotes, or Xcode variable syntax. `versionName` and bundle ID are
inserted as bare pbx values; a bad value can make the project invalid or inject another
setting.

**Recommendation:** introduce validated value objects and independent platform
overlays: canonical marketing version, Android display version/code, Apple short version/
build string, Android application ID, and Apple bundle ID. Validate at model finalization,
then render through platform-specific escaping: not string concatenation.

### P1-02: Default application is not observational or opt-in

**Evidence:** conventions at `KiteSsotPlugin.kt:38-52`, sanitizer logic at
`SanitizeIosProjectTask.kt:65-83`, and the optional-field contract at
`KiteSsotExtension.kt:10-19`.

With app name and version unset, sanitizer still inserts `CFBundleDisplayName`,
`CFBundleName`, `CFBundleShortVersionString`, and `CFBundleVersion`, because it checks
only propagation toggles. This was reproduced in a real Gradle 8.9 consumer build.

Other surprising defaults are more consequential:

- iOS synchronization and sanitization are on;
- locale propagation/filtering is on;
- shared-module migration is on;
- three experimental Native API families are globally opted into.

Applying a source-mutating plugin should be a no-op until a sink is explicitly owned.
Compiler `@OptIn` is an acceptance of API instability, not an identity default.

**Recommendation:** default every destructive or policy-changing capability off.
Adopt `mode = CHECK` as the safe default. A configured value can conventionally enable
its non-destructive adapter, but migration/apply still requires an explicit command.

### P1-03: The root project can never be a target project

**Evidence:** `KiteSsotPlugin.kt:159-207` iterates only `target.subprojects`.

The plugin is required on root, yet a root project that itself applies Android or KMP
receives none of the platform wiring. Single-project KMP builds silently miss identity,
SDK, JVM target, native opt-ins, worker, BuildConfig, and task hooks.

**Recommendation:** model project paths explicitly and configure each selected project
locally. At minimum include `target` in project discovery and add root-only functional
fixtures, but the adapter architecture in section 11 is the real fix.

### P1-04: Multi-app and multi-target behavior cannot be represented safely

**Evidence:** `KiteSsotPlugin.kt:159-174, 402-420, 494-503` and
`PbxprojTargetScope.kt:86-109`.

Every Android application receives the same ID/version; a warning appears only after
the second is seen, but mutation continues. One `androidAppModule` directory receives
icons while every app's `preBuild` depends on that one task. On Apple, every application
target and every build configuration receives the same values. Phone, TV, staging,
enterprise, Catalyst, and companion apps cannot have legitimate overlays.

**Recommendation:** expose named applications and explicit Android project paths, Apple
target names, build configurations, and variant overlays. Ambiguous discovery should be
an error. Never continue after detecting duplicate application IDs.

### P1-05: `sharedModule` conflates four distinct identities

**Evidence:** `KiteSsotExtension.kt:60-74`, `KiteSsotPlugin.kt:287-337`, and
`SyncIosConfigTask.kt:102-115`.

The same string is used as:

- a root-relative directory;
- a Gradle `Project.name`;
- a CocoaPods pod name/path tail;
- a Swift framework import name.

Nested projects, custom `projectDir`, duplicate leaf names, framework `baseName`, and
paths such as `modules/shared` immediately break this equivalence. BuildConfig may be
generated in zero or multiple projects.

**Recommendation:** split `sharedProjectPath`, `sharedProjectDirectory`,
`appleFrameworkName`, and optional `podName`. Prefer `Project.path` for Gradle identity,
and typed `DirectoryProperty`/`RegularFileProperty` for paths.

### P1-06: Peer-plugin classloading is a documented workaround, not a robust API

**Evidence:** compile-only dependencies at `build.gradle.kts:35-42`, the KGP guard and
warning at `KiteSsotPlugin.kt:182-205`, and unguarded Android adapter calls at
`KiteSsotPlugin.kt:162-179`.

If KGP is not visible to the root plugin's classloader, requested native opt-ins,
worker generation, and BuildConfig generation are skipped. The warning omits BuildConfig.
`javaVersion` can still change Java compatibility while Kotlin `jvmTarget` is silently
left alone, creating the mismatch this feature claims to prevent.

AGP is also compile-only, but has no equivalent visibility guard. Declaring AGP only in
a subproject can leave the root plugin's adapter classloader unable to resolve AGP types.
The current functional-test classpath injects full KGP into every fixture, specifically
avoiding the production sibling-classloader topology.

**Recommendation:** split platform adapters into module-local plugins applied where the
peer plugin is visible. If a requested adapter cannot load, fail with an actionable
message; never silently drop requested behavior. Test plugin-only, root-shared KGP/AGP,
and subproject-only peer-plugin classloaders independently.

### P1-07: Configuration order can permanently change installed behavior

**Evidence:** `KiteSsotPlugin.kt:217-226`, `KmpAndroidLibraryWiring.kt:31-49`,
`ClassicAndroidWiring.kt:111-115`, and conditional provider binding at
`KiteSsotPlugin.kt:369, 439-442`.

Several callbacks branch on `.get()` or `.isPresent` when a peer plugin is applied. In
unusual but legal evaluation orders: root logic applying/configuring children before a
later `kiteSsot {}` block: an adapter can return permanently or install default behavior
that later DSL values cannot undo. A task realized before `bundleIdBase` is set can also
omit the derived provider forever.

**Recommendation:** register integrations unconditionally, bind providers unconditionally,
and read/finalize values only at sanctioned DSL-finalization or execution points. Replace
target snapshots and `afterEvaluate` with lazy target/source-set `configureEach` hooks.

### P1-08: Global dry-run makes generated builds stale or incomplete

**Evidence:** `GenerateIoWorkerTask.kt:47-69`, `GenerateBuildConfigTask.kt:40-64`,
`SyncAndroidLogoTask.kt:60-134`, and `SyncIosLogoTask.kt:55-103`.

All four cacheable generation/render tasks include `dryRun` as an input but leave their
declared outputs untouched. Consequences:

- on a clean build, required generated source/assets are absent;
- on a dirty build, compilation consumes yesterday's generated values;
- changed inputs plus `dryRun = true` can snapshot/cache stale existing outputs under a
  new cache key;
- a dry run does not show a diff: only the path that would be written.

`kiteSsotVerify` nevertheless tells users they can “Preview exact edits.”

**Recommendation:** dry-run applies only to explicit source installation/migration.
Build-owned generation should always produce deterministic outputs. `plan` should compute
the same model as `apply` and emit a real unified diff plus a machine-readable plan.

### P1-09: File safety is per-file, not transactional or path-safe

**Evidence:** `RewriteSafety.kt:27-103`, recursive walks at
`SyncIosConfigTask.kt:158-177`, and generator deletes at
`GenerateBuildConfigTask.kt:42-45` / `GenerateIoWorkerTask.kt:49-59`.

The sibling-temp write is better than a direct truncate, but important guarantees are
overstated:

- the backup copy is non-atomic; a crash can leave a partial backup that is then kept
  forever;
- “first backup” is not necessarily pristine if the plugin or user previously changed
  the file;
- replacing through a temp file does not preserve POSIX mode, owner, ACLs, xattrs,
  encoding, BOM, or symlink identity;
- the atomic-move catch handles every `Exception` rather than only unsupported atomic
  moves and there is no file/directory `fsync` before claiming power-loss safety;
- several files are changed independently, so pbxproj/Podfile/Swift migration can stop
  in a half-applied state;
- no canonical containment check prevents configured `../` paths or symlink traversal
  from reaching outside the intended project tree;
- path KDocs say root-relative, but raw `Property<String>` values accept absolute paths
  and cannot express a project/file ownership boundary;
- Kotlin's `File.walkTopDown` implementation follows directory symlinks through
  `File.isDirectory` and `listFiles`, so Swift scanning can escape its apparent root;
  P0-07 covers the corresponding recursive-delete consequence for generators;
- the Swift prune list omits common dependency/generated trees such as `Carthage`,
  `SourcePackages`, `.swiftpm`, `checkouts`, and `Vendor`, while its line regex can match
  an import-looking line inside a multiline comment or string;
- multiple Gradle/Xcode processes have no lock; stale-temp cleanup deletes every matching
  sibling and can remove another active writer's temporary file;
- `.kitessot.bak` duplicates potentially sensitive plist/source data and is not ignored by
  the repository `.gitignore`.

**Recommendation:** replace string paths with typed file/directory/project selectors;
canonicalize and constrain every path; refuse symlinks by default; use NIO
`NOFOLLOW_LINKS`; preserve attributes; write backups atomically outside watched
asset/source directories; lock a whole migration; stage all outputs, validate them, then
commit as one recoverable transaction. Add an explicit restore command and backup
manifest/checksums.

### P1-10: Configured missing-image warnings can never run

**Evidence:** `SyncIosLogoTask.kt:41-65` and `SyncAndroidLogoTask.kt:45-70`.

`@InputFile @Optional` makes the property optional; it does not allow a configured path
to be missing. Gradle validates a present file before `@TaskAction`, so the friendly
“not found: skipping” branches are unreachable. This was reproduced in the Gradle 8.9
consumer smoke.

**Recommendation:** choose one contract. Prefer a required input with Gradle's native,
clear validation plus plugin-specific preflight context. If missing should genuinely
skip, model it as an optional untracked path and validate deliberately: but silent skips
are a poor default for authoritative branding.

### P1-11: pbxproj editing remains syntactically and semantically incomplete

**Evidence:** `PbxprojRewrite.kt:18-124` and `PbxprojTargetScope.kt:15-109`.

Beyond the P0 fallback:

- settings are replaced only; a missing or inherited key is never inserted and no
  warning records zero replacements;
- the matcher requires a narrow `KEY = value;` layout and ignores conditional settings;
- target/list/config discovery relies on exact fragments and regex blocks, not an
  OpenStep grammar;
- `buildConfigurations = (...)` stops at the first `)`, including one in a comment/name;
- object matching is not anchored to the actual `objects` dictionary and does not ignore
  every OpenStep comment/string form;
- every detected application target/configuration is in scope;
- raw version, bundle, and locale values are not safely serialized;
- comments containing setting-like text can be rewritten;
- an input with valid settings in `.xcconfig` can be reported “already in sync” while
  nothing changed;
- the lifecycle log prints configured values even when their propagation toggle was off.

The project parser has grown beyond what is reasonable to maintain as regex-plus-span
logic in an identity plugin.

**Recommendation:** xcconfig first. For legacy support, use a tested parser library or a
small formal tokenizer, count expected mutations per target/config, fail on missing or
duplicate keys, and round-trip real Xcode fixtures across supported Xcode versions.

### P1-12: Locale sync overwrites project metadata and conflates metadata with filtering

**Evidence:** `PbxprojRewrite.kt:95-121`, `LocaleTags.kt:11-33`,
`KiteSsotPlugin.kt:326-337`, and `ClassicAndroidWiring.kt:46-80`.

The `knownRegions` writer replaces the entire project list with `Base` plus detected
locales. Existing regions not present in one Compose directory are discarded. It does
not update actual `.lproj` resources, `developmentRegion`, or generated Info.plist
localizations, so it is not a complete Apple localization SSOT.

On Android, the same locale list is used as a resource filter. Auto-detection scans only
`shared/src/commonMain/composeResources/values-*`; applying that as an app-wide filter can
strip Android-app translations and dependency resources that were never visible to the
scan. Library modules are filtered too. `addAll` also means root values merge with local
filters rather than becoming authoritative, and an empty list cannot clear them.

The model is Android-qualifier-first. `b+(.+)` accepts invalid junk such as `b+night` or
`b+en++US`; explicit Apple-style `en-US` is flagged as invalid-looking by the doctor,
while BuildConfig
exposes raw Android qualifiers to common code. Deduplication occurs before Android-to-
Apple conversion, so distinct inputs such as `pt-rBR` and `b+pt+BR` can emit duplicate
`pt-BR` regions.

**Recommendation:** store canonical, validated BCP-47 tags plus an explicit default
locale. Derive Android qualifiers and Apple regions at the edges. Separate “supported
locale metadata” from the dangerous optimization “filter packaged resources,” default
filtering off, and generate Android 13 `localeConfig` plus Apple localization metadata.
Report resource coverage and missing/extra translations before mutating anything.

### P1-13: plist handling is safer than regex, but not a faithful round-trip editor

**Evidence:** `PlistSanitize.kt:46-252` and `SanitizeIosProjectTask.kt:57-99`.

Material gaps include:

- generated Info.plist (`GENERATE_INFOPLIST_FILE = YES`) is silently unsupported, so
  `ios {}` flags no-op;
- binary/OpenStep plists and non-UTF-8/BOM inputs are unsupported;
- duplicate keys are collapsed into a map and not diagnosed reliably;
- a malformed `key, key, value` sequence can be paired incorrectly;
- DOM serialization can normalize entities, CDATA, attribute quoting, whitespace,
  newlines, and the prolog far beyond the requested key;
- XML security features are attempted with `runCatching` and failures are ignored;
  secure-processing and internal-entity limits are not explicitly configured, leaving
  entity-expansion resource behavior dependent on the JAXP implementation;
- hardcoded SSOT keys only warn and leave the desired state unapplied;
- parse/serialization failure warns but lets the build succeed;
- the ProMotion KDoc presents one plist flag as a complete 120-Hz switch and the export
  compliance KDoc edges into policy/legal advice.

**Recommendation:** prefer generated build settings/xcconfig for generated plists. For
real source plists, use a property-list library/tooling path that supports XML and binary,
validate duplicate/type structure, preserve format/encoding, and make conflict policy
explicit (`FAIL`, `KEEP`, `REPLACE`). Mandatory XML hardening must fail closed; reject
internal subsets/entities and test XXE/expansion/oversized fixtures. Avoid legal/
performance guarantees in KDoc; link to Apple's source material.

### P1-14: Android wiring is incomplete, ambiguous, and almost entirely untested

**Evidence:** `ClassicAndroidWiring.kt:29-115`, `KmpAndroidLibraryWiring.kt:31-57`,
and the functional-test header at `KiteSsotPluginFunctionalTest.kt:9-12`.

No test applies real AGP. Current risks include:

- `applyLocaleFilters` catches **all `Throwable`**, potentially masking real user/plugin
  failures, VM errors, or partial mutation; only expected linkage errors belong there;
- `javaVersion` is not a toolchain and can request bytecode unsupported by the actual JDK;
- Kotlin alignment silently disappears when KGP is not visible;
- KMP-native Android libraries receive only compile/min SDK, despite KDocs promising
  Java and NDK values to every Android module;
- flavor/build-type identities and suffixes are not modeled or verified;
- application name needs a manual manifest placeholder patch;
- there is no relationship validation such as `minSdk <= targetSdk <= compileSdk`;
- SDK/Java/NDK values accept nonsensical or blank input;
- root Android projects are skipped;
- multiple apps continue after a duplicate-ID warning.

**Recommendation:** use module-local AGP adapter plugins and Variant APIs, model selected
applications/variants, generate resources through AGP's generated-source/resource APIs,
and test merged manifests, final variant IDs/versions, SDK values, resource filters, and
Java/Kotlin compilation for AGP 8 and 9.

### P1-15: Branding output can be unused, destructive, or obsolete on day one

**Evidence:** `SyncAndroidLogoTask.kt:114-143, 172-228`,
`CleanupLegacyAppLogoArtifactsTask.kt:36-60`, and
`SyncIosLogoTask.kt:86-143`.

Android always generates fixed `ic_launcher*` names in one configured source directory,
but never verifies that the merged manifest references them. It warns about WebP
collisions, then continues, leaving AAPT to fail after source mutation. The opt-in cleanup
deletes potentially user-authored assets without backup and its claim that deletion is
always safe is too strong. Because the cleanup task is always registered, it can be run
manually without any configured replacement logo and still delete matching assets. There
is no Android 13 monochrome/themed icon, notification icon, splash branding, or
per-variant/app support.

`appLogoAndroidSafeZoneRatio = Double.NaN` passes the `(0, 2]` check because every
comparison with NaN is false; conversion later collapses the foreground safe zone to one
pixel. Validate finiteness before range and reject non-finite numeric DSL values
everywhere.

iOS replaces the entire `Contents.json`, discarding legacy/device entries and modern
Light/Dark/Tinted appearances. Apple explicitly supports those variants in the current
[app-icon workflow](https://developer.apple.com/documentation/xcode/configuring-your-app-icon/).
The backup is placed inside the source `.appiconset`, mixing recovery data into a
directory consumed by asset tooling. An existing `AppIcon-1024.png` is overwritten
without backup, and the task never proves that the Xcode target selects this app-icon
set.

Java2D/ImageIO rendering and PNG encoding can vary by JDK/platform but that environment
is not in the cache key. Color profiles, Display-P3 intent, gamma, source metadata, and
render quality have no golden validation. The Android PNG-background alpha path also
differs from iOS flattening.

**Recommendation:** render deterministic platform-neutral masters into build output,
wire generated Android resources via AGP, and structurally merge or generate a separately
owned Apple asset set. Add manifest/target selection checks, monochrome/dark/tinted
inputs, deterministic renderer versioning, and golden pixel/dimension/alpha/color tests.

### P1-16: the generated worker is not a safe or portable web API

**Evidence:** `IoWorkerGen.kt:35-119`, `GenerateIoWorkerTask.kt:47-69`, and
`KiteSsotPlugin.kt:238-277`.

The feature is advertised as a Kotlin/JS offload primitive, but the generated code is a
browser-only JavaScript evaluator:

- every call creates a Blob URL, parses a fresh script, starts a new worker, sends one
  string, and destroys the worker;
- `jobJs` is raw executable JavaScript assembled into source, so untrusted input is code
  execution and even trusted input receives no syntax validation;
- Blob workers are blocked by common Content Security Policies unless `worker-src blob:`
  is allowed;
- construction failure occurs before URL revocation and before the error handlers are
  installed;
- Node.js Kotlin targets receive the helper even though the global browser `Worker`,
  `Blob`, and `URL.createObjectURL` APIs do not exist there;
- there is no timeout, backpressure, size limit, structured payload, transfer-list API,
  retry policy, or reusable pool;
- the plugin does not add `kotlinx-coroutines-core`, despite generating an import from
  it; describing this as “no-dependency” means only that the dependency is assumed;
- every JS module receives the same default FQN, making aggregation or shared source-set
  layouts collision-prone;
- wasmJs is named in the public block but skipped, and other KMP web targets are not
  represented;
- source KDoc explicitly says the protocol is duplicated in an external KiteCore
  repository and that bugs must be fixed in every copy, but there is no shared protocol
  artifact or cross-repository conformance test to enforce that contract.

Most importantly, the KDoc promise that cancellation discards the worker is false.
`CompletableDeferred()` is detached from the caller's job; cancellation of `await()` does
not call `terminate()`. An infinite worker continues consuming CPU.

This is product scope creep inside an identity/configuration plugin and creates a runtime
compatibility promise much larger than the rest of the project. **Recommendation:** move
the worker to a separately versioned runtime artifact/companion plugin. Expose typed,
registered jobs or a serialization-based protocol, browser and Node implementations,
structured errors, transferables, a bounded reusable pool, timeout, and real cancellable
cleanup. Test it in Chromium/Firefox/WebKit and Node under production bundling and CSP.

### P1-17: BuildConfig is a useful prototype with an unsafe source-level contract

**Evidence:** `BuildConfigGen.kt:14-84`, `KiteSsotBuildConfigExtension.kt:36-80`,
`GenerateBuildConfigTask.kt:40-65`, and `KiteSsotPlugin.kt:287-321`.

The field API stores pre-rendered Kotlin fragments in a public `ListProperty<String>`.
That representation discards type/value structure too early and allows consumers to
append arbitrary source. It also creates the following correctness gaps:

- Kotlin keywords such as `object`, `when`, and `class`, plus the reserved `_`, pass the
  identifier regex and then fail compilation;
- the shared package validator used by worker and BuildConfig generation likewise
  accepts `_` as a package segment even though Kotlin reserves underscore-only names;
- duplicate custom names and collisions with `appName`, `versionName`, `versionCode`,
  `androidApplicationId`, `iosBundleId`, or `locales` are accepted;
- `Double.NaN`, positive infinity, and negative infinity render as unresolved source
  tokens rather than `Double.NaN`/`Double.POSITIVE_INFINITY` or a rejected value;
- string escaping handles the common escapes but emits other ISO control characters
  directly;
- generated declarations use lowercase identity names and uppercase examples without a
  deliberate naming convention;
- validation happens while configuring helper calls, but a user can mutate `fields`
  directly and bypass it; the task does not validate again;
- all identity providers are realized and become task inputs even when
  `includeIdentity = false`, causing unnecessary invalidation and surprising failures;
- the task owns the same generated `commonMain/kotlin` directory family used by other
  potential features, without a manifest or namespace ownership protocol.

The explicit “Not a secret store” warning is good, but its follow-on guidance is
incomplete. Loading a token from an environment variable keeps it out of the build
script/VCS, while the resolved value still enters Gradle task inputs, generated source,
build caches, KLIBs, APKs/IPAs, decompilers, and possibly build scans. Client-side
constants cannot protect credentials.

**Recommendation:** retain a sealed typed field model until rendering, keep the backing
collection internal, validate Kotlin keywords/duplicates/finite values/control
characters at task execution, and compile generated output in tests. Name the capability
`constants` or `runtimeMetadata` unless it intentionally matches Android BuildConfig.
Strongly state that only public client configuration belongs there. Consider whether a
dedicated, mature BuildKonfig-style tool should remain the recommended solution instead
of expanding this plugin's scope.

### P1-18: diagnostics describe problems but cannot enforce correctness

**Evidence:** `KiteSsotDoctorTask.kt:12-135` and `KiteSsotVerifyTask.kt:11-97`.

`kiteSsotDoctor` emits `[FAIL]` yet intentionally returns success. That is reasonable for
an interactive doctor, but there is no strict counterpart for CI, and plugin
configuration can already fail before the doctor is runnable. Its summary counts lines
by substring, not typed results. Most checks are lexical `contains` operations rather
than parsing final platform state, so comments or unrelated text can produce false
passes. The Android check reads only the conventional main manifest; the Apple check
looks for two substrings and ignores target selection, actual build settings, generated
plists, and the other keys the sanitizer manages.

Its KDoc also says the doctor “never throws,” but unguarded `readText`, directory scans,
and structural parsing can still throw on permissions, concurrent deletion, malformed
state, or I/O failure. “Does not intentionally gate on findings” is the accurate
contract; exception containment would need to be implemented per diagnostic.

`kiteSsotVerify` is a resolved-value printout, not verification. It does not compare
expected and actual values, calculate a change plan, show a unified diff, or return a
non-zero status on drift. Its advice to combine `dryRun` with sync tasks promises “exact
edits,” while current dry-run logging is only summaries and has unsafe output semantics.
Neither task reports plugin/KGP/AGP/Gradle/JDK versions, selected apps/targets/variants,
hooked tasks, cacheability, source ownership, or the effective locale/resource model.

**Recommendation:** define a shared typed `Diagnostic` model with stable IDs, severity,
location, expected/actual values, remediation, and documentation URL. Offer:

- `kiteSsotPlan`: read-only exact change plan/diffs;
- `kiteSsotCheck`: strict, non-mutating drift and configuration gate;
- `kiteSsotDoctor`: forgiving interactive explanation;
- JSON and SARIF output plus Gradle Problems API integration.

The strict check should validate final AGP variants and Xcode build settings, not source
substrings, and should be safe to run even when optional configuration is incomplete.

### P1-19: the Gradle architecture is not ready for the advertised compatibility surface

**Evidence:** `KiteSsotPlugin.kt:15-207, 506-525`, the `compileOnly` dependencies at
`build.gradle.kts:35-42`, and the single CI environment.

The root plugin captures a root extension and mutates each subproject from
`rootProject.subprojects {}`. It uses root and subproject `afterEvaluate`, builds a
mutable app-module list across projects, and queries raw files during provider
calculation. This works in a conventional build but fights Gradle's project isolation
model and constrains parallel configuration. [Gradle's isolated-projects model](https://docs.gradle.org/current/userguide/isolated_projects.html)
specifically removes the assumption that one project may freely reach into another.

KGP and AGP are both `compileOnly`, yet the fallback is asymmetric. When KGP lives in a
sibling classloader, native opt-ins, worker generation, and BuildConfig are skipped; the
warning mentions only the first two. A plugin advertised as the authority can silently
omit major outputs because of how the consumer declared plugins. AGP wiring has no
equivalent visibility guard and therefore risks linkage failure rather than graceful
degradation. Compiling against one current KGP/AGP pair does not establish a compatible
range.

Other compatibility problems:

- the declared Gradle minimum is only a warning, even though execution below it is
  explicitly unsupported;
- root projects that themselves apply Android/KMP are never configured;
- task registration names are generic (`syncIosConfig`, `syncIosLogo`,
  `sanitizeIosProject`) and can collide with consumer tasks;
- feature activation is read at different lifecycle points, so late DSL/plugin order can
  change whether hooks exist;
- task-name pattern matching is an undocumented dependency on KGP naming;
- the plugin jar is Java-17-loadable, but CI runs only JDK 21 and current Gradle;
- CI's configuration-cache coverage is only the plugin build. This audit added a minimal
  Gradle 8.9 consumer store/invalidation smoke, but functional fixtures still do not
  assert first-run/reuse across KMP, AGP, generation, or locale changes; isolated
  projects are not tested.

**Recommendation:** make the root plugin a model/aggregation layer and apply small
module-local adapter plugins to participating projects. Communicate through a Gradle
shared service or consumable model, not cross-project mutable state. Use public AGP/KGP
APIs behind version-specific adapters, fail clearly for unsupported ranges, prefix every
task, and test a declared matrix. Configuration cache, isolated projects, parallel
execution, and remote build cache should be release gates, not aspirational badges.

### P1-20: global experimental opt-ins should not be a default identity policy

**Evidence:** the `propagateInteropOptIns = true` convention at
`KiteSsotPlugin.kt:45` and the wiring at `InteropOptIns.kt` /
`KiteSsotPlugin.kt:217-228`.

Applying experimental Native opt-ins to every Native compilation changes source-level
compiler policy, suppresses valuable warnings across production and tests, and accepts
future binary/source compatibility risk on behalf of every module. It is unrelated to
app identity and is enabled merely by applying the plugin. `extraOptIns` expands the
same root-global behavior, with no per-module/source-set scope or marker validation.

**Recommendation:** default off, move to a separately named `compilerPolicy` block, and
allow explicit project/target/compilation scopes. Prefer local opt-ins close to the APIs
that need them. Diagnostics should report why each propagated marker is needed and fail
on unknown marker FQNs only when strict validation is requested.

---

## 7. P2: important design, quality, and maintenance findings

### P2-01: image processing has resource-exhaustion and reproducibility gaps

`ImageIO.read` fully decodes with no byte-size, dimension, pixel-count, or file-type
limit anywhere in the task. A huge-dimension/compression-bomb image can exhaust the
Gradle daemon. Any
ImageIO-supported content is accepted through a property documented as PNG. Foreground
and background inputs may overlap outputs; an existing generated launcher PNG can
become the next run's source, causing destructive self-reprocessing and quality loss.

Inspect headers and PNG signatures first, cap bytes/dimensions/pixels, reject input/output
overlap, and perform decoding/rendering in an isolated worker process. Declare renderer
and color-management versions as inputs. Golden tests should cover alpha, aspect fit,
safe zones, exact dimensions, deterministic hashes where supportable, malformed images,
and memory limits.

### P2-02: the code handles errors inconsistently

Some invalid states throw during root `afterEvaluate`; some warn and skip; some mutate
globally after warning; the doctor prints FAIL and succeeds; Android locale wiring
catches `Throwable`; plist failure warns; missing `@InputFile` fails in Gradle before the
custom message. Users cannot predict whether a bad state is fatal.

Adopt a documented policy:

- invalid user configuration: fail during model finalization with aggregated errors;
- unsupported environment/version: fail before mutation, with supported range;
- optional absent integration: skip with a stable diagnostic;
- target ambiguity or parser uncertainty: fail closed;
- drift in strict check: fail without mutation;
- migration conflict: emit plan, never partially apply;
- internal error: preserve cause and file context; do not catch `Throwable`.

### P2-03: release metadata is too thin for a trusted public plugin

The POM has coordinates and Apache license only. Add project name, description, URL,
inception year, SCM connection/tag, issue management, CI management, organization, and
developer information. The implementation jar lacks `META-INF/LICENSE` and, if the
project ever has applicable notice material, `META-INF/NOTICE`; it also lacks
useful manifest data such as implementation title/version and build revision. The
javadoc artifact is effectively a manifest-only 261-byte jar, so the publication
advertises documentation that does not exist.

Generate useful Dokka HTML/Javadoc, publish it, verify module and plugin marker
coordinates in a clean consumer, and add API/ABI validation. The public task classes and
all public extension members are compatibility surface even if the intended API is only
the DSL.

### P2-04: the release supply chain lacks provenance and artifact hardening

P0-05 covers the tag/version/channel correctness failure. Beyond that blocker, the
workflow lacks protected-environment approval, concurrency control, signed/attested
provenance, dependency verification, dependency locking, SBOM, and a GitHub release
with artifact checksums.

Action references use mutable major tags and the runner floats on `ubuntu-latest`.
Pin third-party actions to reviewed commits and pin the release image/toolchains. Add a
preflight that validates clean tree, changelog entry, tag/version equality, credentials,
marker publication, artifact contents, and signatures before either remote write.
Publish staged artifacts atomically where each repository permits, retry channels
independently, and record the exact artifact digest in release provenance.

The wrapper URL has no `distributionSha256Sum`, and the checked-in daemon-JVM criteria
routes automatic JDK provisioning through Foojay redirect URLs without a repository-held
archive checksum. Treat both Gradle and JDK distributions as release inputs: pin their
expected digests/vendor, verify dependency metadata, and record them in provenance.

### P2-05: compatibility claims exceed the test matrix

CI covers one Linux runner, JDK 21, the wrapper Gradle, and the compile-time AGP/KGP
versions. The minimum Gradle 8.5 and Java 17 load target are not exercised. There is no
macOS/Xcode job, Windows path/newline job, AGP 8/9 matrix, KGP range, Kotlin DSL/Groovy
DSL pair, or old/new Gradle pair. No clean consumer resolves the released-style plugin
marker from a repository.

State a narrow, evidence-based compatibility table. Either test each advertised cell or
label it unsupported/experimental. Binary compilation against current APIs is not proof
that older or newer plugin implementations will supply the same symbols and behavior.

### P2-06: functional tests stop before the most important outcome: consumer compilation

The suite has 86 tests, but only eight TestKit scenarios. Most functional assertions
inspect generated text or task output. Generated BuildConfig and worker source are not
compiled; the worker fixture declares `nodejs()` but never runs the helper. Real AGP is
explicitly excluded. There are no `buildAndFail` contracts for bad input, build-cache
relocation tests, concurrent execution tests, symlink/path-escape tests, or first-run /
reuse configuration-cache assertions inside fixtures.

Pure parser/generator tests are valuable and should remain, but they cannot validate
classloader boundaries, plugin ordering, Variant APIs, source-set dependencies, final
manifests, runtime browser behavior, or Xcode semantics.

### P2-07: no public API/ABI or deprecation discipline is enforced

All extension types, properties, action methods, plugin class, and task classes are
public JVM API. There is no binary compatibility validator, explicit API dump, semantic
versioning gate, deprecation window, replacement annotation, or DSL evolution policy.
Changing a task property or abstract member can break convention plugins and typed task
configuration even if README syntax remains unchanged.

Define the supported public surface, make implementation task types internal where
possible, use interfaces/spec types for the DSL, and add ABI dumps reviewed in pull
requests. Publish migration recipes and keep deprecated aliases for an announced period.

### P2-08: documentation is broad but not executable or reliably current

README, FEATURES, CHANGELOG, and KDocs contain substantial effort, yet the duplicated
claims drift. Examples and compatibility statements are not compiled as tests. There is
no generated DSL reference, migration guide by version, troubleshooting diagnostic-ID
index, architecture/ownership explanation, threat/safety model, or evidence-backed
compatibility matrix. The README's requirements line and wiring tables are useful, but
they are not generated from CI results.

Documentation should be generated from tested snippets and the same typed capability
metadata used by diagnostics. Each feature page should state: default, ownership,
mutation behavior, lifecycle, supported platforms/topologies, inputs/outputs, security
notes, cache semantics, and failure modes.

### P2-09: performance is acceptable for a prototype but scales poorly

Positive: pure generators are small, task registration is mostly lazy, source dirs carry
producer dependencies, and cacheable annotations exist where generation was intended.

Scaling costs:

- the root configures and installs callbacks on every subproject whether selected or
  not;
- two `afterEvaluate` layers delay and serialize configuration decisions;
- iOS sync/sanitize tasks are forced out of date and reread/reparse files every run;
- Swift migration recursively walks and reads the app tree on every matching build;
- the safe writer may reread content the caller already read;
- icon generation performs all raster work in the Gradle daemon and rewrites a fixed
  output set;
- one Web Worker/Blob/script parse per call is expensive for short jobs;
- identity inputs invalidate BuildConfig even when identity inclusion is off;
- warning-only scans disappear on cache hits rather than becoming incremental checks.

Select participating projects explicitly, fingerprint plan inputs, use Worker API process
isolation for images, avoid source-tree work during builds, and benchmark both plugin
configuration time and task execution on a representative 50–200-module KMP build.

### P2-10: ownership and generated-file provenance are underspecified

Some outputs are plugin-owned (`build/generated`), some are user-owned but overwritten
(`project.pbxproj`, Podfile, Swift, Info.plist), and some are generated directly into
source resource trees with fixed names. The file header identifies generated Kotlin but
asset files and JSON/XML lack a robust ownership manifest. Cleanup guesses ownership by
filename and extension.

Every generated artifact should carry or be covered by a manifest containing plugin
version, schema version, source hash, renderer/strategy ID, and exact owned paths. Never
delete a file merely because its name matches. A `kiteSsotCleanGenerated` task should
remove only manifest-owned build outputs; source migration rollback should use a
separate journal.

### P2-11: the convenience accessor makes the architectural coupling public API

**Evidence:** `KiteSsotAccess.kt:6-17`.

`Project.kiteSsot` looks convenient in subproject scripts, but it performs
`rootProject.extensions.getByType` and therefore encourages precisely the cross-project
model access that blocks isolated projects. It also throws Gradle's generic missing-type
exception when the root plugin was not applied, exposes mutable root properties to
module build logic, and gives no resolved/finalized snapshot: subprojects can observe
different values depending on evaluation timing.

Deprecate it in favor of a module-local read-only model extension supplied by the adapter
plugin. That model should expose finalized providers and value provenance, not the root
mutable DSL object. If a compatibility accessor remains, make absence diagnostics
plugin-specific and document its lifecycle/isolated-project limitation.

### P2-12: `sharedModule` is globally mandatory even when no selected feature needs it

**Evidence:** unconditional validation at `KiteSsotPlugin.kt:103-109`.

An Android-only user who wants only application ID/version/SDK propagation must still
invent a `sharedModule`. So must a user who wants only verify/doctor or Apple identity
without Compose resources, BuildConfig, web generation, or module migration. Meanwhile,
all iOS tasks are registered and iOS sync defaults on even in Android-only builds. This
makes the smallest onboarding path communicate a larger topology than the actual
capabilities require and turns an irrelevant value into a configuration failure.

Require dependencies per capability: a Compose-resource scanner needs a selected
resource project; BuildConfig needs a selected source set; migration needs explicit
from/to modules; Android identity needs none of those. Register or activate adapters
from the finalized capability graph and let read-only diagnostics run on a partial model.

---

## 8. Documentation and KDoc truth audit

The following are not stylistic preferences; each statement can misdirect a consumer or
hide a material limitation.

| Claim or API wording | Code truth | Required documentation correction |
|---|---|---|
| Unset identity fields are not propagated | plist references are inserted based on toggles even when values are unset | Define exact per-sink absent behavior and fix implementation |
| `iosBundleSuffix`/Android suffix: “Null = no suffix” | `Property<String>` has no Kotlin-null assignment contract; absence/orElse empty is used | Say “unset/absent,” show `.unset()` or omit it |
| `versionName` may use prerelease text with an Android override | the same raw string becomes Apple's marketing version | Split Apple/Android versions and state each grammar |
| `javaVersion` applies to every Android module | KMP-native Android wiring does not apply it, and KGP visibility can skip alignment | Narrow the claim or implement every adapter |
| locale list is supported app locales | auto-scan is Compose-only; Android uses it as a package filter; Apple only updates `knownRegions` | Separate metadata, resources, and packaging filters |
| headline/table claims say iOS changes are target-scoped | an edge-case caveat discloses global fallback; parser failure can still broaden scope and all app targets share values | Put limitations beside every claim now; state selector/fail-closed behavior after implementing it |
| append-only plist references preserve existing state while establishing SSOT | a hardcoded conflicting value is retained, so the requested state does not converge | Define `FAIL`/`KEEP`/`REPLACE` conflict policy |
| “Every plain `import X`” is rewritten | a line regex, not a Swift parser, can also match import-looking text inside multiline comments/strings | Say “lexically matching lines” or parse Swift statements |
| Web helper is a no-dependency quick start | it imports coroutines, assumes browser APIs and CSP permission | State dependency/runtime/CSP constraints |
| cancellation safely discards the worker | detached deferred leaves it running | Remove the claim until lifecycle tests prove it |
| preview shows exact edits | current output is summaries, not an exact diff | Replace with a real plan/diff task |
| providers keep sensitive BuildConfig values out of the build file/VCS | true only for those two locations; values still enter source, task inputs/caches, and binaries | Keep the “not a secret store” warning but enumerate all remaining exposure paths and prohibit credentials |
| cleanup “always” safely removes generated assets | provenance is inferred from fixed filenames; user files can be deleted | Require manifest/provenance and backup/confirmation |
| ProMotion flag opts into up to 120 Hz | one plist key does not guarantee display cadence or app performance | Describe it narrowly and link Apple guidance |
| encryption boolean tells users how to answer export compliance | legal applicability depends on the app and jurisdictions | Avoid legal conclusions; describe only plist behavior |
| tested Gradle/AGP/KGP/JDK compatibility | only one CI stack is exercised, plus a manual Gradle 8.9 smoke in this audit | Publish a generated tested matrix |

Repository-level drift is already visible. `FEATURES.md` labels its current section a
“v1.6.x working tree” while the build is 1.7.0, and its “honest limitations” still says
plugin validation is red, bytecode is Java 21, classic Android is eager/module-wins,
pbxproj rewriting is target-blind, region tags are unmapped, and `Contents.json` has no
backup: all superseded by current code. It also refers to old `AUDIT` section numbers as
if they were maintained product documentation. Conversely, its proposed “graceful global
fallback” is now implemented but is exactly the fail-open P0 in this audit.

The working source and tests rename KiteCore's worker type from `KiteWorker` to
`WebWorker`, while README and CHANGELOG still use `KiteWorker`. README's tested-version
claim is stronger than CI, its “drop onto a live production app” safety pitch conflicts
with default plist insertion and migration behavior, and the changelog presents AppIcon
backup as complete without mentioning that cache restoration bypasses it. These files
need one truth-generation/checking workflow, not another manual reconciliation pass.
Production KDoc also tells maintainers to consult sections of `FABLE5_AUDIT.md`; an audit
snapshot is not a stable published protocol specification or API reference.

KDoc coverage is uneven despite the volume. `KiteSsotPlugin` itself has no public class
KDoc explaining root-only scope, mutation, defaults, compatibility, or lifecycle.
Most propagation booleans have no individual KDoc. Public task types expose implementation
details without a stable-API warning. Generated source has stronger prose than the API
that activates it. Finally, the published documentation jar is empty, so none of the
source KDoc reaches normal artifact consumers.

### Documentation system to build

1. A one-page mental model: model, adapters, generators, checks, migrations.
2. A default/safety table for every capability.
3. Tested Android, iOS, Compose, multi-app, and convention-plugin examples.
4. A compatibility matrix generated from CI results.
5. A versioning migration guide, especially for existing Play releases.
6. A diagnostics catalog keyed by stable codes.
7. A file-ownership and rollback guide.
8. Security notes for paths, XML, generated constants, raw JavaScript, and build caches.
9. A generated DSL reference from KDoc/Dokka with real API signatures.
10. An architecture decision record explaining why any source rewrite remains necessary.

---

## 9. Test strategy required before calling the plugin production-safe

### 9.1 Fast unit/property/fuzz layer

Keep the existing pure tests and add property-based/fuzz suites for:

- version monotonicity, collisions, bounds, leading zeroes, and migration baselines;
- Android/Apple ID and version grammars;
- BCP-47 canonicalization and Android/Apple derivation;
- OpenStep target extraction/rewrite with comments, escaped strings, nested lists,
  CRLF, Unicode, multiple targets/configurations, and unknown future objects;
- Podfile and Swift transformations, explicitly proving comments/strings are untouched;
- plist duplicate keys, malformed key/value order, XML/binary formats, XXE, entity
  expansion, encodings/BOMs, and round-trip preservation;
- Kotlin identifiers/keywords, duplicate fields, all control characters, finite numeric
  rendering, and source injection;
- image headers, huge dimensions, malformed/truncated data, alpha/color behavior, and
  exact output manifests;
- file containment and symlink-safe traversal/deletion on every supported OS.

Parsers and rewriters should be idempotent (`f(f(x)) == f(x)`), preserve unrelated
bytes wherever promised, and either produce a complete typed change plan or no change.

### 9.2 Gradle consumer integration layer

Build real fixture projects from external repositories/plugin marker coordinates. For
each supported matrix cell, assert first run and second run behavior, `UP-TO-DATE`, local
and relocated build-cache hits, configuration-cache store/reuse, isolated projects,
parallel execution, and clean working tree after ordinary builds.

Required fixtures:

- classic AGP application + library, KMP-native Android library, and multiple app
  variants/flavors;
- KGP in root `apply false`, only in subproject, convention plugin, included build, and
  version catalog aliases;
- root project itself applying Android/KMP;
- nested/renamed shared projects and two application modules;
- custom-named JS browser and Node targets, wasmJs, generated source compilation, and
  sources/Dokka tasks;
- absent/invalid values using `buildAndFail` with stable messages;
- output symlink, `../` path, input/output overlap, stale dry-run output, concurrent
  invocation, partial write failure, and cache restoration over user content.

### 9.3 Platform end-to-end layer

On Android, run manifest/resource merge and compile/package representative variants.
Assert final application ID, version code/name, label, SDKs, resource configuration,
launcher/adaptive/monochrome resources, and no unintended dependency-locale stripping.
Test both AGP 8 and 9 if both are advertised.

On macOS, use real generated and checked-in Xcode projects with one app, multiple apps,
extensions/tests, generated and explicit plists, CocoaPods and non-CocoaPods integration,
multiple configurations, spaces/Unicode, and current Xcode. Query final settings with
`xcodebuild -showBuildSettings`; do not infer success solely from file text. Confirm the
current build consumes changes or keep them out of build hooks.

For the runtime worker artifact, execute compiled production bundles in headless
Chromium, Firefox, WebKit, and Node where supported. Test success, thrown/rejected jobs,
syntax error, worker construction failure, CSP denial, cancellation, timeout, concurrent
calls, transferables, large messages, and teardown/leak behavior.

### 9.4 Release/supply-chain layer

Before publishing, create a temporary local Maven/Plugin Portal-style repository, resolve
the plugin by ID from a clean consumer, inspect marker/POM/sources/docs/license/manifest,
run a smoke build on JDK 17 and 21, compare reproducible artifact hashes, validate ABI,
verify signatures/attestation/SBOM, and prove the tag, POM version, implementation
manifest, changelog heading, and GitHub release all agree.

### Minimum compatibility matrix

| Dimension | Minimum release-gate cells |
|---|---|
| OS | Linux, macOS; Windows for path/text helpers |
| JDK | 17 and 21 |
| Gradle | declared minimum, one middle LTS/common version, current supported maximum |
| KGP | oldest supported, current stable, newest explicitly supported |
| AGP | oldest supported 8.x and supported 9.x, each with its required Gradle/JDK |
| DSL | Kotlin DSL and Groovy DSL smoke |
| Gradle modes | configuration cache store/reuse, build cache relocation, parallel, isolated projects |
| Apple | oldest supported Xcode and current Xcode on macOS |
| JS | browser target and Node target distinguished; wasmJs if claimed |

Do not create a Cartesian-product explosion. Select representative compatible stacks,
then run focused boundary jobs for each dimension. Publish the exact tested table.

---

## 10. Product scope: what is overbuilt, underbuilt, and missing

### Overbuilt or misplaced

- A raw JavaScript worker runtime inside a Gradle identity plugin.
- A second general-purpose BuildConfig ecosystem without compiler/runtime integration
  maturity.
- Default global experimental compiler opt-ins.
- Automatic CocoaPod/Swift rename inference during ordinary builds.
- A handwritten partial pbxproj parser for continuous configuration when xcconfig/build
  settings or a dedicated parser/tool may remove the need.
- A large family of propagation booleans compensating for capability boundaries that
  should be explicit objects.

These are not necessarily bad features. They are separate products with different
release, compatibility, and security obligations. Splitting them makes the core plugin
smaller and allows each to become excellent.

### Underbuilt relative to the promise

- multi-app, multi-target, variants/flavors/configurations, and white-label branding;
- canonical cross-platform version and locale models;
- safe generated Android and Apple integration that does not mutate source;
- target/resource/manifest verification against final build state;
- plan/apply/check workflow with exact diffs, provenance, rollback, and CI formats;
- project selection and nested/composite/convention-plugin topology;
- compatibility adapters and a tested support matrix;
- deterministic modern icon generation (Android monochrome; Apple dark/tinted);
- public API lifecycle, documentation artifact, diagnostics catalog, and migration guides;
- secure filesystem containment and adversarial input handling.

### High-value missing feature sets

1. **Named applications and environments.** Model phone/Wear/TV/demo/enterprise apps,
   debug/release/staging environments, target selectors, ID/name suffixes, and branding
   overlays without duplicating the base identity.
2. **Version intelligence.** Separate marketing version and platform build numbers;
   validate monotonicity against a checked-in baseline or optional Play/App Store query;
   emit release notes and CI outputs without making network access part of configuration.
3. **Localization intelligence.** Canonical BCP-47 model, default locale, resource
   discovery across Compose/Android/Apple, coverage matrices, missing-key reports,
   Android 13 locale config, and Apple localization declarations.
4. **Brand system.** One vector/high-resolution source plus named variants, adaptive and
   monochrome Android assets, Apple Light/Dark/Tinted icons, splash/launch assets,
   notification icons, watchOS/tvOS/macOS sets, deterministic previews, and validation.
5. **Capability-aware platform support.** Android, iOS, macOS, watchOS, tvOS, JS browser,
   Node, wasmJs, desktop, and server sinks should declare what they consume rather than
   inheriting unrelated root toggles.
6. **Policy and compliance checks.** Deployment targets, privacy manifests, required
   reason APIs, signing/team configuration presence, store metadata readiness, manifest
   permissions, package visibility, and export settings: as opt-in, non-legal diagnostics.
7. **Machine-readable resolved model.** Export sanitized JSON for CI/release tooling,
   IDEs, custom platform generators, and third-party adapters, excluding secrets.
8. **IDE/UX integration.** Rich Gradle Problems entries, clickable file/line locations,
   a generated HTML report with diffs and icon/locale previews, and quick-fix commands.
9. **Extensibility SPI.** Allow platform adapters to consume a stable resolved model and
   contribute diagnostics/artifacts without modifying the core plugin.

---

## 11. A revolutionary but practical 2.0 design

### 11.1 Product decomposition

Publish cohesive components:

| Component | Responsibility |
|---|---|
| `io.github.yuroyami.kitessot` | root identity/application model, plan/check/report |
| `kitessot-android` adapter | module-local AGP Variant API integration |
| `kitessot-apple` adapter | selected Xcode targets, generated xcconfig/plist/assets |
| `kitessot-compose-resources` adapter | locale/resource discovery and coverage |
| `kitessot-migrate` plugin | explicit transactional source migrations only |
| `kitessot-constants` optional adapter | typed public runtime metadata generation |
| `kitessot-worker` runtime/plugin | typed cross-environment worker facility, if retained |

The root plugin can apply adapters to explicitly selected projects, but adapters execute
inside their own project/classloader. The resolved immutable model travels through a
well-defined shared service/model interface.

### 11.2 Suggested DSL

```kotlin
plugins {
    id("io.github.yuroyami.kitessot") version "2.x"
}

kiteSsot {
    identity {
        organization.set("Acme")
        product.set("Orbit")
        marketingVersion.set("2.4.0")
        locales {
            default.set("en")
            supported.addAll("en", "fr", "ar-DZ")
        }
    }

    applications {
        create("mobile") {
            android {
                projectPath.set(":androidApp")
                applicationId.set("com.acme.orbit")
                versionCode.set(2_004_000)
                variants.named("debug") {
                    applicationIdSuffix.set(".debug")
                    displayNameSuffix.set(" Dev")
                }
            }
            apple {
                projectFile.set(layout.projectDirectory.file("iosApp/Orbit.xcodeproj/project.pbxproj"))
                targets.add("Orbit")
                bundleId.set("com.acme.orbit")
                buildNumber.set("20400")
            }
            branding.use("orbit")
        }
    }

    branding {
        create("orbit") {
            source.set(layout.projectDirectory.file("brand/orbit.svg"))
            android.monochrome.set(layout.projectDirectory.file("brand/orbit-mono.svg"))
            apple.dark.set(layout.projectDirectory.file("brand/orbit-dark.svg"))
            apple.tinted.set(layout.projectDirectory.file("brand/orbit-tinted.svg"))
        }
    }

    safety {
        sourceMutation.set(SourceMutation.DISABLED)
        pathBoundary.set(PathBoundary.ROOT_PROJECT)
        conflicts.set(ConflictPolicy.FAIL)
    }
}
```

Important differences:

- every app and Apple target is selected, never guessed;
- Android and Apple versions/IDs are separately typed;
- variants are overlays, not global exceptions;
- paths are Gradle file/project properties, not overloaded strings;
- generated sinks are explicit and source mutation defaults to disabled;
- the model is extensible without adding another root boolean.

### 11.3 Continuous-build architecture

```text
DSL + conventions
      |
      v
immutable validated model -----> machine-readable sanitized model
      |
      +---- Android adapter ----> build/generated resources + Variant API
      |
      +---- Apple adapter ------> build/generated xcconfig/plist/assets
      |
      +---- metadata adapter ---> build/generated Kotlin
      |
      `---- checks -------------> Problems API / JSON / SARIF / HTML
```

No continuous path writes a tracked source file. Android consumes generated resources
through AGP. Apple should consume generated xcconfig/build-setting layers and generated
assets/plists through a stable Xcode integration established once. If Xcode cannot
consume a generated artifact without editing the project, an explicit bootstrap
migration should make only the minimal structural edit and thereafter leave source
untouched.

### 11.4 Migration architecture

Migration is a separate two-phase protocol:

1. `kiteSsotMigrationPlan` parses files, selects exact targets, validates containment,
   writes unified diffs plus source hashes, and changes nothing.
2. The developer reviews the plan.
3. `kiteSsotMigrationApply --plan ...` acquires a workspace lock, verifies source hashes,
   stages all new bytes, validates syntax/project loading, commits atomically, and writes
   a rollback journal.
4. `kiteSsotMigrationRollback` restores the journal if no intervening file changed.

Ambiguity is an error. No “best effort” global fallback exists. The migration tool can
support more formats over time without endangering every build.

### 11.5 UX principles

- **Safe on apply:** minimal configuration has zero source-tree writes.
- **Tell before touch:** exact plan/diff precedes every migration.
- **Select, never infer:** multiple projects/apps/targets require names.
- **Fail closed:** unsupported syntax leaves every file untouched.
- **One error pass:** aggregate all model issues with property paths and fixes.
- **Explain provenance:** every value shows explicit/convention/derived origin.
- **Final-state checks:** inspect merged AGP variants and resolved Xcode settings.
- **Stable diagnostics:** every warning/error has a searchable ID and machine form.
- **Reproducible generation:** outputs live under build directories and declare all
  inputs/implementation versions.
- **Honest support:** an automatically published matrix says exactly what was tested.

---

## 12. Prioritized remediation roadmap

### Phase 0: stop dangerous behavior before another release

1. Fix version-code width, leading-zero handling, bounds, and regression tests.
2. Default `propagateSharedModule`, `syncIos`, `sanitizeIosProject`, and experimental
   opt-ins off for new users; remove automatic source mutation from build hooks.
3. Make pbxproj target ambiguity/parser failure fatal and require a target selector.
4. Remove build caching from every in-place/user-owned output task; separate dry-run
   from output-producing tasks.
5. Enforce root containment, reject absolute/escaping/symlinked paths, and make generated
   cleanup manifest-owned and symlink-safe.
6. Reject multi-app/multi-target identity collisions instead of warning and continuing.
7. Tie artifact version to the release tag and run a complete preflight before publish.
8. Correct the false KDocs immediately: cancellation, secrets, unset propagation,
   target scoping, preview, cleanup safety, and platform version semantics.

**Exit criterion:** an ordinary build cannot modify tracked files; invalid/ambiguous
input cannot broaden mutation; version upgrades cannot lower the code; no cache hit can
overwrite user content without a recoverable operation; release version is provably the
tag version.

### Phase 1: establish a trustworthy 1.x maintenance line

1. Add typed aggregate validation and stable diagnostic IDs.
2. Add strict `check` and read-only exact `plan` tasks.
3. Split path/module/pod/framework concepts without breaking existing properties;
   deprecate ambiguous aliases.
4. Compile generated source and apply real AGP in functional tests.
5. Add Gradle minimum/current, JDK 17/21, AGP/KGP boundary, configuration-cache, build
   cache, parallel, and macOS/Xcode release gates.
6. Harden XML/image/input limits and transactional file writing.
7. Publish real Dokka, complete POM/jar metadata, ABI dumps, and artifact smoke tests.
8. Make icon and cleanup ownership explicit; add Android monochrome and Apple modern
   variants only after ownership is safe.

**Exit criterion:** every supported integration has an end-to-end fixture, strict checks
can gate CI without mutation, and published compatibility/documentation matches the
matrix.

### Phase 2: build the 2.0 model and adapters

1. Introduce named applications, platform target selectors, environments, and variants.
2. Create immutable typed IDs/versions/locales and provenance-aware resolution.
3. Move Android integration into a module-local Variant API adapter.
4. Replace continuous Apple source rewrites with generated xcconfig/plist/assets plus a
   one-time explicit bootstrap migration.
5. Introduce a stable adapter SPI and sanitized model export.
6. Move compiler opt-ins, BuildConfig, and worker into optional, clearly scoped
   components.

**Exit criterion:** isolated projects works, no root cross-project mutation remains, all
continuous outputs are generated/owned, and multi-app/target projects are first-class.

### Phase 3: category-leading KMP experience

1. Localization coverage and store/platform metadata generation.
2. Full deterministic brand pipeline and preview gallery.
3. Release monotonicity/store readiness integrations that are opt-in and network-isolated.
4. Rich HTML/IDE report, SARIF, Gradle Problems, and quick fixes.
5. Ecosystem adapters for popular KMP layouts and convention-plugin templates.
6. Performance budgets and benchmarks for 50/100/200-module builds.

**Exit criterion:** the plugin is not merely a copier of values; it is a safe,
explainable, extensible application-model layer that materially reduces KMP platform
drift.

---

## 13. File-by-file disposition

This is a concise ownership map, not a substitute for the findings above.

| File / area | Disposition |
|---|---|
| `KiteSsotPlugin.kt` | Major redesign: split model, adapters, generation, checks, migration; remove cross-project mutation and `afterEvaluate` |
| `KiteSsotExtension.kt` | Replace stringly global bag with named typed applications/policies; keep compatibility facade during migration |
| `KiteSsotAccess.kt` | Deprecate mutable root cross-project accessor; expose a module-local resolved model |
| Android/iOS/web/buildConfig extensions | Keep domain blocks, but scope to named consumers and document defaults/ownership |
| `VersionCode.kt` | Immediate correctness fix; then replace with explicit strategy/value object |
| `ClassicAndroidWiring.kt` | Keep `finalizeDsl` idea; move module-local and add final-variant checks |
| `KmpAndroidLibraryWiring.kt` | Complete behavior or narrow claims; test with real plugin |
| `KotlinJvmTargetWiring.kt` | Retain only behind explicit compiler/toolchain policy and real KGP matrix |
| `InteropOptIns.kt` | Move to optional compiler-policy component; default off |
| `PbxprojTargetScope.kt` | Do not trust as a complete parser; fail closed and preferably replace with established structured tooling |
| `PbxprojRewrite.kt` | Convert to typed change-plan renderer with target selector and syntax encoders; no global fallback |
| `SharedModuleRewrite.kt` | Migration-only; explicit from/to and supported syntax; never auto-detect unrelated pods |
| `PlistSanitize.kt` | Retain pure-transform shape, replace/strengthen parser and conflict/format policy |
| `RewriteSafety.kt` | Replace per-file helper with containment, lock, batch transaction, provenance, and rollback services |
| `SyncIosConfigTask.kt` | Remove from normal build; split into plan/apply migration operations |
| `SanitizeIosProjectTask.kt` | Prefer generated plist/settings; migration-only for source plists |
| `SyncAndroidLogoTask.kt` | Generate under build dir through AGP; add deterministic/provenance/input limits |
| `SyncIosLogoTask.kt` | Generate plugin-owned asset catalog; structurally support modern variants; never cache user-owned overwrite |
| `CleanupLegacyAppLogoArtifactsTask.kt` | Require provenance, replacement plan, confirmation, backup/rollback; otherwise remove |
| `ImageOps.kt` | Isolate process, bound inputs, version renderer, add golden tests |
| `GenerateBuildConfigTask.kt` / `BuildConfigGen.kt` | Typed fields, keyword/duplicate validation, secrets warning; consider optional component |
| `GenerateIoWorkerTask.kt` / `IoWorkerGen.kt` | Move to runtime/companion product; rewrite lifecycle and platform support |
| `LocaleTags.kt` | Replace permissive Android qualifier heuristic with canonical BCP-47 model plus edge renderers |
| doctor/verify tasks | Build one typed diagnostic/change-plan core; add strict and machine-readable modes |
| pure tests | Retain and expand with properties/fuzz/adversarial fixtures |
| functional tests | Replace text-only confidence with real consumer compilation/platform outcomes |
| `build.gradle.kts` | Add full publication metadata, ABI/docs/reproducibility/security verification, and tested version ranges |
| CI/publish workflows | Matrix CI, pinned supply chain, tag/version gate, provenance/signing/staged release |
| README/FEATURES/CHANGELOG | Generate/test claims, remove contradictions, add compatibility/safety/migration model |

---

## 14. Definition of “100% horizon” for this plugin

Perfection is not the number of supported switches. The horizon is reached when:

- the resolved cross-platform application model is coherent, typed, inspectable, and
  extensible;
- applying the plugin is safe and read-only until explicit generated sinks are selected;
- ordinary builds never rewrite tracked source;
- every mutation is explicit, target-selected, previewable, transactional, reversible,
  and contained;
- Android and Apple final build states: not approximate source text: are verified;
- multi-app, multi-target, variants, and modern branding/localization are first-class;
- generated artifacts are deterministic, bounded, provenance-marked, and cache-correct;
- failures are aggregated, precise, stable, machine-readable, and actionable;
- public API and behavior evolve under semantic-version/ABI/deprecation discipline;
- every compatibility claim is backed by a published matrix and end-to-end evidence;
- release artifacts are complete, reproducible, signed/attested, and tied to their tag;
- optional runtime conveniences live in focused components with their own tests and
  lifecycle guarantees;
- documentation is generated from tested truth and states ownership, safety, defaults,
  support, and failure behavior without overclaiming.

The current implementation is not “0%”: it contains several strong primitives and a
clear developer pain point worth solving. But the path to a world-class KMP tool is not
to add more propagation toggles to automatic rewrites. It is to become a typed
application model with safe platform adapters, exact diagnostics, and explicit
migrations. That change would turn `kitessot` from a convenient synchronizer into a
trustworthy foundation that teams can place above Android, Apple, Compose, web, and
future KMP targets without fearing what the next build will silently touch.
