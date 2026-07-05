# kmp-ssot

A Gradle plugin that gives a Kotlin Multiplatform project **one `kmpSsot { }`
block at the root** and propagates cross-platform identity (app name, version,
bundle ID, locales, app logo) — plus the Android SDK levels — to Android and iOS
automatically.

One source of truth, per-concern opt-out toggles, every identity field
optional.

> **Requirements:** Gradle 8.5+, JDK 17+ (toolchain 21), AGP 8+/9.x. Tested
> against AGP 9.2 and Kotlin 2.4. The root-applied, zero-per-module design means
> cross-project configuration is **not** compatible with Gradle's Isolated
> Projects feature; the configuration cache and normal builds are unaffected.

---

## Install

The plugin is on the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.yuroyami.kmpssot)
— no extra repository configuration needed. Apply at the **root project**
and declare once:

```kotlin
// <root>/build.gradle.kts
plugins {
    id("io.github.yuroyami.kmpssot") version "1.7.0"
    // ...your other plugins, typically with .apply(false)...
}

kmpSsot {
    appName      = "Jetzy"
    versionName  = "0.3.0"
    bundleIdBase = "com.yuroyami.jetzy"   // null by default — omit to freeze existing bundle IDs
    javaVersion  = 21                     // optional, NO default — set to control compileOptions

    // versionCode is derived from versionName ("1" + 3-digit segments). For a
    // non-numeric or 4+-segment version, set it explicitly:
    // versionCodeOverride = 42

    // Module structure.
    sharedModule     = "shared"       // REQUIRED — KMP shared module directory name
    androidAppModule = "androidApp"   // optional, default "androidApp"
    // oldSharedModuleName = "composeApp"  // optional — for the rename SSOT when auto-detect can't

    // Android SDK levels — all optional, propagated to every Android module.
    android {
        compileSdk = 36
        minSdk     = 26
        targetSdk  = 36               // application modules only (libraries have none)
        // ndkVersion = "27.0.12077973"
    }

    // Bundle-ID suffixes — both null by default. When unset, applicationId
    // and iOS bundle ID share bundleIdBase verbatim. Set them when you need
    // to differentiate (e.g. platform-specific App Store / Play Store IDs).
    // iosBundleSuffix            = ".ios"
    // androidApplicationIdSuffix = ".android"

    // Locales — auto-detected from ${sharedModule}/src/commonMain/composeResources/values-*.
    // Explicit list overrides:
    // locales = listOf("en", "ar", "fr")

    // App logo — FG always required, plus exactly one BG source. Design
    // naturally (fill the canvas, iOS-style); the plugin handles Android's
    // safe zone, with a tunable ratio if a tight launcher mask clips corners.
    // appLogoPngForeground        = file("art/logo_foreground.png")
    // appLogoPngBackground        = file("art/logo_background.png")
    // appLogoBackgroundColor      = "#FF5500"      // or "#FFFF5500" (AARRGGBB) — alternative to BG PNG
    // appLogoAndroidSafeZoneRatio = 66.0 / 108.0   // default; lower (e.g. 0.55) if corners clip

    // Toggles — all default true. Flip a single flag to opt out.
    // propagateAppName       = true
    // propagateBundleId      = true
    // propagateVersion       = true
    // propagateLocaleList    = true
    // propagateLogo          = true
    // propagateSharedModule  = true
    // propagateAndroidSdk    = true
    // propagateInteropOptIns = true   // add cinterop/Obj-C @OptIn markers to every native compilation
    // syncIos                = true
    // sanitizeIosProject     = true

    // Safety. dryRun previews edits (writes nothing); backupBeforeRewrite (default
    // true) copies a user-owned file to <file>.kmpssot.bak before its first edit.
    // dryRun              = true
    // backupBeforeRewrite = true

    // Migration aid (default false): removes orphan logo files from
    // pre-FG/BG plugin versions. See "Migrating older versions" below.
    // cleanupLegacyLogoArtifacts = true

    // Platform-specific Info.plist feature flags. Each is unset by default —
    // when set, the corresponding key is inserted (or overwritten) in
    // iosApp/iosApp/Info.plist by sanitizeIosProject. See "iOS feature flags".
    // ios {
    //     usesNonExemptEncryption = false   // kills the App Store "Missing Compliance" prompt
    //     proMotion120Hz          = true    // unlocks >60 Hz on ProMotion iPhones
    // }

    // Native interop opt-ins (default on): drop the per-call-site @OptIn noise.
    // extraOptIns.add("kotlin.experimental.ExperimentalObjCRefinement")

    // Web target gap-closer (default off): generate an inline Web Worker offload
    // helper (kmpSsotOffload) into the JS source set. JS target only for now.
    // web {
    //     generateIoWorker = true
    //     ioWorkerPackage  = "kmpssot.generated"   // default
    // }

    // Runtime build info (default off): generate a KmpSsotBuildInfo object into the
    // shared module's commonMain, so the app reads the same identity at runtime.
    // buildInfo {
    //     enabled     = true
    //     packageName = "com.acme.app"   // default: kmpssot.generated
    // }
}
```

