# Design: the `desktop { }` block

Status: proposed, not implemented.
Target release: 3.1.0.
Reserved by [OVERHAUL.md](../OVERHAUL.md) section "Reserved growth axis".

This file lives outside `docs/` on purpose. `mkdocs.yml` sets `docs_dir: docs`,
so anything placed there is published to the site.

## 1. The problem

A Compose Multiplatform repo that also ships a desktop app records the same
four facts a fifth time, inside `compose.desktop.application.nativeDistributions`:

| Fact | Already declared in | Duplicated again as |
| --- | --- | --- |
| app name | `kiteSsot { appName }` | `nativeDistributions.packageName` |
| version | `kiteSsot { version }` | `nativeDistributions.packageVersion` |
| bundle ID | `kiteSsot { appId }` | `nativeDistributions.macOS.bundleID` |
| build number | `kiteSsot { }` scheme | `macOS.packageBuildVersion` |
| app icon | `kiteSsot { logo { } }` | three separate icon files |

`desktop { }` removes those copies. It is the same job the plugin already does
for Android and Apple, pointed at a third consumer.

## 2. Verified facts about the Compose Gradle plugin

Everything below was read from the real classes in
`org.jetbrains.compose:compose-gradle-plugin:1.12.0-rc01`. These facts drive
the design, so they are recorded here rather than assumed.

### 2.1 Most identity fields are plain `var`, not `Property`

`AbstractDistributions` declares `packageName`, `packageVersion`, `copyright`,
`description` and `vendor` as plain nullable `String` fields with ordinary
getters and setters. `AbstractMacOSPlatformSettings` does the same for
`bundleID` and `packageBuildVersion`.

Consequence: KiteSSOT cannot hand Compose a lazy provider for identity. It has
to write a resolved value, at the right moment.

The one exception is `iconFile`, on `AbstractPlatformSettings`, which is a real
`RegularFileProperty`. That single fact is what makes the icon design clean.

### 2.2 Compose configures desktop inside its own `afterEvaluate`

`ComposePlugin.apply()` registers `project.afterEvaluate { }`, and that callback
calls `configureDesktop`. So the callback is registered when the Compose plugin
is applied to a module.

Gradle runs `afterEvaluate` callbacks in registration order. KiteSSOT therefore
has to register its own callback on that module first. Section 6 covers how.

### 2.3 Touching `application` has a side effect

`DesktopExtension` holds `application` and `nativeApplication` behind `Lazy`
delegates, and exposes `_isJvmApplicationInitialized` and
`_isNativeApplicationInitialized`. `configureDesktop` reads those flags to
decide what to configure.

Consequence: if KiteSSOT calls `getApplication()` on a module that never asked
for a desktop app, it initializes the delegate and makes Compose register
packaging tasks that the user never wanted. KiteSSOT must read the
initialization flag before it touches the application.

### 2.4 The native application is macOS only

`NativeApplicationDistributions` exposes only a `macOS` block. There is no
`windows` and no `linux`. `NativeApplicationMacOSPlatformSettings` adds nothing
of its own over `AbstractMacOSPlatformSettings`.

Both application models share the same two base classes:

```
AbstractDistributions            <- JvmApplicationDistributions, NativeApplicationDistributions
AbstractMacOSPlatformSettings    <- JvmMacOSPlatformSettings, NativeApplicationMacOSPlatformSettings
```

Consequence: one writer, typed against the two abstract base classes, covers
both application models. Windows and Linux writes stay on the JVM path only.

### 2.5 Compose validates versions, and Windows is strict

Read from the validator classes:

| Format | Rule |
| --- | --- |
| macOS, `Dmg` and `Pkg` | `MAJOR[.MINOR][.PATCH]`, each a non-negative integer |
| Windows, `Msi` and `Exe` | `MAJOR.MINOR.BUILD`, max 255, max 255, max 65535 |
| Debian | `([0-9]+:)?[0-9][0-9a-zA-Z.+\-~]*(-[0-9a-zA-Z.+~]+)?` |
| RPM | must not contain a dash |
| `macOS.bundleID` | `[A-Za-z0-9\-\.]+` only |

