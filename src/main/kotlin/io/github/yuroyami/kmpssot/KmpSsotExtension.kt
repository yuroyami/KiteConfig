@file:Suppress("DEPRECATION")

package io.github.yuroyami.kmpssot

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * DSL for the kmp-ssot plugin. Applied to the **root project** only.
 *
 * Every identity field is optional. Identity reaches a platform only when its
 * propagation toggle is enabled and the value is present. Source-tree migrations,
 * compiler-policy changes, locale packaging filters, and branding are disabled by
 * default and require an explicit opt-in.
 *
 * App logo: opt in with [propagateLogo], then set [appLogoPngForeground] and
 * exactly one of [appLogoPngBackground] / [appLogoBackgroundColor]. Incomplete
 * layer configuration fails only when propagation is enabled. Apple logo
 * installation also requires [syncIos]. PNG inputs are capped at 32 MiB, 4,096
 * pixels per dimension, and 16,777,216 decoded pixels.
 */
abstract class KmpSsotExtension {

    // --- Identity -------------------------------------------------------------

    /**
     * Cross-platform app name. During explicit iOS synchronization, Apple uses
     * this for `PRODUCT_NAME`, `CFBundleName`, and `CFBundleDisplayName`;
     * diagnostics warn at 16+ characters because Apple recommends a shorter
     * `CFBundleName`.
     */
    abstract val appName: Property<String>

    /**
     * Cross-platform human-readable release version. Android uses it as
     * `versionName`; Apple [iosMarketingVersion] defaults to it. Canonical
     * numeric `x.y.z` is also required when deriving [versionCode]. When an
     * enabled consumer uses the value, it must be non-blank, contain no control
     * characters, and contain at most 255 characters.
     */
    abstract val versionName: Property<String>

    /**
     * Reverse-DNS identifier stem used by [androidApplicationId] and [iosBundleId].
     * Platform suffixes are appended literally and the resolved identifiers are
     * validated before a consuming adapter or migration runs.
     */
    abstract val bundleIdBase: Property<String>

    /**
     * Apple marketing version (`CFBundleShortVersionString`). Defaults to
     * [versionName] and is Apple-validated when iOS version synchronization is
     * enabled.
     */
    abstract val iosMarketingVersion: Property<String>

    /**
     * Apple build number (`CFBundleVersion`): one to three numeric components,
     * with widths 4/2/2 and a positive first component. Deliberately independent
     * from Android [versionCodeOverride].
     */
    abstract val iosBuildNumber: Property<String>

    /** Suffix appended to [bundleIdBase] for the iOS bundle id. Unset means no suffix. */
    abstract val iosBundleSuffix: Property<String>

    /** Suffix appended to [bundleIdBase] for the Android applicationId. Unset means no suffix. */
    abstract val androidApplicationIdSuffix: Property<String>

    /**
     * Explicit Android `versionCode`. When set, it is used verbatim and the
     * derivation from [versionName] is bypassed. Set this when [versionName] is
     * not a plain numeric `x.y.z` or when you want full control. Values are
     * validated against Google Play's `1..2_100_000_000` range.
     */
    abstract val versionCodeOverride: Property<Int>

    // --- Shared toolchain (cross-platform) -----------------------------------

    /**
     * Java source/target compatibility for classic AGP application/library
     * modules, plus root-global Kotlin JVM target alignment where KGP is visible.
     * The alignment reaches every detected project applying Kotlin Multiplatform,
     * Kotlin/JVM, or Kotlin Android and is not scoped by application, shared,
     * interop, or web selectors. AGP's KMP-native Android library DSL exposes no
     * Java `compileOptions`, so only its Kotlin compilation can be configured by
     * that module. **No default**.
     */
    abstract val javaVersion: Property<Int>

    // --- Localization ---------------------------------------------------------