**Every identity field is optional.** A field propagates iff (a) its
`propagate*` toggle is on AND (b) the value is set. This lets you drop the
plugin onto a live production app and centralize only the parts you want —
e.g. `versionName` + `locales` + `appName`, leaving `bundleIdBase` unset so
the already-registered Android `applicationId` and iOS
`PRODUCT_BUNDLE_IDENTIFIER` are never touched.

**Authority.** When a field *is* set in `kmpSsot { }`, the SSOT value is
authoritative: it overrides a module-local `applicationId` / `versionName` /
`compileSdk` for **every** Android module shape (`com.android.application`,
`com.android.library`, and the KMP-native `com.android.kotlin.multiplatform.library`),
via AGP's `finalizeDsl`. Leave a field unset to keep whatever the module declares
itself. (Before 1.7.0, module-local values won for the two classic plugins — this
is now consistent.)

### Scope

`kmpSsot { }` covers cross-platform identity, the shared toolchain, and — via
the optional `android { }` block — the Android SDK levels (`compileSdk`,
`minSdk`, `targetSdk`, `ndkVersion`). When you set those in `kmpSsot { android
{ } }` they are propagated to every Android module's `defaultConfig`, so you no
longer copy-paste them across modules:

```kotlin
kmpSsot {
    android {
        compileSdk = 36
        minSdk     = 26
        targetSdk  = 36   // application modules only
    }
}
```

Each value is optional — leave one unset to keep whatever a module declares
itself, or set `propagateAndroidSdk = false` to disable the block entirely.
Per-flavor / per-variant SDK overrides still belong in the module's own build
file (the block writes `defaultConfig` only).

This works across all three Android module shapes: `com.android.application`,
the classic `com.android.library`, and — since 1.5.0 — AGP's KMP-native
`com.android.kotlin.multiplatform.library` (the `kotlin { androidLibrary { } }`
target that is the standard shared module under AGP 9). For the KMP library only
`compileSdk`/`minSdk` apply — that DSL has no `targetSdk` or `ndkVersion` — and
the value is injected via `finalizeDsl`, so it wins over one set in the module.

### Native interop & web toolchain (1.6.0)

Two gap-closers that are **not** identity propagation — they remove KMP
toolchain boilerplate. Both are scoped to platform-specific trees only (native
compilations / a plugin-owned generated `jsMain` dir); neither touches shared
code.

**Native interop opt-ins** (`propagateInteropOptIns`, default on). Adds the
cinterop / Obj-C opt-in markers — `kotlinx.cinterop.ExperimentalForeignApi`,
`kotlin.experimental.ExperimentalObjCName`,
`kotlin.experimental.ExperimentalNativeApi` — to **every Kotlin/Native
compilation**, so cinterop and Obj-C call sites no longer each need an
`@OptIn`. Applied only to native targets, where the markers resolve. Add your
own:

```kotlin
kmpSsot {
    extraOptIns.add("kotlin.experimental.ExperimentalObjCRefinement")
    // propagateInteropOptIns = false   // opt out entirely
}
```

**Web Worker IO** (`web { generateIoWorker = true }`, default off). KMP has no
`Dispatchers.IO` on the web target — you can't block the single JS main thread.
This generates the proven inline Blob-Worker offload helper
(`suspend fun kmpSsotOffload(jobJs, payload): String`) into a plugin-owned
generated source dir wired onto the JS source set (never your hand-authored
tree). The generated code depends only on `kotlinx-coroutines-core`.

```kotlin
kmpSsot {
    web {
        generateIoWorker = true
        ioWorkerPackage  = "com.acme.app.generated"   // default: kmpssot.generated
    }
}
```