Two of these matter a lot:

- **Windows caps each component.** A `version` of `1.300.0` is fine on Play and
  fine on the App Store, and it fails an MSI build.
- **The macOS bundle ID has a narrower charset than an Android application ID.**
  An `appId` containing an underscore is legal for Android and rejected here.

`TargetFormat` values are `AppImage`, `Deb`, `Rpm`, `Dmg`, `Pkg`, `Exe`, `Msi`.

## 3. Scope

In scope:

- Identity propagation into both `compose.desktop.application` and
  `compose.desktop.nativeApplication`.
- Generated desktop icons, written into `build/`.
- An opt-in derived Windows `upgradeUuid`.
- Diagnostics, verify output, compatibility gating, tests, docs.

Out of scope, deliberately:

- `vendor`, `description` and `copyright`. KiteSSOT propagates facts that are
  already declared somewhere else. These three are declared once, in Compose,
  and nowhere else. Adding them to the DSL would create the second copy that
  this plugin exists to prevent.
- Signing, notarization, entitlements, provisioning profiles. Those are secrets
  and machine setup, not shared identity.
- `targetFormats`. KiteSSOT reads them to validate, and never writes them.
- Conveyor, or the plain `application` plugin with hand-run jpackage.

## 4. The DSL

Two additions. Selection goes in `modules { }`, to match `androidApps(...)`.
Everything platform-specific goes in `desktop { }`, to match `android { }` and
`ios { }`.

```kotlin
kiteSsot {
    modules {
        // default: the sole module that configures a Compose desktop application.
        // Several such modules require an explicit call, the same rule androidApps uses.
        desktopApps(":desktopApp")
    }

    // ---- Desktop-only: identity suffix, build number, icons, Windows upgrade code ----
    desktop {
        enabled = true                 // false always wins, even over a configured block

        idSuffix = ".desktop"          // default: empty, so the macOS bundle ID equals appId
        // buildNumber = "42"          // default: the root scheme, rendered as text
        rebuild = 2                    // default: 0. Bump to re-cut a build of the same version
        // scheme { v -> ... }         // rare: override the root scheme for desktop alone
        // publishedBuildNumber = "1001004000"  // release guard, compared componentwise

        icons = true                   // default: true when logo { } is also configured
        roundMacOsIcon = true          // default: true. macOS expects a rounded icon
        // linuxPackageName = "jetzy"  // default: a slug derived from appName
        deriveUpgradeUuid = true       // default: false. See section 9
    }
}
```

`desktop { }` is a feature block, so configuring it is the opt-in. It follows
the `configured && enabled` shape used by `web { ioWorker { } }` and
`buildConfig { }`, since it has no 2.x predecessor to bridge.

### Why these names

- `idSuffix` matches `android { idSuffix }`, not `ios { bundleIdSuffix }`. The
  desktop value extends the same reverse-DNS base.
- `buildNumber`, `rebuild`, `scheme` and `publishedBuildNumber` are the exact
  names `ios { }` already uses, and they mean the same thing.
- `icons` is a plain switch, not a nested block, because it has no settings of
  its own. Its inputs come from `logo { }`.

## 5. What gets written

| KiteSSOT truth | Compose target | JVM app | Native app |
| --- | --- | --- | --- |
| `appName` | `nativeDistributions.packageName` | yes | yes |
| `appName`, slugged | `linux.packageName` | yes | no |
| `version` | `nativeDistributions.packageVersion` | yes | yes |
| `appId` + `desktop.idSuffix` | `macOS.bundleID` | yes | yes |
| resolved build number | `macOS.packageBuildVersion` | yes | yes |
| generated `.icns` | `macOS.iconFile` | yes | yes |
| generated `.ico` | `windows.iconFile` | yes | no |
| generated `.png` | `linux.iconFile` | yes | no |
| UUIDv5 of `appId` | `windows.upgradeUuid` | opt-in | no |

