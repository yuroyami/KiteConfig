# KiteConfig 1.0.0: rename plus read-back API

Date: 2026-08-31
Target release: KiteConfig 1.0.0

## Summary

One release does three things:

1. Rename the plugin from KiteSSOT to KiteConfig.
2. Add a read-back API so other build files can read resolved configuration.
3. Rename the generated `BuildConfig` class to `KiteBuildConfig`.

`io.github.yuroyami.kitessot` 4.2.0 is the final release of that line. It stays
on the Gradle Plugin Portal because portal versions cannot be deleted. There will
be no 5.0.0.

### Working assumption

The plugin has no consumers other than its author. No deprecation shims, no
compatibility layer, and no migration guide are needed. Every change below is a
clean break.

## Problem

### The read-back gap

KiteConfig consumes configuration but hands very little of it back. A consumer
who wants the resolved `versionCode` in another build file has to declare the
number a second time, which is the exact duplication the plugin exists to remove.

The plugin is a single source of truth for the platforms it writes to. It is not
yet a single source of truth for the build files themselves.

Six public read-back providers exist today on the root extension:
`androidApplicationId`, `iosBundleId`, `desktopBundleId`, `versionCode`,
`canonicalLocales`, and `resolvedSharedProjectPath`. Four problems keep them from
being useful:

1. **No convenient accessor.** Reaching them needs `extensions.getByType<...>()`
   with the fully qualified type. Nothing signals that read-back exists.
2. **The submodule path is deprecated.** `Project.kiteSsot` is the only
   cross-project accessor and it warns on every use, even though reads through it
   are safe.
3. **Coverage holes.** `versionCode` is public for Android only. The iOS build
   number, iOS marketing version, desktop build number, resolved per-platform app
   name, and the four Android SDK levels are all `internal`.
4. **It returns the mutable model.** A consumer can attempt a write and hit a
   confusing late-mutation failure instead of getting a read-only view.

### The name

"SSOT" is an acronym the reader has to already know. "KiteConfig" says what the
plugin does on sight.

This is the third name. The plugin shipped as `kmp-ssot` at 0.1.0, was renamed to
KiteSSOT in commit `0385259`, and becomes KiteConfig here. The `KMPS` diagnostic
prefix is a leftover from the first name.

## Part A: the rename

### Mapping

| Old | New |
| --- | --- |
| plugin id `io.github.yuroyami.kitessot` | `io.github.yuroyami.kiteconfig` |
| Maven coordinate `io.github.yuroyami:kitessot` | `io.github.yuroyami:kiteconfig` |
| package `io.github.yuroyami.kitessot` | `io.github.yuroyami.kiteconfig` |
| 27 `KiteSsot*` class names | `KiteConfig*` |
| DSL block `kiteSsot { }` | `kiteConfig { }` |
| `-Pkitessot.dryRun`, `.backups`, `.color` | `-Pkiteconfig.*` |
| `-PkiteSsot.version`, `.releaseTag` | `-PkiteConfig.*` |
| backup dir `.kitessot/recovery/` | `.kiteconfig/recovery/` |
| generated package `kitessot.generated` | `kiteconfig.generated` |
| generated class `BuildConfig` | `KiteBuildConfig` |
| 94 `KMPS###` diagnostic codes | `KTCNFG###` |
| `api/kitessot.api` | `api/kiteconfig.api` |
| repo `yuroyami/KiteSSOT` | `yuroyami/KiteConfig` |

Roughly 2438 occurrences across 138 files. The diagnostic codes alone are 360
references across source, tests, and docs.

### Deliberately unchanged

- **Task names.** `kiteDoctor`, `kiteRewriteLogo`, `kiteRewriteXcode`,
  `kitePlan`, `kiteCheck`. These never contained "ssot" and read correctly under
  the new name.
- **`DiscouragedKiteApi`.** Already uses the `Kite` prefix.
- **Diagnostic code numbers.** Only the prefix changes, so `KMPS021` becomes
  `KTCNFG021`. Keeping the numbers preserves the existing ordering and makes the
  rename mechanical.

### Also needs updating

- `settings.gradle.kts`: `rootProject.name`.
- `.github/workflows/publish.yml`: the `KITE_SSOT_VERSION` environment variable,
  the `-PkiteSsot.*` flags, and the `kitessot-$VERSION.jar` filename checks.
- `.github/workflows/ci.yml` and `docs.yml`.
- `.github/ISSUE_TEMPLATE/bug_report.yml`.
- `mkdocs.yml`: site name and URL.
- The local git remote, which still points at the old repository URL. GitHub
  redirects it, so this is tidiness rather than a break.

### README history section

A short section at the very end of the README, covering two points:

- The plugin has had two earlier names, `kmp-ssot` and then KiteSSOT.
- The version resets to 1.0.0 because the DSL surface has reached a stable form
  that is not planned to change.