```kotlin
// in jsMain:
val sum = kmpSsotOffload("(n) => { let s=0; for(let i=0;i<+n;i++) s+=i; return ''+s }", "100000000")
```

**JS target only** in this release — a wasmJs-only module is logged and
skipped. For a richer typed worker (`KiteWorker`) plus a unified
`ioDispatcher()` across every target, see the runtime sibling
[`KiteCore`](https://github.com/yuroyami/KiteCore).

### Runtime build info (1.7.0)

Close the SSOT loop to *runtime*. The plugin already computes the identity for the
build config, so it can also emit it as a Kotlin object the app reads at runtime —
About screens, crash-reporter tags, analytics — with no `expect/actual BuildConfig`
boilerplate:

```kotlin
kmpSsot {
    buildInfo {
        enabled     = true
        packageName = "com.acme.app"   // default: kmpssot.generated
    }
}
```

`generateKmpSsotBuildInfo` writes a `KmpSsotBuildInfo` object into the shared
module's `commonMain` (a plugin-owned `build/generated/kmpssot/commonMain/kotlin`
dir wired onto the source set — never your hand-authored tree):

```kotlin
public object KmpSsotBuildInfo {
    public const val appName: String = "…"
    public const val versionName: String = "…"
    public const val versionCode: Int = …
    public const val androidApplicationId: String = "…"
    public const val iosBundleId: String = "…"
    public val locales: List<String> = listOf(…)
}
```

### Scope & boundaries