    /**
     * Canonical platform-resource locale tags: 2–3 letter language with optional
     * script, region, and variants. General BCP-47 extensions/private-use tags do
     * not map consistently across Android/Xcode and are rejected. Legacy Android
     * qualifiers are accepted and normalized. Defaults to discovery of exact
     * locale-only resource directories (`values-en`, `values-pt-rBR`, or
     * `values-b+sr+Latn`) when a shared project/directory can be resolved.
     * Configuration accepts at most 1,000 entries, each at most 255 characters,
     * before canonicalization.
     */
    abstract val locales: ListProperty<String>

    // --- Module structure ----------------------------------------------------

    /**
     * Legacy shared module directory/name. Optional. Prefer [sharedProjectPath]
     * and [composeResourcesDirectory] when directory, Gradle project, CocoaPod,
     * and Swift framework names differ.
     */
    @Deprecated(
        "Conflates a Gradle project, directory, and Swift module; use sharedProjectPath, " +
            "composeResourcesDirectory, and iosSharedModuleName.",
    )
    abstract val sharedModule: Property<String>

    /** Absolute Gradle project path that owns shared/common generated source, for example `:shared`. */
    abstract val sharedProjectPath: Property<String>

    /**
     * New CocoaPods/Swift module identifier for an explicit iOS reference
     * migration. Set together with [iosPreviousSharedModuleName]. This is an
     * identifier, not a Gradle project path or filesystem directory.
     */
    abstract val iosSharedModuleName: Property<String>

    /**
     * Previous CocoaPods/Swift module identifier for an explicit iOS reference
     * migration. Set together with [iosSharedModuleName]. The plugin never
     * guesses this value from a Podfile.
     */
    abstract val iosPreviousSharedModuleName: Property<String>

    /** Optional explicit Compose resources directory used for locale discovery. */
    abstract val composeResourcesDirectory: DirectoryProperty

    /**
     * Legacy Android application directory string. Prefer [androidAppDirectory];
     * retained as a compatibility fallback for builds without an application plugin.
     */
    @Deprecated("Use androidApplicationProjects or the typed androidAppDirectory property.")
    abstract val androidAppModule: Property<String>

    /**
     * Typed directory that owns the Android application's `src/main` tree. By
     * default the plugin resolves it from the uniquely selected/detected Android
     * application project, including custom `projectDir` mappings.
     */
    abstract val androidAppDirectory: DirectoryProperty

    /**
     * Exact Android application project paths that may receive app-scoped
     * identity, version, name, and locale-filter values. When empty, a
     * sole detected application is selected automatically; multiple detected
     * applications are accepted only while no app-scoped value needs a target.
     * Android SDK and JVM policy remain global to compatible Android modules
     * and are not restricted by this selector. The single Android logo output
     * sink accepts at most one effective application; with no Android plugin it
     * uses an explicit [androidAppDirectory] or the legacy directory fallback.
     */
    abstract val androidApplicationProjects: ListProperty<String>

    /**
     * Legacy compatibility input for [iosPreviousSharedModuleName]. New builds
     * should use the semantically scoped iOS property instead.
     */
    @Deprecated("Use iosPreviousSharedModuleName.")
    abstract val oldSharedModuleName: Property<String>

    // --- App logo -------------------------------------------------------------

    /**
     * Foreground layer of the app logo, preferably a square PNG with an alpha channel.
     * Designed naturally — fill the canvas like an iOS marketing icon. The
     * plugin handles Android's adaptive-icon safe zone automatically by
     * centring the FG at [appLogoAndroidSafeZoneRatio] of the adaptive canvas
     * (default 66/108 ~61.1%); for iOS and Android legacy fallbacks, the FG is
     * aspect-fit into each platform's target canvas. A non-square source is contained (never
     * stretched).
     *
     * Recommended source size: 1024×1024. Minimum useful size: 432×432.
     */
    abstract val appLogoPngForeground: RegularFileProperty

    /**
     * Background layer of the app logo, preferably a square PNG. Alpha is allowed but
     * the BG should be effectively opaque — any transparency reads as white on
     * the iOS flattened output. A flat-colour PNG works fine.
     *
     * Mutually exclusive with [appLogoBackgroundColor] — set exactly one when
     * [appLogoPngForeground] is set.
     */
    abstract val appLogoPngBackground: RegularFileProperty