This is the only signpost available for anyone who finds the abandoned
`kitessot` plugin on the portal, since published portal versions cannot be
edited.

## Part B: the read-back API

### The interface

`KiteConfigValues` is a public interface whose members are all `Provider<T>`. It
has no setters and no mutable properties.

`KiteConfigExtension` implements it. There is no wrapper object and no duplicated
resolution logic: the interface is a narrower view of the same instance.

### The accessor

```kotlin
import io.github.yuroyami.kiteconfig.kiteConfig

android {
    defaultConfig {
        versionCode = kiteConfig.versionCode.get()
    }
}
```

`val Project.kiteConfig: KiteConfigValues` resolves the root extension and
returns it typed as the interface, so a consumer holding it cannot write to the
model. When the plugin is not applied to the root project, it throws with a
message naming the fix.

### Why the accessor name does not collide with the DSL block

The block `kiteConfig { }` is a generated function taking an `Action`. The
accessor is a property. They have different signatures and do not conflict.

In the root project, where the plugin is applied, Gradle generates
`val Project.kiteConfig: KiteConfigExtension`. Our accessor has the same name and
receiver but a different return type. An explicit import wins over the implicit
generated one, so the accessor resolves. Reads work either way, because
`KiteConfigExtension` implements `KiteConfigValues`.

The one sharp edge: in the root, an explicit import shadows the writable
extension, so `kiteConfig.version.set(...)` outside the block would not compile.
Writes belong inside the block, so this is acceptable.

### The 18 values

All are `Provider<T>`.

#### Version

| Member | Type | Source |
| --- | --- | --- |
| `version` | `Provider<String>` | root `version` |
| `versionCode` | `Provider<Int>` | `effectiveAndroidVersionCode` |
| `iosBuildNumber` | `Provider<String>` | `effectiveIosBuildNumber` |
| `iosMarketingVersion` | `Provider<String>` | `effectiveIosMarketingVersion` |
| `desktopBuildNumber` | `Provider<String>` | `effectiveDesktopBuildNumber` |

#### Identity

| Member | Type | Source |
| --- | --- | --- |
| `appName` | `Provider<String>` | root `appName` |
| `appNameFor(platform)` | `Provider<String>` | `effectiveAppNameFor` |
| `id` | `Provider<String>` | root `id` |
| `androidApplicationId` | `Provider<String>` | already public |
| `iosBundleId` | `Provider<String>` | already public |
| `desktopBundleId` | `Provider<String>` | already public |

#### Build

| Member | Type | Source |
| --- | --- | --- |
| `canonicalLocales` | `Provider<List<String>>` | already public |
| `jvmTarget` | `Provider<Int>` | root `jvmTarget` |
| `resolvedSharedProjectPath` | `Provider<String>` | already public |
| `minSdk` | `Provider<Int>` | `android.minSdk` |
| `targetSdk` | `Provider<Int>` | `android.targetSdk` |
| `compileSdk` | `Provider<Int>` | `android.compileSdk` |
| `ndk` | `Provider<Int>` | `android.ndk` |

`appNameFor` takes the existing public `KitePlatform` enum.

### The unset contract

This is the core rule, and it is uniform across all 18 values.

**The accessors supply no defaults and return no nulls.**

The root DSL has no conventions. `version`, `appName`, `id`, and `jvmTarget` are
bare `Property` declarations, and the SDK levels are optional by design. So any
of the 18 can be unconfigured. The diagnostics engine already describes this as
"identity values are optional until configured".

`Provider<T>` gives the right contract with no extra machinery:

```kotlin
kiteConfig.version.get()   // configured   -> the value
                           // unconfigured -> throws, the build stops
```

Reading a value the root never declared is a mistake in the consuming build file.
It fails loudly and names the missing property. The caller is responsible for
knowing the value is set.

Documentation must not present `.orNull` as the normal way to read these. A
caller who writes `kiteConfig.minSdk.orNull ?: 24` has created a second source of
truth for `24` in the consumer, which is the problem this feature exists to
remove.

### Rejected alternative: branded failure messages

Each value could be wrapped so an unconfigured read throws a KiteConfig-worded
error naming the exact DSL line to add. The error text would be better.

This is rejected because it breaks provider composition. An absent provider today
propagates absence correctly when wired into another task's optional property. A
throwing wrapper would fail there instead of staying absent, which trades away
the main reason to return providers at all.

Gradle's own message already names the source, for example: "Cannot query the
value of extension 'kiteConfig' property 'version' because it has no value
available."

### Removing the old accessor

`Project.kiteSsot` and its file are deleted outright. Nothing replaces it beyond
the new accessor.

Its only real job was reaching raw DSL inputs from a submodule, and the Android
SDK levels were the only inputs that mattered. Its own KDoc used `minSdk` as the
headline example. Folding those four into the 18 removes that job.

Everything else it did was already dead:

