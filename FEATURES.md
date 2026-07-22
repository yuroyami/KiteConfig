# kitessot feature reference

This file describes the current implementation. It is a capability inventory,
not a roadmap or idea bank. The [README](README.md) is the guided setup and
migration document.

## Product architecture

| Surface | Current capability |
|---|---|
| Central model | One provider-backed `kiteSsot {}` extension applied at the root; optional identity, toolchain, locale, selector, and migration values |
| Android adapter | Authoritative AGP `finalizeDsl` configuration for selected applications, classic libraries, and KMP-native Android libraries |
| KMP adapter | Selected native compiler opt-ins, global compatible Kotlin/JVM alignment, generated `commonMain`, and explicitly selected browser-target source |
| Apple migration | Target-scoped pbxproj rewrite, hardened source-plist sanitation, explicit Podfile/Swift module-reference migration |
| Branding installers | Owned Android adaptive/legacy icon tree and owned Apple universal AppIcon output |
| Diagnostics | Resolved-model report, resilient doctor, strict JSON/SARIF check, stable IDs, read-only mutation plan |
| Release guardrails | Fixed-width Android version derivation, explicit Play baseline, independent Apple marketing/build numbers |

The model is configured only in the root project and frozen after root evaluation.
The deprecated `Project.kiteSsot` cross-project accessor remains compatibility-only
and is not Isolated-Projects safe.

## Compatibility evidence

- The standalone plugin floor is Gradle 8.5.
- A real published-plugin consumer applies KGP 2.4.0 on Gradle 8.5, wires a
  fields-only BuildConfig into `commonMain`, and compiles a source that imports it.
- The same KGP integration proves strict configuration-cache reuse on the current
  Gradle wrapper. KGP's stable numeric `-release-N` runtime metadata is accepted;
  RC, Beta, dev, and arbitrary suffixes are rejected.
- AGP 8.5.2 classic adapters and AGP 9.1.1 classic/KMP-native adapters run in
  separate real-consumer fixtures.

## Root model API

### Identity and release

| Property | Meaning |
|---|---|
| `appName` | Optional display name; Android receives a manifest placeholder and Apple migration receives product/display-name settings. |
| `versionName` | Optional Android display version and default provider for `iosMarketingVersion`; when consumed, non-blank, control-free, and at most 255 characters. |
| `versionCodeOverride` | Optional Android store build number in `1..2_100_000_000`. |
| `android.publishedVersionCode` | Optional offline lower bound enforced while Android version propagation is active for a detected app. |
| `iosMarketingVersion` | Apple `MARKETING_VERSION` during explicit iOS sync, validated as three non-negative integer components. |
| `iosBuildNumber` | Apple `CURRENT_PROJECT_VERSION` during explicit iOS sync, independent from Android and validated as one to three integer components. |
| `ios.deploymentTarget` | Compatibility assertion required by the Apple universal-icon installer; it does not write `IPHONEOS_DEPLOYMENT_TARGET`. |
| `bundleIdBase` | Optional reverse-DNS base for Android and Apple identifiers. |
| `androidApplicationIdSuffix` | Optional Android-only suffix. |
| `iosBundleSuffix` | Optional Apple-only suffix. |

When an enabled consumer needs a derived Android version code, it uses an
order-preserving fixed width: `x.y.z -> 1xxxyyyzzz`. Exactly three segments in
`0..999` are required, with no leading zeroes. Use `versionCodeOverride` for any
other scheme.

### Project and target selection

| Property | Selection rule |
|---|---|
| `androidApplicationProjects` | Exact absolute Gradle paths for app-scoped identity and locale operations. Empty selects a sole detected app; multiple apps require an explicit selector when an app-scoped operation is enabled. The single logo sink accepts at most one app (or an explicit/legacy directory when no app plugin is present); SDK/JVM policy remains global. |
| `sharedProjectPath` | Exact KMP project that owns `commonMain` generated source and the default KMP scope. |
| `interopProjectPaths` | Exact KMP projects eligible for native compiler policy; empty may use the selected shared project. |
| `web.projectPaths` | Exact KMP projects eligible for worker generation; empty may use the selected shared project. |
| `web.browserTargetNames` | Exact Kotlin/JS target names known by the consumer to use a browser runtime; always required when generation is enabled. |
| `ios.targetNames` | Exact Xcode application target names. Empty is accepted only for a sole application target. |

