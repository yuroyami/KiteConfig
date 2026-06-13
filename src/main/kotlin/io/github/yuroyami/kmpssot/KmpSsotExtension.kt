package io.github.yuroyami.kmpssot

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * DSL for the kmp-ssot plugin. Applied to the **root project** only.
 *
 * Every identity field is optional. A field gets propagated iff (a) its
 * `propagate*` toggle is true (default true) AND (b) the value is set.
 * Leave a field unset to opt out of that piece of propagation completely.
 *
 * App logo: set [appLogoPngForeground] and exactly one of [appLogoPngBackground]
 * / [appLogoBackgroundColor] to enable logo propagation, or leave all unset.
 * Setting only one fails configuration.
 */
abstract class KmpSsotExtension {

    // --- Identity -------------------------------------------------------------

    abstract val appName: Property<String>
    abstract val versionName: Property<String>
    abstract val bundleIdBase: Property<String>

    /** Suffix appended to bundleIdBase for the iOS bundle id. Null = no suffix. */
    abstract val iosBundleSuffix: Property<String>

    /** Suffix appended to bundleIdBase for the Android applicationId. Null = no suffix. */
    abstract val androidApplicationIdSuffix: Property<String>

    /**
     * Explicit Android `versionCode`. When set, it is used verbatim and the
     * derivation from [versionName] is bypassed. Set this when [versionName] is
     * not a plain numeric `x.y.z` (pre-release suffixes, 4+ segments, segments
     * > 999), or when you simply want full control over the integer.
     */
    abstract val versionCodeOverride: Property<Int>

    // --- Shared toolchain (cross-platform) -----------------------------------

    /**
     * Java source/target compatibility for every Android module. **No default** —
     * applied only when you set it explicitly, so the plugin never silently
     * overrides a module's own `compileOptions`.
     */
    abstract val javaVersion: Property<Int>

    // --- Localization ---------------------------------------------------------

    /**
     * Locales supported by the app. Defaults to auto-detection from
     * `{sharedModule}/src/commonMain/composeResources/values-*` directories.
     */
    abstract val locales: ListProperty<String>

    // --- Module structure ----------------------------------------------------

    /** KMP shared module directory name (e.g. "shared", "composeApp"). REQUIRED. */
    abstract val sharedModule: Property<String>

    /** Android application module directory name. Defaults to "androidApp". */
    abstract val androidAppModule: Property<String>

    /**
     * Previous shared-module name, for the rename SSOT. Normally auto-detected
     * from the Podfile's `pod 'X', :path => '../X'` line; set this explicitly
     * when the pod name differs from the directory name or uses a nested path,
     * where auto-detection can't safely guess.
     */
    abstract val oldSharedModuleName: Property<String>

    // --- App logo -------------------------------------------------------------

    /**
     * Foreground layer of the app logo, as a square PNG with an alpha channel.
     * Designed naturally — fill the canvas like an iOS marketing icon. The
     * plugin handles Android's adaptive-icon safe zone automatically by
     * centring the FG at [appLogoAndroidSafeZoneRatio] of the adaptive canvas
     * (default 66/108 ~61.1%); for iOS and Android legacy fallbacks, the FG is
     * rendered at its native size. A non-square source is aspect-fit (never
     * stretched).
     *
     * Recommended source size: 1024×1024. Minimum useful size: 432×432.
     */
    abstract val appLogoPngForeground: RegularFileProperty

    /**
     * Background layer of the app logo, as a square PNG. Alpha is allowed but
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
     * masks. Validated at configuration: must be in `(0, 2]`.
     */
    abstract val appLogoAndroidSafeZoneRatio: Property<Double>

    // --- File paths -----------------------------------------------------------

    /** Path (relative to root project) to the iOS Xcode project file. */
    abstract val iosProjectPath: Property<String>

    /** Path (relative to root project) to the iOS Podfile. */
    abstract val iosPodfilePath: Property<String>

    /** Path (relative to root project) to the iOS Info.plist. */
    abstract val iosInfoPlistPath: Property<String>

    /** Directory (relative to root project) of the iOS app, scanned for Swift files. Defaults to "iosApp". */
    abstract val iosAppDir: Property<String>

    /**
     * Path (relative to root project) to the iOS `AppIcon.appiconset` directory.
     * Defaults to `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset`. Override
     * for non-standard Xcode group layouts.
     */
    abstract val iosAppiconsetPath: Property<String>

    // --- Toggles (all default true unless noted) -----------------------------

    abstract val propagateAppName: Property<Boolean>
    abstract val propagateBundleId: Property<Boolean>
    abstract val propagateVersion: Property<Boolean>
    abstract val propagateLocaleList: Property<Boolean>
    abstract val propagateLogo: Property<Boolean>
    abstract val propagateSharedModule: Property<Boolean>

    /** Propagate the `android { }` SDK knobs (compileSdk/minSdk/targetSdk/ndkVersion). Default true. */
    abstract val propagateAndroidSdk: Property<Boolean>

    /** Master switch for the iOS pbxproj rewrite task. If false, no iOS sync happens at all. */
    abstract val syncIos: Property<Boolean>

    /**
     * Ensure the iOS `Info.plist` has the SSOT-pointing keys the sync task relies on.
     * Append-only for those string keys: never overwrites existing values. Default true.
     */
    abstract val sanitizeIosProject: Property<Boolean>

    /**
     * Delete logo artefacts left behind by pre-FG/BG plugin versions. Default
     * false — opt-in migration helper. When true, `cleanupLegacyAppLogoArtifacts`
     * runs as a dependency of `syncAndroidLogo`. Always registered for one-shot
     * manual invocation too.
     */
    abstract val cleanupLegacyLogoArtifacts: Property<Boolean>

    /**
     * Preview mode. When true, every file-rewriting task logs the change it
     * *would* make and writes nothing. Default false.
     */
    abstract val dryRun: Property<Boolean>

    /**
     * Copy a user-owned file (pbxproj, Info.plist, Podfile, Swift) to
     * `<file>.kmpssot.bak` before the first rewrite, so an unexpected edit is
     * recoverable. Default true. Generated launcher icons are plugin-owned and
     * never backed up.
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

    fun android(action: Action<in KmpSsotAndroidExtension>) {
        action.execute(android)
    }

    // --- Derived values (read-only) ------------------------------------------

    /**
     * versionCode: [versionCodeOverride] when set, otherwise derived from
     * [versionName] via `"1" + zero-padded dot segments`. The derivation fails
     * fast with a clear message on a non-numeric or 4+-segment version (see
     * [deriveVersionCode]).
     */
    val versionCode: Provider<Int>
        get() = versionCodeOverride.orElse(versionName.map { deriveVersionCode(it) })

    val androidApplicationId: Provider<String>
        get() = bundleIdBase.zip(androidApplicationIdSuffix.orElse("")) { base, suffix -> base + suffix }

    val iosBundleId: Provider<String>
        get() = bundleIdBase.zip(iosBundleSuffix.orElse("")) { base, suffix -> base + suffix }
}