Each write is gated by the existing `propagate { }` switches:

| Write | Gate |
| --- | --- |
| `packageName`, `linux.packageName` | `propagate { appName }` |
| `macOS.bundleID`, `windows.upgradeUuid` | `propagate { bundleId }` |
| `packageVersion`, `macOS.packageBuildVersion` | `propagate { version }` |
| icon files | `logo { }` configured, and `desktop { icons }` |

`propagate { locales }` is not honoured. Compose has no locale field of its own.
The nearest thing is `macOS.infoPlist { extraKeysRawXml }`, and that is applied
at packaging time, so it would not be a source edit. It is still the wrong place
to write: it is one raw XML string the user may already own, and merging
fragments into a string the user controls is fragile. This could become an
opt-in later. It is not in 3.1.0.

### Who wins when both sides declare a value

Two rules apply, and the split matters.

**The SSOT replaces core identity.** `packageName`, `packageVersion`,
`macOS.bundleID` and `macOS.packageBuildVersion` are the facts this plugin
exists to own. When a module also sets one, KiteSSOT overwrites it and records
the change through `SsotDriftLog`, which prints one warning per project naming
what was replaced. This is what the Android side already does.

**The module wins on per-format and derived extras.** These are not shared
truths, so an explicit value is always kept:

- `linux.packageName`
- `windows.upgradeUuid`
- the per-format version overrides, which are separate fields from the root
  `packageVersion`: `msiPackageVersion`, `exePackageVersion`,
  `dmgPackageVersion`, `pkgPackageVersion`, `debPackageVersion`,
  `rpmPackageVersion`, and the matching `*PackageBuildVersion` fields

`kiteSsotDoctor` prints which side supplied each value.

## 6. The wiring hook

There is no `finalizeDsl` for Compose. The closest safe hook is an
`afterEvaluate` registered before the Compose plugin registers its own.

### How KiteSSOT wins the ordering race

KiteSSOT is applied to the root project, and the root project is evaluated
before any subproject. So the callback that `allprojects { }` registers on a
subproject is always registered before that subproject applies Compose.

```kotlin
// registered during root evaluation, so it runs before Compose's own callback
target.allprojects {
    val consumerProject = this
    consumerProject.afterEvaluate {
        if (!composeAdaptersUsable) return@afterEvaluate
        DesktopWiring.write(consumerProject, ext, resilient)
    }
}
```

The registration must NOT sit inside `plugins.withId("org.jetbrains.compose")`.
Gradle fires `withId` callbacks after the plugin's `apply()` returns, which is
after Compose has already registered its own `afterEvaluate`. That would put
KiteSSOT second, and every write would be too late.

### The root project edge case

If a user applies Compose to the root project itself and builds a desktop app
there, ordering depends on the order of the root `plugins { }` block. KiteSSOT
detects that case and fails with a clear message rather than writing values
that may or may not land.