- **Writing.** `finalizeModel` and `disallowChanges` lock the model after root
  evaluation, so a submodule write fails with a confusing error.
- **Root access.** `extensions.getByType<...>()` already works from the root with
  no deprecation, so the accessor only ever mattered in submodules.

No test uses it, so removal is clean.

### Why cross-project reads are safe

Two reasons, and one honest limitation.

1. `finalizeModel` freezes every validated DSL input before subprojects can
   observe it. A submodule cannot read a half-configured value.
2. Every member is a `Provider`, so nothing is computed at access time.
   Realization happens at execution, long after the freeze.

**Limitation.** This is still cross-project access, so it is not compatible with
Gradle Isolated Projects. That is not a regression: the plugin already configures
subprojects through `allprojects` and does not support Isolated Projects today.
The docs should say this plainly rather than imply the accessor is isolated safe.

## Part C: KiteBuildConfig

Two convention changes in the plugin's `buildConfig` extension setup:

| Convention | Old | New |
| --- | --- | --- |
| `className` | `BuildConfig` | `KiteBuildConfig` |
| `packageName` | `kitessot.generated` | `kiteconfig.generated` |

So the generated class becomes `kiteconfig.generated.KiteBuildConfig`.

The class name change removes an ambiguity: AGP generates its own
`com.yourapp.BuildConfig`, so two classes shared the simple name `BuildConfig`
and an import could silently pick the wrong one.

Both remain overridable through `className` and `packageName`.

This is the one part of the rename that reaches consumer Kotlin **source** rather
than build files, because the generated class is imported by application code.

## Files

**New**

- `KiteConfigValues.kt`: the interface and the `Project.kiteConfig` accessor.

**Changed**

- `KiteConfigExtension.kt`: implement `KiteConfigValues`. The 18 members break
  down as six existing public providers becoming overrides, four existing public
  `Property` declarations becoming covariant `Provider` overrides (`version`,
  `appName`, `id`, `jvmTarget`), four new members wrapping `internal effective*`
  providers (`iosBuildNumber`, `iosMarketingVersion`, `desktopBuildNumber`,
  `appNameFor`), and four new members delegating to the nested Android extension
  (`minSdk`, `targetSdk`, `compileSdk`, `ndk`). That is eight new public members.
  The `internal effective*` names stay so the engine keeps its call sites.
- `KiteConfigPlugin.kt`: the two `buildConfig` conventions.
- Every other source, test, doc, and workflow file touched by the rename mapping.
- `README.md`: promote read-back out of the DSL listing into its own section,
  document all 18 and the unset contract, and add the history section at the end.
- `docs/index.md`: the same read-back section.
- `CHANGELOG.md`: the 1.0.0 entry.

**Deleted**

- `KiteSsotAccess.kt`.
- `api/kitessot.api`, replaced by `api/kiteconfig.api`.

## Commit strategy

Two commit series inside the one release:

1. The mechanical rename, including `KiteBuildConfig`.
2. The read-back API on top.

Same single release and version number. This keeps the API diff readable instead
of burying it in a few thousand renamed lines.

## Testing

- A functional test where a **submodule** build file reads
  `kiteConfig.versionCode` and asserts the resolved value.
- A functional test asserting no deprecation warning appears in that output.
- A functional test where an unconfigured value fails the build on `.get()`
  instead of resolving to null or a default.
- Per-value coverage: each of the 18 resolves to its expected value.
- A test that the accessor throws a clear error when the plugin is missing from
  the root project.
- A test asserting every `KiteConfigValues` member returns `Provider` and none
  returns `Property`, so the read-only boundary cannot rot.
- A test that the generated class is `kiteconfig.generated.KiteBuildConfig`.
- A rename sweep check: no `kitessot`, `kiteSsot`, `KiteSsot`, or `KMPS` string
  survives anywhere in the repository except the README history section and the
  CHANGELOG.

`KdocExampleCompilationTest` already compiles KDoc samples, so the new samples
are covered by it.

ABI validation is enabled, so `updateKotlinAbi` must run and the regenerated dump
must be committed or `checkKotlinAbi` fails in CI.

## Release

Version 1.0.0 under the new coordinate `io.github.yuroyami:kiteconfig`.

The release guard requires a `## 1.0.0` heading in `CHANGELOG.md` matching an
exact `v1.0.0` tag. The publish workflow's version variable and jar filename
checks must be renamed first or the release fails.

## Out of scope

- **Engine plumbing values.** Roughly 45 further `internal effective*` providers
  cover splash art, logo art, pbxproj and plist paths, `dryRun` and `backups`,
  plist conflict policy, opt-in markers, and io worker settings. These describe
  how the engine runs, not what the app is, and stay internal so they do not
  become a permanent contract.
- **Isolated Projects support.** Unsupported plugin wide.
- **Renumbering diagnostic codes.** Only the prefix changes.
