# Read-back API design

Date: 2026-08-31
Target release: 5.0.0

## Problem

KiteSSOT consumes configuration but hands very little of it back. A consumer who
wants the resolved `versionCode` in another build file has to declare the number
a second time, which is the exact duplication the plugin exists to remove.

The plugin is a single source of truth for the platforms it writes to. It is not
yet a single source of truth for the build files themselves.

### What already works

`KiteSsotExtension` has six public read-back providers: `androidApplicationId`,
`iosBundleId`, `desktopBundleId`, `versionCode`, `canonicalLocales`, and
`resolvedSharedProjectPath`. From the root build file they are reachable with
`extensions.getByType<KiteSsotExtension>()`. This is documented in one short
paragraph in the README, below a long DSL listing.

### What is broken

1. **No convenient accessor.** Reaching the extension needs
   `extensions.getByType<...>()` with the fully qualified type. Nothing signals
   that read-back exists.
2. **The submodule path is deprecated.** `Project.kiteSsot` in
   `KiteSsotAccess.kt` is the only cross-project accessor, and it warns on every
   use. Reads through it are actually safe, so the warning is misleading.
3. **Coverage holes.** `versionCode` is public for Android only. The iOS build
   number, iOS marketing version, desktop build number, resolved per-platform app
   name, and the four Android SDK levels are all `internal`.
4. **It returns the mutable model.** The accessor hands back the whole DSL
   extension, so a consumer can attempt a write and hit a confusing late-mutation
   failure instead of getting a read-only view.

## Solution

A read-only view interface, reachable from any project through one import.

### The interface

`KiteSsotValues` is a public interface whose members are all `Provider<T>`. It
has no setters and no mutable properties.

`KiteSsotExtension` implements it. There is no wrapper object and no duplicated
resolution logic: the interface is a narrower view of the same instance.

### The accessor

```kotlin
import io.github.yuroyami.kitessot.kite

android {
    defaultConfig {
        versionCode = kite.versionCode.get()
    }
}
```

`val Project.kite: KiteSsotValues` resolves the root extension and returns it
typed as the interface, so a consumer holding it cannot write to the model.

When the plugin is not applied to the root project, the accessor throws with a
message naming the fix.

### Why the accessor uses `kite` and not `kiteSsot`

`kite` is short, and it does not change if the plugin is renamed later. Task
names in the plugin already use the `kite` prefix, so it matches what users
already type.

## The 18 values

All are `Provider<T>`.

### Version

| Member | Type | Source |
| --- | --- | --- |
| `version` | `Provider<String>` | root `version` |
| `versionCode` | `Provider<Int>` | `effectiveAndroidVersionCode` |
| `iosBuildNumber` | `Provider<String>` | `effectiveIosBuildNumber` |
| `iosMarketingVersion` | `Provider<String>` | `effectiveIosMarketingVersion` |
| `desktopBuildNumber` | `Provider<String>` | `effectiveDesktopBuildNumber` |

### Identity

| Member | Type | Source |
| --- | --- | --- |
| `appName` | `Provider<String>` | root `appName` |
| `appNameFor(platform)` | `Provider<String>` | `effectiveAppNameFor` |
| `id` | `Provider<String>` | root `id` |
| `androidApplicationId` | `Provider<String>` | already public |
| `iosBundleId` | `Provider<String>` | already public |
| `desktopBundleId` | `Provider<String>` | already public |

### Build

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

## The unset contract

This is the core rule, and it is uniform across all 18 values.

**The accessors supply no defaults and return no nulls.**

The root DSL has no conventions. `version`, `appName`, `id`, and `jvmTarget` are
bare `Property` declarations, and the SDK levels are optional by design. So any
of the 18 can be unconfigured. The diagnostics engine already describes this as
"identity values are optional until configured" (KMPS050).

`Provider<T>` gives the right contract with no extra machinery:

```kotlin
kite.version.get()   // configured   -> the value
                     // unconfigured -> throws, the build stops
```

Reading a value the root never declared is a mistake in the consuming build file.
It fails loudly and names the missing property. The caller is responsible for
knowing the value is set.

Documentation must not present `.orNull` as the normal way to read these. A
caller who writes `kite.minSdk.orNull ?: 24` has created a second source of truth
for `24` in the consumer, which is the problem this whole feature exists to
remove.

### Rejected alternative: branded failure messages