The documented layout avoids it entirely. Compose is declared at the root with
`apply false`, exactly like Kotlin and AGP:

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.12.0" apply false
    id("io.github.yuroyami.kitessot") version "3.1.0"
}
```

The root declaration is required for the same reason the README already gives
for Kotlin and AGP: KiteSSOT reads typed Compose classes from its own
classloader, and that only works when the plugin is declared at the root.

### Class isolation

All Compose-typed code lives in its own file, `DesktopWiring.kt`, following the
rule stated in `ClassicAndroidWiring.kt`. Compose types appear in method
descriptors, so putting them on `KiteSsotPlugin` would break Gradle's
decoration of the plugin whenever Compose is absent.

Failures go through `wireValueGroup(resilient, "desktop identity") { }`, so a
real build fails loudly and `kiteSsotDoctor` still runs.

The wiring is guarded on both `COMPOSE_ON_CLASSPATH` and `KGP_ON_CLASSPATH`.
Compose's own DSL signatures mention `KotlinTarget` and `KotlinNativeTarget`, so
Compose being visible is not on its own enough to make those calls safe.

## 7. Detection and selection

Detection follows `androidApps` exactly.

1. During `allprojects`, `plugins.withId("org.jetbrains.compose")` records the
   project path in `detectedComposeProjects`.
2. A project counts as a **desktop app** only if, at `afterEvaluate` time, its
   `DesktopExtension` reports `_isJvmApplicationInitialized` or
   `_isNativeApplicationInitialized` as true. Merely applying the Compose plugin
   is not enough, because every Compose UI module applies it.
3. `modules { desktopApps(...) }` overrides detection and is additive.
4. Zero detected apps and no selector: the feature reports SKIPPED and writes
   nothing.
5. Several detected apps and no selector: hard failure naming every candidate
   and the one line that fixes it, matching the `KMPS070` wording.

Timing splits in two, the same way `androidApps` already splits. Identity writes
happen per project, in that project's `afterEvaluate`. The census-wide checks,
meaning "several candidates and no selector" and "a selected path is not a
desktop app", need the whole project census, so they run in
`gradle.projectsEvaluated`, after the resilient-diagnostic early return.

The initialization flags are Kotlin `internal`, so they are read reflectively
through their JVM names, `get_isJvmApplicationInitialized$compose` and
`get_isNativeApplicationInitialized$compose`. If reflection fails, KiteSSOT
treats the module as not a desktop app and records a WARNING, because the
alternative is to initialize the delegate and create packaging tasks the user
never asked for.

## 8. Icons

### Where they go

Into `build/`, never into the source tree. `iconFile` is a
`RegularFileProperty`, so Compose can read a generated path.

This puts desktop icons in the automatic tier alongside `buildConfig { }` and
`web { ioWorker { } }`. No ownership manifest for source files, no backups, no
rollback, no `dryRun`, and no opt-in task to run by hand. An ordinary
`./gradlew packageDmg` produces a correct icon with nothing else to remember.

### The task

`generateKiteSsotDesktopIcons`, registered on each selected desktop app project,
following `GenerateIoWorkerTask`:

| Aspect | Value |
| --- | --- |
| output | `build/generated/kitessot/desktop-icons/` |
| files | `app.icns`, `app.ico`, `app.png` |
| owner | `desktop-icons` |
| writer | `OwnedOutputSafety.replaceGeneratedTree` |
| `dryRun` | `@get:Internal`, ignored, logged at info, same as the other generators |
| caching | `@CacheableTask` |

Wiring uses providers so Gradle infers the task dependency for every packaging
task:

```kotlin
macOS.iconFile.set(iconTask.flatMap { it.outputDir.file("app.icns") })
windows.iconFile.set(iconTask.flatMap { it.outputDir.file("app.ico") })
linux.iconFile.set(iconTask.flatMap { it.outputDir.file("app.png") })
```

### What gets drawn

Inputs are the existing `logo { foreground }` plus one of `background` or
`backgroundColor`. The same composite the Apple icon uses, foreground contained
over a covered background, flattened to opaque.

`desktop { icons }` has no inputs of its own. If it resolves to true while
`logo { }` has no complete plan, the build fails at configuration time naming
both blocks, the same gate `takeOverLegacyIcons` already uses. Leaving
`logo { }` unconfigured turns desktop icons off quietly. The failure is only for
an explicit `icons = true`.

| File | Contents |
| --- | --- |
| `app.icns` | `ic07` through `ic14`: 128, 256, 512 and 1024, plus the retina entries that also cover 32 and 64 pixels |
| `app.ico` | PNG entries at 16, 24, 32, 48, 64, 128, 256 |
| `app.png` | one 512 by 512 PNG |

The ICNS set deliberately skips the older `icp4` and `icp5` types. They nominally
accept PNG payloads at 16 and 32 pixels, and support for that is inconsistent
across readers. `ic11` and `ic12` cover the same pixel sizes as retina entries
and are reliable. Step 2 of section 16 verifies the produced file on real macOS.

macOS icons are rounded by default, because a square icon looks wrong in the
Dock next to every other app. Windows and Linux icons stay square, which is
correct for those platforms. `roundMacOsIcon = false` turns the rounding off.

### New image code

The repo can already decode PNG safely, resize, composite, mask a circle, and
write PNG bytes. Two things are new, and one is a cleanup:

| Work | Kind |
| --- | --- |
| ICNS container writer | new. Magic, total length, then `<OSType><length><PNG>` entries |
| ICO container writer | new. `ICONDIR`, then 16 byte `ICONDIRENTRY` records with little-endian offsets, then PNG payloads |
| rounded rectangle mask | new. Sibling of the existing `applyCircleMask` |
| lift `encodePng` into `ImageOps` | cleanup. It is currently copy-pasted in two tasks |
| lift `padToSafeZone` into `ImageOps` | cleanup. It is private inside the Android task and a third copy would be worse |

Both containers hold PNG payloads, so every sub-image comes from primitives
that already exist. Neither writer needs a new dependency.

## 9. Windows `upgradeUuid`

Windows keys MSI upgrades on `upgradeUuid`. When it is unset, jpackage derives
one from the app name. Renaming the app therefore breaks the upgrade path for
everyone who already installed the old build, silently.

`desktop { deriveUpgradeUuid = true }` replaces that with a UUIDv5 computed
from `appId`, which does not change when the display name does.

Rules:

- Off by default. Turning it on for a shipped app changes its upgrade code once,
  which is exactly the breakage this feature prevents in the future. The
  changelog and the KDoc both say so plainly.
- KiteSSOT never overwrites a value the user set.
- `kiteSsotVerify` and `kiteSsotDoctor` always print the resolved UUID, whether
  derived or not, so it can be checked before a release.
- The value is a pure function of `appId`. No randomness, no timestamp, so it
  is stable across machines and across the configuration cache.
- The UUIDv5 namespace is a fixed constant, written in the source and repeated
  in `FEATURES.md`. Changing it later would change every derived upgrade code
  ever produced, so it is a published contract, not an implementation detail.

This one carries real-world risk that no unit test can cover, so it ships
documented as unverified against a real MSI upgrade, in the same honest tone
the README "Limits" section already uses.

## 10. Versions

### Mapping

| Number | Goes to | Why |
| --- | --- | --- |
| `version`, for example `1.4.0` | `packageVersion` | marketing version, every format reads it |
| resolved build number, for example `1001004000` | `macOS.packageBuildVersion` | this is `CFBundleVersion`, the same field family as iOS |

Windows and Linux have no separate build-number field, so the scheme result
reaches macOS only. That is a property of jpackage, not a KiteSSOT choice, and
`kiteSsotDoctor` states it so nobody hunts for a missing write.

The precedence gears match `ios { }` exactly: root scheme, then `rebuild`, then
root `scheme { }`, then `desktop { scheme { } }`, then an explicit
`desktop { buildNumber }`, then `propagate { version = false }` which writes
nothing.

`VersionResolution.computeVersionCode` currently picks its error text from a
platform string, and its `else` branch names `android { versionCode }`. A
`"desktop"` branch must be added, otherwise a desktop failure tells the user to
edit the Android block.

### The check that actually matters

KiteSSOT validates `version` against the enabled `targetFormats` before Compose
sees it, and fails with a message naming the KiteSSOT property.

Since `version` is always strict `x.y.z`, the Debian, RPM and macOS rules can
never fail. **Windows is the only real failure mode.** If `Msi` or `Exe` is in
`targetFormats` and any component exceeds its cap, the build fails early:

```
kiteSsot { version } is 1.300.0, which Windows installers reject.
MSI and EXE accept MAJOR.MINOR.BUILD with limits 255, 255 and 65535.
Either lower the component, or drop Msi and Exe from targetFormats.
```

The bundle ID gets the same treatment. `appId` may legally contain an
underscore for Android and `macOS.bundleID` may not, so a resolved desktop
bundle ID is checked against `[A-Za-z0-9\-\.]+` and fails early with both
values printed.

### Linux package name

Debian package names must be lowercase and start with an alphanumeric. An
`appName` of `Jetzy` is not a valid one. KiteSSOT derives a slug, lowercasing
and replacing characters outside `[a-z0-9+.-]` with `-`, and writes it to
`linux.packageName` only when the user has not set one and `Deb` or `Rpm` is a
target format. The derived value is always printed by `kiteSsotVerify`, so it
is never a surprise.

If the slug still cannot be made valid, for example an `appName` that is all
punctuation, the build fails and asks for `desktop { linuxPackageName }`.

## 11. Compatibility gating

Mirrors the AGP and KGP arrangement.

| Piece | Detail |
| --- | --- |
| dependency | `compileOnly("org.jetbrains.compose:compose-gradle-plugin")` |
| version catalog | new `compose` version plus one library entry |
| classpath probe | `COMPOSE_ON_CLASSPATH`, via `Class.forName("org.jetbrains.compose.desktop.DesktopExtension", false, ...)` |
| runtime version | reflective probe, `Package.implementationVersion` first |
| supported range | `isSupportedComposeVersion`, in `PluginCompatibility.kt` |
| soft diagnostic | `KMPS082`, same shape as `KMPS061` |
| hard failure | `[KITESSOT-COMPAT-007]`, the next free code. 001 through 006 are taken |

One difference from AGP. The existing `STABLE_TOOL_VERSION` regex rejects any
suffix, and Compose ships long `-beta` and `-rc` lines that people really do
build on. The Compose parser therefore accepts the suffix forms and compares
only the numeric part, closer to `STABLE_KGP_RUNTIME_VERSION` than to the AGP
rule.

`gradle.lockfile` must be regenerated, since `dependencyLocking` locks all
configurations. `runtimeClasspath` must stay empty in that file, so the SBOM
claim of zero runtime dependencies stays true.

## 12. Diagnostics and reporting

New engine IDs, in the free `KMPS08x` family:

| ID | Subject |
| --- | --- |
| `KMPS080` | desktop identity propagation, and what was written |
| `KMPS081` | desktop icon generation state |
| `KMPS082` | Compose Gradle plugin compatibility |
| `KMPS083` | desktop application selection |
| `KMPS084` | macOS bundle ID legality |
| `KMPS085` | package version legality against the enabled target formats |
| `KMPS086` | derived Linux package name |
| `KMPS087` | Windows upgrade code, derived or user-set |

Provider-resolution IDs continue at `KMPS941` and up, one per new `resolve(...)`
call in `bindDiagnosticInputs`.

Task changes:

| Task | Change |
| --- | --- |
| `kiteSsotVerify` | a new `Desktop` section after `App logo` |
| `kiteSsotDoctor` | nothing. It picks up new findings automatically |
| `kiteSsotCheck` | nothing. New IDs become SARIF rules automatically |
| `kiteSsotPlan` | a `policies` entry and at most a `notes` line |

`kiteSsotPlan` lists source mutations. Desktop writes go to `build/`, so they
must not appear in `mutationPaths` or `operations`. The existing note that
generated source is build-owned already covers the wording.

`generateKiteSsotDesktopIcons` joins the README task table as a `build/` writer,
next to `generateKiteSsotBuildConfig` and `generateKiteSsotIoWorker<Target>`.
Like those two, it is listed for reference and nobody has to invoke it. It runs
as a dependency of the Compose packaging tasks.

## 13. Testing

Follows the existing conventions: JUnit 5, `@TempDir`, `GradleRunner` for
functional tests, no golden images.

| Area | Kind | What it proves |
| --- | --- | --- |
| ICNS writer | unit | parse the produced bytes back, assert magic, entry count, per-entry offsets and lengths |
| ICO writer | unit | same, plus that 256 is encoded as a `0` width byte |
| rounded mask | unit | corner pixels transparent, centre pixel opaque |
| version rules | unit | the Windows caps, the Debian and RPM shapes, the bundle ID charset |
| Linux slug | unit | `Jetzy` to `jetzy`, punctuation handling, the failure case |
| UUIDv5 | unit | stable for a given `appId`, different for a different one |
| compat range | unit | floor, ceiling, and the `-rc` suffix form |
| identity write | functional | a fixture with a Compose desktop app, assert the resolved values |
| both app models | functional | assert the native application gets bundle ID and versions too |
| selection failure | functional | two desktop apps, no selector, assert the message names both |
| no side effect | functional | a Compose UI module that is not an app gets no packaging tasks |
| icon dependency | functional | packaging depends on the icon task without an explicit `dependsOn` |
| the SSOT wins | functional | the module sets its own `packageName`, assert the KiteSSOT value lands and the drift warning names the replacement |
| icons without logo | functional | `icons = true` with no `logo { }`, assert the failure names both blocks |

The "the SSOT wins" test earns its place twice. It is the only test that proves
the `afterEvaluate` registration order from section 6 actually holds, and it
covers the drift rule from section 5 at the same time. If Compose ever changes
when it reads these fields, this is the test that goes red.

The icon dependency test cannot name `packageDmg`, because the per-format
packaging tasks only exist on a matching host. It asserts against
`packageDistributionForCurrentOS`, or inspects the task graph with `--dry-run`.
CI already runs macos-15, ubuntu-24.04 and windows-2025, so all three hosts are
covered.

The Compose plugin must be added to `testKitPluginClasspath`, the same way KGP
already is, or fixtures cannot apply it.

Two existing guards constrain new code:

- `KdocExampleCompilationTest` runs every `kiteSsot { }` KDoc fence through a
  real build. New examples may reference only `:shared` and `art/logo_fg.png`.
- `MessageHygieneTest` rejects retired 2.x property names inside string
  literals. New messages must avoid them.

## 14. Documentation and ABI

| File | Change |
| --- | --- |
| `README.md` | the `desktop { }` block after `buildConfig { }`, a row in the tier prose, a row in the task table, and the new root `apply false` line |
| `FEATURES.md` | a prose section, plus rows in the feature list, the manage table, the defaults table, the selection table, and limitations |
| `CHANGELOG.md` | a new `## [3.1.0]` with `### Added` |
| `api/kitessot.api` | regenerate with `./gradlew updateKotlinAbi` |
| `docs/`, `mkdocs.yml` | nothing. KDoc is the published API reference |