Active selectors are validated and de-duplicated. Missing, wrong-plugin, multi-app,
multi-target bundle-ID, and classloader-visibility ambiguity is an error.

### Typed filesystem selection

Preferred properties are:

- `sharedProjectPath`
- `iosSharedModuleName`
- `iosPreviousSharedModuleName`
- `composeResourcesDirectory`
- `androidAppDirectory`
- `iosPbxprojFile`
- `iosPodfileFile`
- `iosInfoPlistFile`
- `iosAppDirectory`
- `iosAppIconDirectory`

Legacy/conflated inputs (`sharedModule`, `oldSharedModuleName`,
`androidAppModule`, `iosProjectPath`, `iosPodfilePath`, `iosInfoPlistPath`,
`iosAppDir`, `iosAppiconsetPath`) remain as compatibility inputs. Mutation tasks
constrain all resolved paths to the root or selected iOS tree and reject
symlinks/special files.

### Platform-resource locale model

- Internal form: the canonical BCP-47 subset that maps consistently to Android
  resource qualifiers and Xcode regions (language plus optional script, region,
  and variants), order-stable and de-duplicated. Extensions/private-use are rejected;
  input is bounded to 1,000 entries and 255 characters per raw tag.
- Accepted boundary compatibility: Android `language-rREGION` and
  `b+language+Script+REGION` qualifiers.
- Discovery: exact locale-only resource forms (`values-en`, `values-pt-rBR`, or
  `values-b+sr+Latn`) below an explicit Compose resources directory or the
  selected shared project's conventional directory; direct hyphenated BCP-47 and
  mixed qualifier directory names are ignored. The no-follow shallow scan is
  bounded to 10,000 immediate entries.
- Apple renderer: additive canonical tags for `knownRegions`, preserving `Base`
  and unrelated existing regions because `.lproj` ownership is outside this task.
- Android renderer: AGP 9 locale filters with an AGP 8-compatible qualifier
  fallback, exact-set application-only locale replacement that preserves AGP 8
  density/ABI/other resource configurations (including the ambiguous `car`
  UI-mode token), non-empty when enabled, and behind
  `filterAndroidResources=false` by default.

## Android adapter matrix

| Value | Application | Classic library | KMP-native Android library |
|---|:---:|:---:|:---:|
| app name placeholder | yes | no | no |
| application ID | yes | no | no |
| version name/code | yes | no | no |
| application locale filter | opt-in | no | no |
| compile SDK | yes | yes | yes |
| minimum SDK | yes | yes | yes |
| target SDK | yes | no | no |
| NDK version | yes | yes | unavailable |
| Java source/target | yes | yes | unavailable |
| Kotlin JVM target | when KGP is visible | when KGP is visible | compatible Kotlin targets |

SSOT values run in AGP's finalization phase and win over module-local values.
Unset values leave the module unchanged. SDK relationships, IDs, NDK syntax,
Java levels, and supported runtime tool versions are validated before use.

## KMP generators

### Runtime BuildConfig

- Disabled by default and scoped to `sharedProjectPath`.
- Generates one Kotlin object below
  `build/generated/kitessot/commonMain/kotlin` and wires it to `commonMain`.
- Optional identity fields: app name, version name/code, Android ID, Apple ID,
  locales.
- Typed custom fields: String, Int, Long, Boolean, Double.
- Rejects invalid/reserved identifiers, duplicates, identity collisions,
  arbitrary source fragments, malformed literals, and non-finite doubles.
- Bounded to 512 custom fields, 10,000 characters per String, 65,536 characters
  per legacy transport entry, and 1,048,576 transport characters total;
  `Int.MIN_VALUE` and `Long.MIN_VALUE` receive canonical compilable literals.
- Checksum ownership prevents deletion/replacement of unknown or modified files.
- Build-cache storage defaults off because generated values become cache payload.
- Not a credential store; generated values are public binary inputs.

### Browser Web Worker