    /**
     * Solid-colour background, as a hex string `#RRGGBB` or `#AARRGGBB`
     * (Android convention — alpha first). Used in place of a BG PNG when the
     * background is just a flat colour. A semi-transparent colour is flattened
     * over white on both platforms (with a warning) so Android and iOS match.
     *
     * Mutually exclusive with [appLogoPngBackground] — set exactly one when
     * [appLogoPngForeground] is set.
     */
    abstract val appLogoBackgroundColor: Property<String>

    /**
     * Fraction of the Android adaptive-icon canvas (108dp) that the foreground
     * is scaled to. The FG is centred on a transparent canvas at this size, so
     * smaller values mean more padding and less chance of the launcher's mask
     * clipping corners.
     *
     * Default `66.0 / 108.0` (~0.611), matching Android's adaptive-icon safe
     * zone. Lower this (typically `0.55`–`0.6`) for tighter OEM/third-party
     * masks. Validated at configuration and execution: must be in `(0, 1]` so
     * the requested safe zone cannot crop outside the adaptive-icon canvas.
     */
    abstract val appLogoAndroidSafeZoneRatio: Property<Double>

    // --- File paths -----------------------------------------------------------

    /** Legacy relative path. Prefer [iosPbxprojFile]. */
    @Deprecated("Use the typed iosPbxprojFile property.")
    abstract val iosProjectPath: Property<String>

    /** Legacy relative path. Prefer [iosPodfileFile]. */
    @Deprecated("Use the typed iosPodfileFile property.")
    abstract val iosPodfilePath: Property<String>

    /** Legacy relative path. Prefer [iosInfoPlistFile]. */
    @Deprecated("Use the typed iosInfoPlistFile property.")
    abstract val iosInfoPlistPath: Property<String>

    /** Legacy relative path. Prefer [iosAppDirectory]. */
    @Deprecated("Use the typed iosAppDirectory property.")
    abstract val iosAppDir: Property<String>

    /**
     * Path (relative to root project) to the iOS `AppIcon.appiconset` directory.
     * Defaults to `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset`. Override
     * for non-standard Xcode group layouts.
     */
    @Deprecated("Use the typed iosAppIconDirectory property.")
    abstract val iosAppiconsetPath: Property<String>

    /**
     * Typed source `project.pbxproj` selected for explicit iOS migration. Defaults
     * to `iosApp/iosApp.xcodeproj/project.pbxproj` below the root project.
     */
    abstract val iosPbxprojFile: RegularFileProperty

    /**
     * Typed Podfile selected for an explicit shared-module migration. Defaults to
     * `iosApp/Podfile` below the root project.
     */
    abstract val iosPodfileFile: RegularFileProperty

    /**
     * Typed source XML Info.plist selected for explicit sanitization. Defaults to
     * `iosApp/iosApp/Info.plist` below the root project.
     */
    abstract val iosInfoPlistFile: RegularFileProperty

    /**
     * Typed iOS source tree used for narrowly scoped Swift migration. Defaults to
     * `iosApp` below the root project.
     */
    abstract val iosAppDirectory: DirectoryProperty

    /**
     * Typed AppIcon.appiconset installation directory. Defaults to
     * `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset` below the root project.
     */
    abstract val iosAppIconDirectory: DirectoryProperty

    // --- Capability toggles --------------------------------------------------

    /** Propagate a present [appName] to selected platform consumers. Default true. */
    abstract val propagateAppName: Property<Boolean>

    /** Propagate resolved platform bundle/application identifiers. Default true. */
    abstract val propagateBundleId: Property<Boolean>

    /** Propagate present platform release versions and build numbers. Default true. */
    abstract val propagateVersion: Property<Boolean>

    /** Add requested locale metadata to enabled platform consumers. Default true. */
    abstract val propagateLocaleList: Property<Boolean>

