# kmp-ssot

A Gradle plugin that gives a Kotlin Multiplatform project **one `kmpSsot { }`
block at the root** and propagates cross-platform identity (app name, version,
bundle ID, locales, app logo) — plus the Android SDK levels — to Android and iOS
automatically.

One source of truth, per-concern opt-out toggles, every identity field
optional.

> **Requirements:** Gradle 8.5+, JDK 17+ (toolchain 21), AGP 8+/9.x. Tested
> against AGP 9.1 and Kotlin 2.3. The root-applied, zero-per-module design means
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
    id("io.github.yuroyami.kmpssot") version "1.4.0"
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
}
```

**Every identity field is optional.** A field propagates iff (a) its
`propagate*` toggle is on AND (b) the value is set. This lets you drop the
plugin onto a live production app and centralize only the parts you want —
e.g. `versionName` + `locales` + `appName`, leaving `bundleIdBase` unset so
the already-registered Android `applicationId` and iOS
`PRODUCT_BUNDLE_IDENTIFIER` are never touched.

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

- Android `defaultConfig.resourceConfigurations` (app + library modules)
- iOS `project.pbxproj` `knownRegions` (preserves `Base`)

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
| `com.android.application` | `applicationId`, `versionCode`/`versionName`, `compileOptions`, `manifestPlaceholders["appName"]`, `resourceConfigurations` |
| `com.android.library` | `compileOptions`, `resourceConfigurations` |
| `org.jetbrains.kotlin.multiplatform` | `syncIosConfig` + `syncIosLogo` hooked into `linkPod*FrameworkIos*` + `embedAndSignAppleFrameworkForXcode` |
| `com.android.application` (SDK) | `compileSdk`, `defaultConfig.minSdk`/`targetSdk`, `ndkVersion` (from `android { }`) |
| `com.android.library` (SDK) | `compileSdk`, `defaultConfig.minSdk`, `ndkVersion` (from `android { }`) |
| iOS `project.pbxproj` (idempotent) | `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`, `INFOPLIST_KEY_CFBundleDisplayName`, `INFOPLIST_KEY_CFBundleName`, `PRODUCT_NAME`, `PRODUCT_BUNDLE_IDENTIFIER`, `knownRegions` |
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

- **`./gradlew kmpSsotVerify`** prints the resolved SSOT values and which iOS
  target files exist. It modifies nothing — run it to sanity-check config.
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

`PRODUCT_BUNDLE_IDENTIFIER` is rewritten for **every** occurrence in pbxproj
— main app, tests, and extensions all get the same value. If a project
genuinely needs distinct bundle IDs per target (e.g. widget extension),
set `propagateBundleId = false` and manage those IDs manually.

---

## Roadmap

- `localeFilters` migration — switch from the deprecated
  `resourceConfigurations` to `androidResources.localeFilters` for AGP 9+.
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
