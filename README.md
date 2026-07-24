# KiteSSOT

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.yuroyami.kitessot?label=plugin%20portal)](https://plugins.gradle.org/plugin/io.github.yuroyami.kitessot)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

KiteSSOT lets a Kotlin Multiplatform project declare shared app settings once,
in the root Gradle build. It can then apply those settings to Android, prepare
reviewable Apple project updates, and generate values that common Kotlin code
can read.

You choose each feature separately. Most unset values leave the existing
project configuration alone. Two useful exceptions have defaults:
`iosMarketingVersion` follows `versionName`, and locales can be discovered from
Compose resources. Features that need several inputs fail with setup guidance
when one is missing.

| Declare once | KiteSSOT can use it for |
|---|---|
| App name, version, and bundle ID | Android app configuration, Apple project updates, and generated runtime constants |
| Android SDK and Java versions | Android applications, Android libraries, and compatible Kotlin JVM targets |
| Locales | Apple regions, optional Android resource filtering, and generated runtime constants |
| Public client values | A typed Kotlin object in `commonMain` |
| Logo layers | Android launcher icons and an Apple AppIcon catalog |
| Native opt-ins | Selected Kotlin/Native compilations |
| Browser worker settings | A generated Kotlin/JS offload helper |

This README is the guided tour. See [FEATURES.md](FEATURES.md) for the exact
capability and safety reference.

[Start here](#start-in-five-minutes) |
[See every option](#complete-api-example) |
[Read values yourself](#read-kitessot-values-yourself) |
[Run source-changing tasks](#apple-updates-and-logo-installation)

## Start in five minutes

### 1. Apply the plugin at the root

KiteSSOT is a root plugin. If Android or Kotlin Multiplatform plugins are used
by subprojects, declare their versions at the root with `apply false`. This lets
KiteSSOT use their typed Gradle APIs.

```kotlin
// Root build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("io.github.yuroyami.kitessot") version "2.0.0"
}
```

### 2. Declare only the values you want KiteSSOT to own

```kotlin
// Root build.gradle.kts
kiteSsot {
    appName = "Jetzy"
    versionName = "1.4.0"
    bundleIdBase = "com.example.jetzy"

    sharedProjectPath = ":shared"
    androidApplicationProjects.add(":androidApp")

    android {
        compileSdk = 36
        minSdk = 26
        targetSdk = 36
    }
}
```

That small block already does useful work:

- the selected Android app receives the app ID, version name, derived version
  code, and `appName` manifest placeholder;
- compatible Android modules receive the declared SDK values;
- values you did not set, such as `ndkVersion`, stay under module control.

Use the app name placeholder in the selected Android manifest:

```xml
<application android:label="${appName}" />
```

### 3. Check the result

Start with commands that do not change source files:

```bash
./gradlew kiteSsotVerify
./gradlew kiteSsotDoctor
./gradlew kiteSsotPlan
./gradlew kiteSsotCheck
```

`kiteSsotVerify` prints the resolved model. `kiteSsotDoctor` explains setup
problems without failing the build. `kiteSsotPlan` shows enabled operations and
their destinations. `kiteSsotCheck` writes a CI report and fails on errors.

## What KiteSSOT applies automatically

KiteSSOT applies an optional value only when it is present. When that feature
has a propagation switch, the switch must also be enabled. Generators use their
own `enabled` switches.

| Destination | Values applied during normal Gradle configuration |
|---|---|
| Selected Android application | App ID, version name, version code, `appName` manifest placeholder, and optional locale filters |
| Every classic Android application | `compileSdk`, `minSdk`, `targetSdk`, `ndkVersion`, Java compatibility, and matching Kotlin JVM target when supported |
| Every classic Android library | `compileSdk`, `minSdk`, `ndkVersion`, Java compatibility, and matching Kotlin JVM target when supported |
| KMP-native Android library | `compileSdk` and `minSdk` |
| Compatible KMP, Kotlin/JVM, and Kotlin Android projects | Kotlin JVM target when `javaVersion` is set |
| Apple source files | Nothing during a normal build. Apple updates require an explicit `kiteSsot*` task |
| `commonMain` | Generated `BuildConfig` only when `buildConfig.enabled = true` |
| Selected browser source sets | Generated worker helper only when `web.generateIoWorker = true` |

`androidApplicationProjects` limits app identity and locale filtering. It does
not limit Android SDK or JVM policy. Those values are shared toolchain policy.

KiteSSOT applies Android values after each module's own `android {}` block. A
value declared in KiteSSOT wins over the same value in a module. Leave the
KiteSSOT value unset when the module should remain in charge.

## Complete API example

The next example is a map of every preferred DSL option. It deliberately enables
several independent features so their requirements are visible. It is not a
starter template. Copy the small setup above, then add only the parts you need.

Each KDoc comment says whether a value is optional, has a default, or becomes
required when another feature is enabled. Deprecated 1.x compatibility names
are intentionally omitted.

```kotlin
// Root build.gradle.kts
import io.github.yuroyami.kitessot.PlistConflictPolicy

kiteSsot {
    // Identity and release =====================================================

    /**
     * OPTIONAL.
     * REQUIRED WHEN buildConfig.enabled = true and includeIdentity = true.
     * Display name used by enabled Android and Apple consumers.
     */
    appName = "Jetzy"

    /**
     * OPTIONAL.
     * REQUIRED WHEN buildConfig.enabled = true and includeIdentity = true.
     * Android versionName. Apple marketing version uses this by default.
     * A plain x.y.z value can also produce Android's versionCode.
     */
    versionName = "1.4.0"

    /**
     * OPTIONAL.
     * REQUIRED WHEN buildConfig.enabled = true and includeIdentity = true.
     * Base ID used to create Android and Apple identifiers.
     */
    bundleIdBase = "com.example.jetzy"

    /**
     * OPTIONAL.
     * Android-only text appended to bundleIdBase.
     */
    androidApplicationIdSuffix = ".demo"

    /**
     * OPTIONAL.
     * Apple-only text appended to bundleIdBase.
     */
    iosBundleSuffix = ".demo"

    /**
     * OPTIONAL.
     * Explicit Android versionCode. Remove this to derive it from versionName.
     */
    versionCodeOverride = 1_004_000_042

    /**
     * OPTIONAL. DEFAULT: versionName.
     * Apple MARKETING_VERSION used by explicit iOS synchronization.
     */
    iosMarketingVersion = "1.4.0"

    /**
     * OPTIONAL.
     * Apple CURRENT_PROJECT_VERSION used by explicit iOS synchronization.
     * Use one to three numeric parts. The first part must be positive.
     */
    iosBuildNumber = "42"

    // Shared toolchain and locales ============================================

    /**
     * OPTIONAL.
     * Java compatibility for classic Android modules and Kotlin JVM target
     * alignment for compatible Kotlin projects.
     */
    javaVersion = 17

    /**
     * OPTIONAL.
     * Canonical locale tags. When omitted, KiteSSOT can discover supported
     * values-* directories from the selected Compose resources directory.
     */
    locales.set(listOf("en", "en-US", "fr"))

    // Project selection ========================================================

    /**
     * REQUIRED WHEN BuildConfig generation is enabled.
     * Also provides the default scope for locale discovery, Native opt-ins,
     * and browser worker generation.
     */
    sharedProjectPath = ":shared"

    /**
     * REQUIRED WHEN more than one Android app exists and an app-level feature
     * is active. One app is selected automatically when it is unambiguous.
     */
    androidApplicationProjects.add(":androidApp")

    /**
     * REQUIRED WHEN Native opt-ins are enabled and sharedProjectPath is not
     * the intended scope.
     */
    interopProjectPaths.add(":shared")

    // Typed paths ==============================================================

    /**
     * OPTIONAL.
     * Explicit Compose resources directory used for locale discovery.
     */
    composeResourcesDirectory.set(
        layout.projectDirectory.dir("shared/src/commonMain/composeResources")
    )

    /**
     * OPTIONAL.
     * Android app source directory. Auto-detected from the selected app in
     * normal Android builds.
     */
    androidAppDirectory.set(layout.projectDirectory.dir("apps/android"))

    /**
     * OPTIONAL. DEFAULT: iosApp/iosApp.xcodeproj/project.pbxproj.
     * Xcode project file used by explicit iOS tasks.
     */
    iosPbxprojFile.set(
        layout.projectDirectory.file("apps/ios/Jetzy.xcodeproj/project.pbxproj")
    )

    /**
     * OPTIONAL. DEFAULT: iosApp/Podfile.
     * Podfile used only for an enabled shared-module reference migration.
     */
    iosPodfileFile.set(layout.projectDirectory.file("apps/ios/Podfile"))

    /**
     * OPTIONAL. DEFAULT: iosApp/iosApp/Info.plist.
     * Source XML plist used by explicit sanitization.
     */
    iosInfoPlistFile.set(
        layout.projectDirectory.file("apps/ios/Jetzy/Info.plist")
    )

    /**
     * OPTIONAL. DEFAULT: iosApp.
     * Apple source tree searched by shared-module import migration.
     */
    iosAppDirectory.set(layout.projectDirectory.dir("apps/ios"))

    /**
     * OPTIONAL.
     * DEFAULT: iosApp/iosApp/Assets.xcassets/AppIcon.appiconset.
     * AppIcon catalog directory used by the Apple logo installer.
     */
    iosAppIconDirectory.set(
        layout.projectDirectory.dir(
            "apps/ios/Jetzy/Assets.xcassets/AppIcon.appiconset"
        )
    )

    // Propagation switches =====================================================

    /** DEFAULT: true. Apply appName when it is present. */
    propagateAppName = true

    /** DEFAULT: true. Apply resolved Android and Apple identifiers. */
    propagateBundleId = true

    /** DEFAULT: true. Apply present platform version values. */
    propagateVersion = true

    /** DEFAULT: true. Add locale metadata to enabled consumers. */
    propagateLocaleList = true

    /** DEFAULT: true. Apply values from android { } to compatible modules. */
    propagateAndroidSdk = true

    /**
     * DEFAULT: false.
     * Replace the selected Android app's locale filters. This affects packaging.
     * At least one locale is required when enabled.
     */
    filterAndroidResources = true

    /**
     * DEFAULT: false.
     * Enable explicitly invoked Android and Apple logo installers.
     * Apple installation also requires syncIos = true.
     */
    propagateLogo = true

    /**
     * DEFAULT: false.
     * Allow explicit Podfile and Swift import migration.
     * Requires syncIos plus the old and new module names below.
     */
    propagateSharedModule = true

    /**
     * DEFAULT: false.
     * Add the built-in interop markers to selected Native compilations.
     */
    propagateInteropOptIns = true

    /**
     * OPTIONAL.
     * Extra fully qualified opt-in markers for the same Native scope.
     */
    extraOptIns.add("kotlin.experimental.ExperimentalObjCRefinement")

    /**
     * DEFAULT: false.
     * Authorize explicitly invoked Apple source update tasks.
     */
    syncIos = true

    /**
     * DEFAULT: false. REQUIRES: syncIos = true.
     * Maintain KiteSSOT references in a source XML Info.plist.
     */
    sanitizeIosProject = true

    /**
     * DEFAULT: false.
     * Allow the Android logo task to back up and replace known legacy icons.
     * Requires propagateLogo and a complete replacement logo.
     */
    cleanupLegacyLogoArtifacts = true

    /**
     * DEFAULT: true.
     * Keep first-contact recovery copies for user-owned source and Apple assets.
     */
    backupBeforeRewrite = true

    /**
     * DEFAULT: false.
     * Preview explicitly invoked source-changing tasks without applying them.
     * Build-owned generated Kotlin source is still produced.
     */
    dryRun = true

    // Shared-module migration ==================================================

    /**
     * REQUIRED WHEN shared-module migration is enabled.
     * Previous CocoaPods and Swift module name.
     */
    iosPreviousSharedModuleName = "SharedKit"

    /**
     * REQUIRED WHEN shared-module migration is enabled.
     * New CocoaPods and Swift module name.
     */
    iosSharedModuleName = "JetzyShared"

    // Logo inputs ==============================================================

    /**
     * REQUIRED WHEN propagateLogo = true.
     * Foreground PNG for both platform installers.
     */
    appLogoPngForeground.set(
        layout.projectDirectory.file("branding/logo-foreground.png")
    )

    /**
     * REQUIRED WHEN propagateLogo = true.
     * Choose this color or appLogoPngBackground, never both.
     */
    appLogoBackgroundColor = "#6750A4"

    /**
     * ALTERNATIVE to appLogoBackgroundColor.
     * Uncomment this and remove the color when the background is a PNG.
     */
    // appLogoPngBackground.set(
    //     layout.projectDirectory.file("branding/logo-background.png")
    // )

    /**
     * OPTIONAL. DEFAULT: 66.0 / 108.0.
     * Foreground size inside Android's adaptive icon canvas.
     */
    appLogoAndroidSafeZoneRatio = 66.0 / 108.0

    // Android ==================================================================

    android {
        /**
         * OPTIONAL.
         * Offline guard. The next resolved versionCode must be greater.
         */
        publishedVersionCode = 1_004_000_041

        /** OPTIONAL. Applied to every compatible Android module. */
        compileSdk = 36

        /** OPTIONAL. Applied to every compatible Android module. */
        minSdk = 26

        /** OPTIONAL. Applied to Android applications only. */
        targetSdk = 36

        /** OPTIONAL. Applied to classic Android modules only. */
        ndkVersion = "27.0.12077973"
    }

    // Apple ====================================================================

    ios {
        /**
         * REQUIRED for Apple universal AppIcon installation.
         * This validates compatibility. It does not configure Xcode's
         * IPHONEOS_DEPLOYMENT_TARGET.
         */
        deploymentTarget = "12.0"

        /**
         * REQUIRED WHEN an enabled Xcode app-setting update must choose among
         * multiple application targets. A sole app is selected automatically.
         * Project-level locales and file-only updates do not need this selector.
         */
        targetNames.add("Jetzy")

        /**
         * DEFAULT: FAIL.
         * Choose FAIL, KEEP, or REPLACE for conflicting plist values.
         */
        plistConflictPolicy = PlistConflictPolicy.FAIL

        /**
         * OPTIONAL.
         * Manage ITSAppUsesNonExemptEncryption during explicit plist updates.
         */
        usesNonExemptEncryption = false

        /**
         * OPTIONAL.
         * Manage CADisableMinimumFrameDurationOnPhone in the source plist.
         */
        proMotion120Hz = true
    }

    // Browser Kotlin/JS ========================================================

    web {
        /** DEFAULT: false. Generate the browser-only kiteSsotOffload helper. */
        generateIoWorker = true

        /**
         * REQUIRED WHEN generation is enabled.
         * Exact Kotlin/JS targets that are configured with browser().
         */
        browserTargetNames.add("js")

        /**
         * REQUIRED WHEN sharedProjectPath is not the intended worker scope.
         * Each path must select a Kotlin Multiplatform project.
         */
        projectPaths.add(":shared")

        /** OPTIONAL. DEFAULT: kitessot.generated. */
        ioWorkerPackage = "com.example.jetzy.generated"
    }

    // Runtime constants ========================================================

    buildConfig {
        /** DEFAULT: false. Generate a Kotlin object into commonMain. */
        enabled = true

        /** OPTIONAL. DEFAULT: kitessot.generated. */
        packageName = "com.example.jetzy.generated"

        /** OPTIONAL. DEFAULT: BuildConfig. */
        className = "AppConfig"

        /**
         * DEFAULT: true.
         * Include appName, version values, IDs, and locales. Complete identity
         * values are required when this is true.
         */
        includeIdentity = true

        /**
         * DEFAULT: false.
         * Enable only for public values and trusted build caches.
         */
        allowBuildCache = false

        /** OPTIONAL. Public client configuration only, never secrets. */
        stringField("BASE_URL", "https://api.example.com")
        stringField(
            "PUBLIC_CHANNEL",
            providers.gradleProperty("publicChannel").orElse("stable"),
        )
        intField("API_TIMEOUT_MS", 30_000)
        longField("CACHE_BYTES", 5_000_000L)
        booleanField("ANALYTICS_ENABLED", true)
        doubleField("SAMPLE_RATE", 0.25)
    }
}
```

The full example uses the preferred 2.x API. These older properties still exist
only so existing builds can migrate:

| Compatibility property | Preferred replacement |
|---|---|
| `sharedModule` | `sharedProjectPath`, `composeResourcesDirectory`, and `iosSharedModuleName` |
| `oldSharedModuleName` | `iosPreviousSharedModuleName` |
| `androidAppModule` | `androidApplicationProjects` or `androidAppDirectory` |
| `iosProjectPath` | `iosPbxprojFile` |
| `iosPodfilePath` | `iosPodfileFile` |
| `iosInfoPlistPath` | `iosInfoPlistFile` |
| `iosAppDir` | `iosAppDirectory` |
| `iosAppiconsetPath` | `iosAppIconDirectory` |
| `buildConfig.fields` | `stringField`, `intField`, `longField`, `booleanField`, and `doubleField` |

## Read KiteSSOT values yourself

There are three different places where you may want a value. Use the matching
approach so it is clear whether you are reading Gradle configuration or
application runtime data.

### In root Gradle build logic

The root extension exposes Gradle `Property` and `Provider` values. Keep the
provider lazy when the receiving API accepts one.

```kotlin
// Root build.gradle.kts
import io.github.yuroyami.kitessot.KiteSsotExtension
import org.gradle.kotlin.dsl.getByType

val ssot = extensions.getByType<KiteSsotExtension>()

val minSdkProvider = ssot.android.minSdk
val versionCodeProvider = ssot.versionCode
val androidIdProvider = ssot.androidApplicationId

tasks.register("printKiteSsotMinSdk") {
    inputs.property("minSdk", minSdkProvider)
    doLast {
        logger.lifecycle("KiteSSOT minSdk = ${minSdkProvider.get()}")
    }
}
```

If another Gradle plugin exposes a `Property<Int>`, connect the providers
directly:

```kotlin
// Replace this with the other plugin's actual extension.
otherPluginExtension.minimumSdk.set(ssot.android.minSdk)
```

Use `.get()` only when an API requires a plain value and only after you have set
that optional property:

```kotlin
val requiredMinSdk: Int = ssot.android.minSdk.get()
val optionalMinSdk: Int? = ssot.android.minSdk.orNull
```

Available read-only derived providers are:

| Provider | Result |
|---|---|
| `ssot.versionCode` | Explicit or derived Android version code |
| `ssot.androidApplicationId` | Base bundle ID plus Android suffix |
| `ssot.iosBundleId` | Base bundle ID plus Apple suffix |
| `ssot.canonicalLocales` | Normalized, de-duplicated locale list |
| `ssot.resolvedSharedProjectPath` | Effective shared Gradle project path |

### In a subproject build script

The current compatibility accessor can read the root model:

```kotlin
@file:Suppress("DEPRECATION")

import io.github.yuroyami.kitessot.kiteSsot

val minSdkProvider = kiteSsot.android.minSdk
val optionalMinSdk = minSdkProvider.orNull
```

This accessor is deprecated because it reaches across Gradle projects and does
not support Isolated Projects. Treat it as read-only. Never configure or mutate
the root model from a subproject. For new shared build logic, prefer a convention
plugin with its own local input or pass the provider from root build logic.

### In application code

Enable `buildConfig` to generate a typed object in the selected shared project's
`commonMain`:

```kotlin
kiteSsot {
    appName = "Jetzy"
    versionName = "1.4.0"
    bundleIdBase = "com.example.jetzy"
    sharedProjectPath = ":shared"

    buildConfig {
        enabled = true
        packageName = "com.example.jetzy.generated"
        className = "AppConfig"
    }
}
```

Read it from `commonMain` and production source sets that depend on
`commonMain`:

```kotlin
import com.example.jetzy.generated.AppConfig

fun userAgent(): String =
    "${AppConfig.appName}/${AppConfig.versionName} (${AppConfig.versionCode})"
```

With identity enabled, the generated object contains `appName`, `versionName`,
`versionCode`, `androidApplicationId`, `iosBundleId`, and `locales`. SDK and
toolchain values are not included automatically.

If runtime code needs `minSdk`, keep one root value and use it for both Android
configuration and a custom generated field:

```kotlin
val appMinSdk = 26

kiteSsot {
    sharedProjectPath = ":shared"

    android {
        minSdk = appMinSdk
    }

    buildConfig {
        enabled = true
        includeIdentity = false
        packageName = "com.example.jetzy.generated"
        className = "PlatformConfig"
        intField("MIN_SDK", appMinSdk)
    }
}
```

```kotlin
import com.example.jetzy.generated.PlatformConfig

val minimumSupportedAndroidSdk = PlatformConfig.MIN_SDK
```

Generated values are public binary inputs. Do not put credentials, signing
material, private API keys, or other secrets in `buildConfig`.

## Common feature walkthroughs

### Versions

`versionName = "1.2.3"` produces Android version code `1001002003`. Use
`versionCodeOverride` for prerelease names, four-part versions, or another store
numbering scheme.

Apple numbers are independent:

```kotlin
kiteSsot {
    versionName = "1.4.0"
    iosMarketingVersion = "1.4.0"
    iosBuildNumber = "42"
}
```

`android.publishedVersionCode` is an optional offline check. It runs when an
Android application is detected and version propagation is enabled. KiteSSOT
never contacts a store and never increments versions for you.

### Locales

Set locales directly:

```kotlin
kiteSsot {
    locales.set(listOf("en", "en-US", "fr", "sr-Latn"))
}
```

Or let KiteSSOT discover exact locale directories such as `values-en`,
`values-pt-rBR`, and `values-b+sr+Latn` under the selected Compose resources
directory.

Locale metadata does not remove Android resources. Turn on
`filterAndroidResources` only when you want the selected application package to
contain the declared locale set:

```kotlin
kiteSsot {
    locales.set(listOf("en", "fr"))
    filterAndroidResources = true
}
```

### Browser worker

After enabling the `web {}` block from the complete example, call the generated
helper from the selected browser target:

```kotlin
import com.example.jetzy.generated.kiteSsotOffload

suspend fun parseAwayFromTheUiThread(json: String): String =
    kiteSsotOffload(
        jobJs = "(value) => JSON.parse(value).name",
        payload = json,
        timeoutMillis = 1_000L,
    )
```

The consumer supplies `kotlinx-coroutines-core`. The helper is for browser
Kotlin/JS, not Node.js or wasm. `jobJs` is executable JavaScript, so it must not
come from user input. The deployed Content Security Policy must allow Blob
workers, normally through `worker-src blob:`.

### Native interop opt-ins

```kotlin
kiteSsot {
    sharedProjectPath = ":shared"
    propagateInteropOptIns = true
    interopProjectPaths.add(":shared")
    extraOptIns.add("kotlin.experimental.ExperimentalObjCRefinement")
}
```

This adds compiler opt-ins to selected Native compilations. It does not edit
source files.

## Apple updates and logo installation

KiteSSOT does not change Xcode projects, plists, Podfiles, Swift files, or source
assets during an ordinary build. These changes use explicit tasks.

Use this workflow:

1. Configure exact paths and target names.
2. Enable only the operation you need.
3. Run `./gradlew kiteSsotPlan`.
4. Set `dryRun = true`.
5. Run one explicit task and review its preview.
6. Set `dryRun = false`, run that same task, then inspect the diff.
7. Run `kiteSsotDoctor` and `kiteSsotCheck`.

| Task | Purpose |
|---|---|
| `kiteSsotSanitizeIosProject` | Maintain configured keys in a source XML Info.plist |
| `kiteSsotSyncIosConfig` | Apply selected Xcode, plist, Podfile, and Swift plans |
| `kiteSsotSyncIosLogo` | Install the selected Apple AppIcon catalog |
| `kiteSsotSyncAndroidLogo` | Install the Android launcher icon tree |
| `kiteSsotCleanupLegacyAppLogoArtifacts` | Back up and remove selected legacy Android icon files |

There is no "apply everything" task. Each source-changing operation has its own
review and recovery boundary.

For Android icons, the manifest must use the generated resource names:

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round" />
```

For Apple icons, the selected Xcode application target must already have
`ASSETCATALOG_COMPILER_APPICON_NAME` for the chosen catalog. The universal
AppIcon installer requires Xcode 14 or newer and
`ios.deploymentTarget = "12.0"` or newer. That property is a compatibility
assertion. It does not write `IPHONEOS_DEPLOYMENT_TARGET`.

Both logo installers enforce path containment and checksum ownership. Android
refuses unowned collisions unless `cleanupLegacyLogoArtifacts` explicitly
authorizes a backed-up takeover. Apple can back up eligible first-contact files
when `backupBeforeRewrite` is enabled. Later manual changes are not silently
replaced, and unreferenced Apple PNGs are reported instead of deleted.

## Defaults worth remembering

| Setting | Default |
|---|---|
| Identity, SDK, Java, Apple build number, logo inputs | Unset |
| `iosMarketingVersion` | Follows `versionName` |
| Identity, locale metadata, and Android SDK propagation | `true` |
| Android locale filtering | `false` |
| Logo, shared-module migration, and Native opt-ins | `false` |
| Apple sync and plist sanitization | `false` |
| `backupBeforeRewrite` | `true` |
| `dryRun` | `false` |
| Browser worker generation | `false` |
| BuildConfig generation | `false` |
| BuildConfig identity fields | `true` after generation is enabled |
| BuildConfig cache storage | `false` |
| Plist conflict policy | `FAIL` |

## Compatibility

| Component | Supported range |
|---|---|
| Gradle | 8.5 or newer |
| JVM running Gradle | Java 17 or 21 in CI |
| Kotlin Gradle plugin | Stable 2.4.x |
| Android Gradle plugin | 8.5.2 through 9.2.x |

The plugin is built with Java 21 and emits Java 17 bytecode. The root aggregation
design supports Gradle configuration cache, but not Gradle Isolated Projects.

## Current boundaries

KiteSSOT does not provide:

- per-flavor, per-build-type, or per-Xcode-target identity overrides;
- automatic Gradle directory or `settings.gradle` renames;
- generated or binary Info.plist conversion;
- xcconfig generation;
- Node.js or wasm workers;
- SVG or vector logo input;
- Apple dark or tinted icon variants;
- store access, signing, secret management, release upload, or `pod install`.

These are deliberate boundaries, not implied future behavior.

## Upgrading from 1.x

Version 2.0 renamed the plugin and made every source-changing operation explicit.
The short migration checklist is:

1. Change the plugin ID to `io.github.yuroyami.kitessot`.
2. Rename `kmpSsot {}` to `kiteSsot {}`.
3. Replace directory guesses with project selectors and typed paths.
4. Opt into logo, Apple, Native, and locale-filtering behavior explicitly.
5. Keep Android and Apple build numbers separate.
6. Run the four read-only checks before any source-changing task.

See [CHANGELOG.md](CHANGELOG.md) for the complete 2.0 migration record.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow and test
commands. Security reports belong in the process described by
[SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