    /** Apply Android packaging resource filters derived from [locales]. Dangerous optimization; default false. */
    abstract val filterAndroidResources: Property<Boolean>

    /**
     * Enable explicitly invoked logo installers. Default false. Android requires
     * this toggle alone; the Apple installer additionally requires [syncIos].
     */
    abstract val propagateLogo: Property<Boolean>

    /** Enable the explicit Podfile/Swift shared-module reference migration under [syncIos]. Default false. */
    abstract val propagateSharedModule: Property<Boolean>

    /** Propagate the `android { }` SDK knobs (compileSdk/minSdk/targetSdk/ndkVersion). Default true. */
    abstract val propagateAndroidSdk: Property<Boolean>

    /**
     * Propagate the interop opt-in markers (`ExperimentalForeignApi`,
     * `ExperimentalObjCName`, `ExperimentalNativeApi`) to Kotlin/Native
     * compilations in [interopProjectPaths] (or the selected shared project).
     * Default false. Add your own via [extraOptIns].
     */
    abstract val propagateInteropOptIns: Property<Boolean>

    /**
     * Extra opt-in marker FQNs appended to the interop defaults in the same
     * explicitly selected Native compilation scope, e.g.
     * `extraOptIns.add("kotlin.experimental.ExperimentalObjCRefinement")`.
     */
    abstract val extraOptIns: ListProperty<String>

    /**
     * Exact KMP project paths eligible for Native compiler-policy propagation.
     * Empty falls back to [resolvedSharedProjectPath]; ambiguity or no selection
     * is an error when [propagateInteropOptIns] is enabled.
     */
    abstract val interopProjectPaths: ListProperty<String>

    /** Explicit opt-in for source-tree iOS migration tasks. Default false; ordinary builds never invoke them. */
    abstract val syncIos: Property<Boolean>

    /**
     * Ensure the iOS `Info.plist` has the SSOT-pointing keys the sync task relies
     * on. Effective only with [syncIos]. Default false. Run explicitly after
     * reviewing the plan/check output.
     */
    abstract val sanitizeIosProject: Property<Boolean>

    /**
     * Authorize takeover of pre-FG/BG artefacts, template collisions, and—before
     * the first ownership manifest—unowned paths the current Android installer
     * will claim. Default false. A complete enabled replacement logo is required.
     * When true,
     * `kmpSsotSyncAndroidLogo` validates/renders the replacement first, then
     * backs up, takes over, and installs in one rollback-capable operation.
     * `kmpSsotCleanupLegacyAppLogoArtifacts` remains an explicit backup/removal
     * task for manual recovery workflows.
     */
    abstract val cleanupLegacyLogoArtifacts: Property<Boolean>

    /**
     * Preview switch for explicitly invoked migration/install tasks. Text tasks
     * render unified-style diffs and binary installers list planned paths without
     * writing. Generated build outputs ignore this flag. Prefer the read-only
     * `kmpSsotPlan` task for an initial overview.
     */
    abstract val dryRun: Property<Boolean>

    /**
     * Copy a user-owned text target to `<file>.kmpssot.bak` before its first
     * rewrite and preserve first-contact iOS AppIcon files in the checksummed
     * durable `.kmpssot/recovery` area outside `build/`. Default true. Android
     * legacy takeover always uses its checksummed recovery area when
     * [cleanupLegacyLogoArtifacts] is enabled.
     */
    abstract val backupBeforeRewrite: Property<Boolean>

    // --- Platform-specific blocks --------------------------------------------

    /**
     * iOS-only options (Info.plist feature flags). See [KmpSsotIosExtension].
     * Accessed as `kmpSsot { ios { usesNonExemptEncryption = false } }`.
     *
     * Created as a child extension by `KmpSsotPlugin.apply` — Gradle can't
     * decorate an abstract property of a non-managed type, so it's exposed
     * through this getter.
     */
    val ios: KmpSsotIosExtension
        get() = (this as ExtensionAware).extensions.getByType(KmpSsotIosExtension::class.java)