- Disabled by default and requires exact KMP project and Kotlin/JS browser target
  selection.
- Generates a single-shot `kiteSsotOffload` helper below the target's owned
  `build/generated/kitessot/<target>Main/kotlin` directory.
- Custom target names and package names are supported.
- Protocol envelopes carry request IDs and explicit success/error state.
- Browser API checks, object-URL revocation, a 30-second default timeout,
  cancellation termination, error normalization, and post-message failure
  handling are generated.
- Requires consumer `kotlinx-coroutines-core`, Blob-worker CSP permission, and
  trusted JavaScript source.
- Node.js-only and wasm targets are unsupported and never inferred.

Build-owned generators remain active when global `dryRun` is true because their
outputs are compilation inputs, not source migrations.

## Native compiler policy

`propagateInteropOptIns` is disabled by default. When enabled, only selected KMP
projects and native compilations receive the three built-in cinterop/Obj-C/native
experimental markers plus validated, de-duplicated `extraOptIns`. It does not
annotate or rewrite source. `interopProjectPaths` scopes only this Native policy;
it does not restrict `javaVersion`, whose Java compatibility and Kotlin/JVM
target alignment is root-global across compatible detected projects.

## Explicit Apple migration

### pbxproj

- Resolves `PBXNativeTarget` application nodes through configuration-list IDs to
  exact `XCBuildConfiguration` spans.
- Never falls back to a global build-settings rewrite.
- Supports explicit one-or-more target selection; assigning one bundle ID to
  multiple app targets is refused.
- Can update product/display name, bundle ID, marketing version, build number,
  and project-level locale regions.
- Missing targets, malformed graph links, missing expected settings, duplicate
  objects, and parser uncertainty abort the complete plan.
- Literal replacement handles special characters without regex replacement
  injection.

### Source Info.plist

- Supports XML property lists only; binary/OpenStep and generated plists are not
  converted.
- Enables secure XML processing and disables external entities/DTDs.
- Rejects unsafe declarations, duplicate or malformed root dictionary entries,
  input over 4 MiB of UTF-8, and any baseline it cannot round-trip losslessly.
- Inserts configured SSOT build-setting references and optional
  `ITSAppUsesNonExemptEncryption` / `CADisableMinimumFrameDurationOnPhone` flags.
- Conflict policy is explicit: `FAIL` (default), `KEEP`, or `REPLACE`.
- A failure returns no partial rewrite.

### Shared-module references

- Requires explicit `iosPreviousSharedModuleName` and new
  `iosSharedModuleName`; no Podfile inference. The old conflated names remain a
  compatibility fallback only.
- Rewrites at most one exact local-pod declaration.
- Rewrites only plain, exact Swift module imports.
- Masks comments, strings, raw strings, and extended regex literals; unterminated
  lexical regions fail closed before any source write.
- Prunes dependency, vendor, build, checkout, user-data, and symlink trees.
- Does not rename a directory, update `settings.gradle`, run CocoaPods, or alter
  qualified/testable/implementation-only/bridging-header imports.

## Branding installers

The branding installers and consumers are checked as one contract. The plugin
does not rewrite the user-owned Android manifest, so consumers must keep it
pointed at `@mipmap/ic_launcher` (and
`@mipmap/ic_launcher_round` when `android:roundIcon` is used), and the selected
Xcode application target must declare `ASSETCATALOG_COMPILER_APPICON_NAME` for the
configured catalog (`AppIcon` by default). The explicit iOS config transaction
aligns an existing assignment and refuses a missing one; diagnostics validate the
Android references and Xcode selection. A successful file installation alone is
therefore not reported as an aligned application.

### Android

- Foreground PNG plus exactly one background PNG or Android-form hex color.
- Strict PNG decoding capped at 32 MiB, 4,096 pixels per dimension, and
  16,777,216 decoded pixels, with no input-under-output or unsafe-path aliasing.
- Aspect-preserving foreground contain/background cover behavior.
- Adaptive foreground/background, legacy square/round images at five densities,
  API 26 wrappers, and API 33 monochrome wrappers when the SSOT
  `android.compileSdk >= 33`.