kmp-ssot's core job is **configuration propagation**: one identity, propagated to
Android and iOS. Three features are deliberately-scoped **toolchain gap-closers**
rather than identity propagation — they remove KMP boilerplate but touch only
platform-specific trees: `propagateInteropOptIns` (native compilations),
`web { generateIoWorker }` (a plugin-owned `jsMain` dir), and
`buildInfo` (a plugin-owned `commonMain` dir). None edits your shared code. The
web worker overlaps with the [`KiteCore`](https://github.com/yuroyami/KiteCore)
runtime library by design — use KiteCore if you want the richer typed variant.

### Two one-time consumer-side patches

**AndroidManifest.xml** — replace the hardcoded label with the placeholder:
```xml
<application
    android:label="${appName}"
    ... />
```

**iOS Info.plist** — use Xcode build-setting references:
```xml
<key>CFBundleShortVersionString</key>
<string>$(MARKETING_VERSION)</string>
<key>CFBundleVersion</key>
<string>$(CURRENT_PROJECT_VERSION)</string>
```

---

## App logo

Provide a foreground layer plus exactly one background source:

- `appLogoPngForeground` — square PNG with an alpha channel. The visible logo content. **Always required** when propagating a logo.
- Background — pick **exactly one**:
  - `appLogoPngBackground` — square PNG (effectively opaque). Colour or texture behind the foreground.
  - `appLogoBackgroundColor` — hex string `"#RRGGBB"` or `"#AARRGGBB"` (Android convention — alpha first). The plugin synthesizes a solid-colour image and feeds it through the same pipeline as a real BG PNG.

**Design naturally** — fill the canvas like an iOS App Store icon. The plugin
handles Android's adaptive-icon safe zone for you: when generating the
adaptive FG layer, the source FG is centred at `appLogoAndroidSafeZoneRatio`
of the 108dp canvas (default `66.0 / 108.0` ≈ 61.1%, matching Android's
adaptive-icon spec) with a transparent margin, so the launcher's mask and
parallax movement don't crop your content. iOS and the Android legacy
fallback render the source layers at native size — what you design is what
ships.

Lower `appLogoAndroidSafeZoneRatio` if your target launcher applies a
tighter mask than the inscribed circle (common on third-party launchers and
some OEM skins) and the FG corners still clip. Typical overrides land
around `0.55`–`0.6`.

Recommended source size: 1024×1024 (matches the iOS App Store icon). Minimum
useful size: 432×432 (xxxhdpi adaptive-icon foreground).

Validation runs at `afterEvaluate`: setting `appLogoPngForeground` requires
exactly one of `appLogoPngBackground` / `appLogoBackgroundColor`. Setting
both backgrounds — or either background without the foreground — fails the
build.

**Android (`syncAndroidLogo`)** generates a complete launcher-icon resource
tree under `${androidAppModule}/src/main/res/`:

| Output | Notes |
|---|---|
| `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png` | FG, auto-padded to `appLogoAndroidSafeZoneRatio` (default 66/108) |
| `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_background.png` | BG (PNG or solid colour), fills the 108×scale canvas |
| `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` | Legacy fallback (square, 48×scale) |
| `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.png` | Legacy fallback (circle-masked) |
| `mipmap-anydpi-v26/ic_launcher{,_round}.xml` | API-26+ adaptive-icon wrappers |

Hooked into Android `preBuild` so files are in place by resource processing.
The plugin does **not** copy anything into `commonMain/composeResources/` —
if you want the logo available to Compose, place it in `composeResources/`
yourself.

**iOS (`syncIosLogo`)** composites foreground over background (PNG or
synthesized solid colour) at 1024×1024, flattens to opaque RGB (App Store
rejects alpha icons), writes `AppIcon-1024.png` and a single-image
universal `Contents.json`. Requires iOS deployment target 14+. Hooked into
the iOS framework link tasks.

### Migrating older versions

Pre-1.1 plugin versions used `appLogoXml` + `appLogoPng` and generated
different files. (`appLogoBackgroundColor` is **not** legacy — it's a current,
first-class background source; see [App logo](#app-logo).) After upgrading,
clean the orphans once:

```bash
./gradlew cleanupLegacyAppLogoArtifacts
```

…or set `cleanupLegacyLogoArtifacts = true` in the DSL until you've shipped
a build with the new layout. The task removes
`${androidAppModule}/src/main/res/drawable/ic_launcher.xml` and
`${androidAppModule}/src/main/res/values/ic_launcher_background.xml` — both
were 100% plugin-owned in older versions, so deletion is safe.

If you upgraded from 1.1 to 1.2 and your icon now looks small on Android:
1.1 expected the FG to be designed *inside* the inner 61% of the canvas;
1.2 expects the FG to fill the canvas naturally and auto-pads it. Re-export
your FG to fill the source PNG.

**Template icon collisions.** A fresh Android Studio / KMP wizard app ships
`mipmap-*/ic_launcher.webp` (and friends). The plugin generates `.png` launcher
icons with the same names, and two `ic_launcher.*` in one `mipmap-*` bucket is a
duplicate resource that fails the AAPT2 merge. The first Android logo sync warns
with the exact colliding files; run `./gradlew cleanupLegacyAppLogoArtifacts` (or
set `cleanupLegacyLogoArtifacts = true`) to remove them.

---

## Shared-module rename SSOT

`sharedModule` is the directory name of the KMP shared module — required.
When you rename it (say from `shared` to `composeApp`):

1. Rename the directory and update `settings.gradle.kts` `include(":...")`.
2. Update `kmpSsot { sharedModule = "composeApp" }`.
3. Reference it in your shared module's cocoapods `baseName`:
   ```kotlin
   // shared (or composeApp)/build.gradle.kts
   val ssot = rootProject.extensions.getByType<io.github.yuroyami.kmpssot.KmpSsotExtension>()
   kotlin {
       cocoapods {
           framework { baseName = ssot.sharedModule.get() }
       }
   }
   ```
4. Run `./gradlew syncIosConfig`.

The plugin detects the old name from the existing `iosApp/Podfile` line
(`pod 'X', :path => '../X'`, only when the pod name equals the path tail) and
rewrites:

- The Podfile `pod` name + `:path =>` references
- Every exact `import X` (plain form) in `iosApp/**/*.swift` — submodule imports
  (`import X.Sub`), same-prefix modules (`import XKit`), and `@testable import`
  are left untouched

If the pod name differs from the directory or uses a nested path, auto-detection
can't safely guess — set `oldSharedModuleName = "..."` explicitly.

Then `pod install` in `iosApp/` (or `./gradlew :${sharedModule}:podInstall`)
refreshes the Pods workspace from the new podspec — `Pods.xcodeproj`, the
iOS app's pbxproj linker entries, and the generated podspec all rebuild
from there.

`@_implementationOnly import` and Bridging-Header `#import <X/X.h>` are
**not** touched — the regex is intentionally narrow. Edit those by hand if
they exist.

---

## Auto-detected locales

If `locales` is not set explicitly, the plugin scans
`${sharedModule}/src/commonMain/composeResources/` for directories named
`values-<tag>` (matching the Compose Resources convention). Override with
`locales = listOf("en", "ar")` to force a specific list.

The list propagates to:

- Android application modules via `androidResources.localeFilters` (AGP 9), with a
  runtime fallback to the deprecated `resourceConfigurations` on AGP 8; library
  modules use `resourceConfigurations` (they have no `localeFilters`)
- iOS `project.pbxproj` `knownRegions` (preserves `Base`)

Android region qualifiers are mapped to the Apple form for `knownRegions`
(`pt-rBR` → `pt-BR`, `b+sr+Latn` → `sr-Latn`); Android keeps its own tags.
Non-locale `values-*` directories (`values-night`, `values-v26`, `values-land`)
are ignored by auto-detection.

---

## iOS feature flags

`kmpSsot { ios { … } }` propagates a small set of Info.plist feature flags
that nearly every codebase ends up tweaking by hand. Each is a `Boolean`
property — unset by default, so the plugin leaves the plist alone unless you
opt in. When set, the value is inserted into `Info.plist` by
`sanitizeIosProject`, or overwritten if the existing value differs (the DSL
is the source of truth for these keys, unlike `CFBundleDisplayName` &
friends which point at Xcode build settings).

| DSL property | Info.plist key | When you want it |
|---|---|---|
| `usesNonExemptEncryption` | `ITSAppUsesNonExemptEncryption` | Set to `false` to silence App Store Connect's "Missing Compliance" prompt for apps using only standard/exempt encryption (HTTPS, system frameworks). Set to `true` if you genuinely use non-exempt encryption and have filed the docs. |
| `proMotion120Hz` | `CADisableMinimumFrameDurationOnPhone` | Set to `true` to opt into ProMotion's high refresh rate (up to 120 Hz) on supported iPhones. Without it, iOS caps the app to 60 Hz. |

```kotlin
kmpSsot {
    // …identity fields…

    ios {
        usesNonExemptEncryption = false
        proMotion120Hz          = true
    }
}
```

These run through the same `sanitizeIosProject` task as the SSOT-pointing
keys, so they're gated by `syncIos` + `sanitizeIosProject` (both default
true). If you set the property to one value in the DSL and a different one
in the plist by hand, the DSL wins on the next sync.

---

## What gets auto-wired

| Where | Values |
|---|---|
| `com.android.application` | `applicationId`, `versionCode`/`versionName`, `compileOptions` + Kotlin `jvmTarget`, `manifestPlaceholders["appName"]`, `androidResources.localeFilters` (via `finalizeDsl`, SSOT-authoritative) |
| `com.android.library` | `compileOptions` + Kotlin `jvmTarget`, `resourceConfigurations` (via `finalizeDsl`, SSOT-authoritative) |
| `org.jetbrains.kotlin.multiplatform` | `syncIosConfig` + `syncIosLogo` hooked into `linkPod*FrameworkIos*` + `embedAndSignAppleFrameworkForXcode` |
| `com.android.application` (SDK) | `compileSdk`, `defaultConfig.minSdk`/`targetSdk`, `ndkVersion` (from `android { }`) |
| `com.android.library` (SDK) | `compileSdk`, `defaultConfig.minSdk`, `ndkVersion` (from `android { }`) |
| `com.android.kotlin.multiplatform.library` (SDK) | `compileSdk`, `minSdk` via `finalizeDsl` (from `android { }`; no `targetSdk`/`ndkVersion`) |
| iOS `project.pbxproj` (idempotent, **application target only**) | `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`, `INFOPLIST_KEY_CFBundleDisplayName`, `INFOPLIST_KEY_CFBundleName`, `PRODUCT_NAME`, `PRODUCT_BUNDLE_IDENTIFIER`; `knownRegions` (project-level) |
| iOS `Info.plist` (when `ios { }` flags set) | `ITSAppUsesNonExemptEncryption`, `CADisableMinimumFrameDurationOnPhone` |
| iOS `Podfile` (when `sharedModule` differs) | `pod 'X', :path => '../X'` lines |
| iOS `iosApp/**/*.swift` (when `sharedModule` differs) | plain `import X` statements |
| iOS `AppIcon.appiconset/` | `AppIcon-1024.png` (FG-over-BG, opaque) + universal `Contents.json` |
| Android `${androidAppModule}/src/main/res/` | full launcher-icon tree (adaptive + legacy, all densities) |

`versionCode` is derived from `versionName` via
`"1" + dot-segments-padded-to-3` (e.g. `0.3.0` → `1000003000`).

---

## Safety: verify, dry-run, backups

The plugin rewrites files you own (pbxproj, `Info.plist`, Podfile, Swift). Three
safety nets:

- **`./gradlew kmpSsotVerify`** prints the resolved SSOT values (identity, Android
  SDK levels, `javaVersion`, toolchain toggles, logo config) and which iOS target
  files exist. It modifies nothing — run it to sanity-check config.
- **`./gradlew kmpSsotDoctor`** runs an end-to-end diagnostic with a PASS/WARN/FAIL
  line and a fix per check: the Android manifest `${appName}` placeholder, the
  Info.plist SSOT references, the pbxproj application target, the appiconset,
  Android launcher-icon collisions, locale sanity, `versionCode` derivability, and
  Kotlin-plugin visibility. Modifies nothing.
- **Aggregate tasks** — `kmpSsotSync` runs every sync; `kmpSsotSyncIos` and
  `kmpSsotSyncAndroid` scope to one platform, so you don't need to know each
  individual task name.
- **`kmpSsot { dryRun = true }`** makes every rewriting task log the change it
  *would* make and write nothing. Run a sync task to preview exact edits.
- **`backupBeforeRewrite`** (default **true**) copies a user-owned file to
  `<file>.kmpssot.bak` before its first real edit, so any unexpected rewrite is
  recoverable. Generated launcher icons are plugin-owned and not backed up.

Identity rewrites are idempotent — re-running with the same DSL is a no-op — and
a `versionName` the plugin can't turn into a `versionCode` (non-numeric, 4+
segments, a segment > 999) **fails fast at configuration** with guidance instead
of crashing mid-build. Set `versionCodeOverride` for those.

## Edge cases

### iOS launcher name

The iOS home screen shows `CFBundleDisplayName` (falling back to
`CFBundleName`). The plugin drives the app name through **`PRODUCT_NAME`**,
which it rewrites in every build config — and `sanitizeIosProject` ensures the
on-disk `Info.plist` has `CFBundleDisplayName` / `CFBundleName` pointing at
`$(PRODUCT_NAME)`. So for the common on-disk-`Info.plist` setup the name
propagates with no manual pbxproj editing.

The plugin also rewrites `INFOPLIST_KEY_CFBundleDisplayName` /
`INFOPLIST_KEY_CFBundleName` **when they exist** (the generated-plist path), but
never inserts those — pbxproj has structured per-target build-config sections
and blind injection would land in the wrong one. If you use a generated
`Info.plist` and the template didn't emit `INFOPLIST_KEY_CFBundleName`, add that
line once to the main target's build config; otherwise `PRODUCT_NAME` already
covers you.

### Multi-target iOS projects

pbxproj build settings (`PRODUCT_NAME`, `PRODUCT_BUNDLE_IDENTIFIER`,
`MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`, `INFOPLIST_KEY_*`) are rewritten
**only inside the application target's build configurations**. Test targets and
app extensions keep their own `PRODUCT_NAME` and their distinct, dot-suffixed
bundle IDs (`com.app.tests`, `com.app.widget`) — so test-bundle linkage and App
Store validation stay intact.

The plugin resolves the application target by walking the pbxproj object graph
(`PBXNativeTarget` with `productType = com.apple.product-type.application` →
its `buildConfigurationList` → those `XCBuildConfiguration` objects). If it can't
find an application target (an unusual or framework-only project) it falls back to
a global rewrite and logs a warning so you can verify your other targets.
`knownRegions` is a single project-level block and is always rewritten globally.

---

## Roadmap

- xcconfig-driven iOS propagation — write a single `kmpssot.xcconfig` the
  pbxproj includes, instead of regex-rewriting the pbxproj per-key.
- Themed-icon support (`<monochrome>`) for Android 13+ adaptive icons.
- `LaunchScreen.storyboard` logo injection — inject the iOS logo into the
  launch-screen image view too, not just the AppIcon.
- Auto-rename — actually rename the shared module directory on disk +
  update `settings.gradle.kts`. Today the plugin assumes the rename has
  already happened.

---

## Contributing

```bash
./gradlew build           # compile + run the test suite
./gradlew test            # unit + GradleRunner functional tests
./gradlew validatePlugins # Gradle's plugin-correctness checks
```

CI runs all three on every push/PR. Releases are tag-driven: pushing a `v*` tag
publishes to the Gradle Plugin Portal and GitHub Packages.

## License

Licensed under the [Apache License 2.0](LICENSE).