House style applies: no em-dashes, second person, short sentences, backtick
every identifier, and state what is rejected as its own paragraph.

## 15. Limits, stated up front

- One desktop identity per build. No per-build-type overlays, matching the
  existing one-identity rule.
- The scheme result reaches macOS only. Windows and Linux have no build-number
  field.
- Locales are not propagated. Compose has no field for them.
- `vendor`, `description` and `copyright` are not managed, on purpose.
- Signing and notarization are untouched.
- Reading the initialization flags uses reflection into Kotlin `internal`
  members. A future Compose release may rename them. `KMPS083` reports it as a
  WARNING and the feature degrades to explicit selection rather than guessing.
- Nobody has yet verified a real signed DMG, a real MSI upgrade across two
  versions, or a real Debian install driven by these outputs.

## 16. Suggested order of work

Each step is independently reviewable and leaves the build green.

1. `ImageOps` cleanup: lift `encodePng` and `padToSafeZone`, add the rounded
   mask. No behaviour change, existing tests must still pass.
2. ICNS and ICO writers, with their unit tests. Pure functions, no Gradle.
3. Validation helpers and the `"desktop"` branch in `computeVersionCode`, with
   unit tests. Still no Compose dependency.
4. The `compileOnly` dependency, the classpath probe, the compat range, the
   lockfile update.
5. `KiteSsotDesktopExtension`, `modules { desktopApps }`, the `effective*`
   providers, `modelValues()` registration, the ABI dump.
6. `DesktopWiring`, detection, and the identity writes. First functional test.
7. The icon generator task and its lazy wiring.
8. `deriveUpgradeUuid`.
9. Diagnostics, the verify section, the plan policy entry.
10. Documentation.

Steps 1 through 3 carry most of the new logic and none of the integration risk,
so they are the right place to start.