Each value could be wrapped so an unconfigured read throws a KiteSSOT-worded
error naming the exact DSL line to add. The error text would be better.

This is rejected because it breaks provider composition. An absent provider today
propagates absence correctly when wired into another task's optional property. A
throwing wrapper would fail there instead of staying absent, which trades away
the main reason to return providers at all.

Gradle's own message already names the source, for example: "Cannot query the
value of extension 'kiteSsot' property 'version' because it has no value
available."

## Removing `Project.kiteSsot`

The deprecated accessor is deleted and `KiteSsotAccess.kt` is removed.

Its only real job was reaching raw DSL inputs from a submodule, and the Android
SDK levels were the only inputs that mattered. Its own KDoc used `minSdk` as the
headline example. Folding those four into the 18 removes that job.

Everything else it did was already dead:

- **Writing.** `finalizeModel` and `disallowChanges` lock the model after root
  evaluation, so a submodule write fails with a confusing error. The mutability
  was a trap, not a feature.
- **Root access.** `extensions.getByType<KiteSsotExtension>()` already works from
  the root with no deprecation, so the accessor only ever mattered in submodules.

No test uses it, so removal is clean.

## Why cross-project reads are safe

Two reasons, and one honest limitation.

1. `finalizeModel` freezes every validated DSL input before subprojects can
   observe it (`KiteSsotPlugin.kt`). A submodule cannot read a half-configured
   value.
2. Every member is a `Provider`, so nothing is computed at access time.
   Realization happens at execution, long after the freeze.

**Limitation.** This is still cross-project access, so it is not compatible with
Gradle Isolated Projects. That is not a regression: the plugin already configures
subprojects through `allprojects` and does not support Isolated Projects today.
The docs should say this plainly rather than imply the new accessor is isolated
safe.

## Files

**New**

- `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotValues.kt`: the interface
  and the `Project.kite` accessor.

**Changed**

- `KiteSsotExtension.kt`: implement `KiteSsotValues`. The 18 members break down
  as six existing public providers becoming overrides, four existing public
  `Property` declarations becoming covariant `Provider` overrides (`version`,
  `appName`, `id`, `jvmTarget`), four new members wrapping `internal effective*`
  providers (`iosBuildNumber`, `iosMarketingVersion`, `desktopBuildNumber`,
  `appNameFor`), and four new members delegating to the already public nested
  Android extension (`minSdk`, `targetSdk`, `compileSdk`, `ndk`). That is eight
  new public members in total. The `internal effective*` names stay so the engine
  keeps its current call sites.
- `README.md`: move read-back out of the DSL listing into its own section,
  document all 18 and the unset contract.
- `docs/index.md`: add the same section.
- `CHANGELOG.md`: the 5.0.0 entry.
- `api/kitessot.api`: regenerate.

**Deleted**

- `KiteSsotAccess.kt`.

## Testing

- A functional test where a **submodule** build file reads `kite.versionCode` and
  asserts the resolved value.
- A functional test asserting no deprecation warning appears in that build's
  output.
- A functional test where an unconfigured value fails the build on `.get()`
  instead of resolving to null or a default.
- Per-value coverage: each of the 18 resolves to its expected value.
- A test that the accessor throws a clear error when the plugin is missing from
  the root project.
- A test asserting every `KiteSsotValues` member returns `Provider` and none
  returns `Property`, so the read-only boundary cannot rot.
- KDoc samples must compile: `KdocExampleCompilationTest` already enforces this,
  so the new samples are covered by it.

ABI validation is enabled, so `updateKotlinAbi` must run and the regenerated
dump must be committed or `checkKotlinAbi` fails in CI.

## Release

Version 5.0.0. The removal of `Project.kiteSsot` is a breaking change to public
API, so it takes the major bump.

Everything else is additive.

## Out of scope

- **The KiteConfig rename.** Decided separately, as its own task, after this
  ships. The `kite` accessor name was chosen so a rename does not touch consumer
  call sites.
- **Engine plumbing values.** Roughly 45 further `internal effective*` providers
  cover splash art, logo art, pbxproj and plist paths, `dryRun` and `backups`,
  plist conflict policy, opt-in markers, and io worker settings. These describe
  how the engine runs, not what the app is, and are deliberately left internal so
  they do not become a permanent contract.
- **Isolated Projects support.** Out of scope here and unsupported plugin wide.