    /** Configure the nested Apple-specific model. */
    fun ios(action: Action<in KmpSsotIosExtension>) {
        action.execute(ios)
    }

    /**
     * Android-only SDK options. See [KmpSsotAndroidExtension]. Accessed as
     * `kmpSsot { android { compileSdk = 36; minSdk = 26 } }`. Created as a child
     * extension by `KmpSsotPlugin.apply` for the same reason as [ios].
     */
    val android: KmpSsotAndroidExtension
        get() = (this as ExtensionAware).extensions.getByType(KmpSsotAndroidExtension::class.java)

    /** Configure the nested Android SDK/release model. */
    fun android(action: Action<in KmpSsotAndroidExtension>) {
        action.execute(android)
    }

    /**
     * Browser Kotlin/JS options. wasmJs and Node-only targets are unsupported.
     * See [KmpSsotWebExtension]. Accessed as
     * `kmpSsot { web { generateIoWorker = true } }`. Created as a child extension
     * by `KmpSsotPlugin.apply` for the same reason as [ios] / [android].
     */
    val web: KmpSsotWebExtension
        get() = (this as ExtensionAware).extensions.getByType(KmpSsotWebExtension::class.java)

    /** Configure optional browser Kotlin/JS source generation. */
    fun web(action: Action<in KmpSsotWebExtension>) {
        action.execute(web)
    }

    /**
     * Runtime build-config codegen. See [KmpSsotBuildConfigExtension]. Accessed as
     * `kmpSsot { buildConfig { enabled = true } }`. Created as a child extension by
     * `KmpSsotPlugin.apply` for the same reason as [ios] / [android] / [web].
     */
    val buildConfig: KmpSsotBuildConfigExtension
        get() = (this as ExtensionAware).extensions.getByType(KmpSsotBuildConfigExtension::class.java)

    /** Configure optional common Kotlin constants generation. */
    fun buildConfig(action: Action<in KmpSsotBuildConfigExtension>) {
        action.execute(buildConfig)
    }

    // --- Derived values (read-only) ------------------------------------------

    /**
     * versionCode: validated [versionCodeOverride] when set, otherwise derived from
     * [versionName] via `"1" + three zero-padded dot segments`. The derivation
     * requires exactly three canonical numeric components in `0..999` (see
     * [deriveVersionCode]).
     */
    val versionCode: Provider<Int>
        get() = versionCodeOverride.map(::validateVersionCode).orElse(versionName.map { deriveVersionCode(it) })

    /** Resolved Android identifier: [bundleIdBase] plus [androidApplicationIdSuffix]. */
    val androidApplicationId: Provider<String>
        get() = bundleIdBase.zip(androidApplicationIdSuffix.orElse("")) { base, suffix -> base + suffix }

    /** Resolved Apple identifier: [bundleIdBase] plus [iosBundleSuffix]. */
    val iosBundleId: Provider<String>
        get() = bundleIdBase.zip(iosBundleSuffix.orElse("")) { base, suffix -> base + suffix }

    /** Canonical, de-duplicated supported resource-locale model used by every renderer. */
    val canonicalLocales: Provider<List<String>>
        get() = locales.map(::canonicalizeLocales)

    /** Effective absolute shared-project selector, including the legacy module-name fallback. */
    val resolvedSharedProjectPath: Provider<String>
        get() = sharedProjectPath.orElse(sharedModule.map { if (it.startsWith(':')) it else ":$it" })

    /** Effective new iOS module name, with the pre-2.0 conflated property as a compatibility fallback. */
    internal val resolvedIosSharedModuleName: Provider<String>
        get() = iosSharedModuleName.orElse(sharedModule)

    /** Effective old iOS module name, with the pre-2.0 property as a compatibility fallback. */
    internal val resolvedIosPreviousSharedModuleName: Provider<String>
        get() = iosPreviousSharedModuleName.orElse(oldSharedModuleName)
}