- First contact refuses unowned output paths and same-stem template collisions.
- Subsequent replacement/removal requires ownership-manifest checksum agreement.

### Apple

- Requires both `syncIos=true` and `propagateLogo=true`; the deployment-target
  property validates compatibility but does not configure the Xcode setting.
- Opaque 1024×1024 foreground-over-background composite plus universal
  `Contents.json`.
- Explicit compatibility contract: Xcode 14+ and `ios.deploymentTarget >= 12.0`.
- Generates the default universal appearance only; Dark/Tinted appearances and
  Icon Composer files remain deliberately outside this raster installer.
- Bounded input decoding and output containment checks.
- With backups enabled (the default), durable first-contact recovery lives below
  `.kitessot/recovery`; checksum ownership tracking is always enforced, and
  `clean` cannot erase recovery copies.
- Reports unreferenced PNGs without deleting unknown assets.

### Legacy Android takeover

- Explicit one-shot task only.
- Finds known pre-pipeline artifacts and same-stem Android Studio template icons;
  before the first ownership manifest, it also includes unowned paths the current
  installer will claim.
- Backs up all candidates with SHA-256 provenance before deleting the first.
- Shares the installer ownership lock and preserves current manifest-owned icons.
- Rolls back removed files if the batch cannot complete.
- Dry-run lists the exact candidates without writing or deleting.

## Diagnostics

| Task | Contract |
|---|---|
| `kiteSsotVerify` | Best-effort resolved model/path report |
| `kiteSsotDoctor` | Resilient aggregate diagnostic; never gates on findings |
| `kiteSsotCheck` | Same diagnostic engine, deterministic JSON or SARIF, fails after report creation on errors or optional warnings |
| `kiteSsotPlan` | Read-only selected operation/path/target/policy report |

Stable diagnostic families cover Android manifest placeholders and launcher-icon
references (`KMPS001`–`KMPS003`), plist and bundle-name compatibility
(`KMPS010`–`KMPS012`), Xcode target/icon/deployment scope (`KMPS020`–`KMPS024`), Android resources
(`KMPS030`–`KMPS031`), locales
(`KMPS040`), version derivation (`KMPS050`), KGP visibility and active AGP/KGP
compatibility (`KMPS060`–`KMPS062`), exact Android/iOS selector validity
(`KMPS070`–`KMPS071`), provider and fingerprint resolution (`KMPS901`–`KMPS940`),
and unexpected engine failure (`KMPS999`).
Plist finding `KMPS011` follows the configured conflict policy: `FAIL` is an
error, `KEEP` is a warning for intentionally preserved drift, and `REPLACE`
remains an error until the explicit migration applies the replacement.

## Source-mutation safety invariants

- Every migration/install capability is disabled by default and absent from
  ordinary build task dependencies.
- Every operation resolves a complete plan before the first source write.
- User-owned text changes use strict UTF-8 reads, path containment, no-follow
  checks, sibling staging, atomic replacement where supported, directory
  durability attempts, and—when backups are enabled—write-once `.kitessot.bak`
  recovery.
- Multi-file iOS plans lock and stage the batch, re-check source snapshots before
  commit, and roll back already committed files on failure. Swift discovery is
  bounded to depth 32 and 10,000 entries; text files are capped at 64 MiB and the
  combined iOS snapshot/render budget is 256 MiB.
- Generated and installed assets use owner IDs, normalized relative paths,
  checksums, lock files, and atomic manifests.
- Unknown, manually modified, escaping, duplicated, special, or symlinked output
  is never silently overwritten or deleted.
- Source installers are intentionally non-cacheable and always re-run safety
  validation. Build-owned generators alone use Gradle outputs/cache semantics.

## Explicit limitations

Current code does not implement:

- Gradle Isolated Projects;
- per-flavor, per-build-type, or per-Xcode-target identity overlays;
- xcconfig generation or automatic Xcode include wiring;
- generated/binary Info.plist conversion;
- automatic project-directory/settings renames;
- Node.js or wasm workers;
- SVG/vector icon input, dark/tinted Apple icon variants, or launch-screen edits;
- store API access, signing, secret management, `pod install`, release upload, or
  automatic version increments.

These are boundaries, not implied features.
