@file:Suppress("DEPRECATION")

package io.github.yuroyami.kitessot

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * Root configuration for KiteSSOT.
 *
 * Apply the plugin and configure this model in the root project. Set only the
 * values that KiteSSOT should own. Most unset scalar values leave existing
 * project configuration alone. [iosMarketingVersion] can inherit [versionName],
 * [locales] can be discovered, and enabled features may require related inputs.
 *
 * Present identity, locale, and Android SDK values are applied by default.
 * Source changes, logo installation, Native compiler opt-ins, and Android
 * resource filtering require explicit switches.
 *
 * A small setup looks like this:
 *
 * ```kotlin
 * kiteSsot {
 *     appName = "Jetzy"
 *     versionName = "1.4.0"
 *     bundleIdBase = "com.example.jetzy"
 *     sharedProjectPath = ":shared"
 *
 *     android {
 *         compileSdk = 36
 *         minSdk = 26
 *         targetSdk = 36
 *     }
 * }
 * ```
 *
 * Scalar and list inputs use Gradle `Property` and `ListProperty`. Typed paths
 * use `DirectoryProperty` and `RegularFileProperty`. Build logic can keep a
 * value lazy, for example `val minSdk = ssot.android.minSdk`, and pass that
 * provider to another Gradle property. The derived read-only providers are
 * [versionCode], [androidApplicationId], [iosBundleId], [canonicalLocales], and
 * [resolvedSharedProjectPath].
 */
abstract class KiteSsotExtension {

    // --- Identity -------------------------------------------------------------

    /**
     * Optional app display name.
     *
     * Android receives the `appName` manifest placeholder. Explicit Apple sync
     * can use it for `PRODUCT_NAME`, `CFBundleName`, and `CFBundleDisplayName`.
     */
    abstract val appName: Property<String>

    /**
     * Optional release version shown to users.
     *
     * Android uses it as `versionName`. [iosMarketingVersion] uses it by
     * default. A plain `x.y.z` value can also produce [versionCode].
     */
    abstract val versionName: Property<String>

    /**
     * Optional reverse-DNS base for Android and Apple identifiers.
     *
     * KiteSSOT appends [androidApplicationIdSuffix] and [iosBundleSuffix] to
     * produce the platform IDs.
     */
    abstract val bundleIdBase: Property<String>

    /**
     * Optional Apple marketing version.
     *
     * The default is [versionName]. Explicit Apple sync writes it as
     * `MARKETING_VERSION`.
     */
    abstract val iosMarketingVersion: Property<String>

    /**
     * Optional Apple build number for `CURRENT_PROJECT_VERSION`.
     *
     * It is independent from Android's [versionCodeOverride].
     */
    abstract val iosBuildNumber: Property<String>

    /** Optional suffix appended to [bundleIdBase] for the Apple bundle ID. */
    abstract val iosBundleSuffix: Property<String>

    /** Optional suffix appended to [bundleIdBase] for the Android application ID. */
    abstract val androidApplicationIdSuffix: Property<String>

    /**
     * Optional explicit Android `versionCode`.
     *
     * Use it for prerelease names or a custom numbering scheme. When omitted,
     * KiteSSOT derives the code from a plain `x.y.z` [versionName].
     */
    abstract val versionCodeOverride: Property<Int>

    // --- Shared toolchain (cross-platform) -----------------------------------

    /**
     * Optional Java and Kotlin JVM level.
     *
     * It configures Java compatibility in classic Android modules and aligns
     * compatible Kotlin JVM compile tasks across the build. Project selectors
     * do not limit this shared toolchain policy.
     */
    abstract val javaVersion: Property<Int>

    // --- Localization ---------------------------------------------------------

    /**
     * Optional locale tags such as `en`, `en-US`, and `sr-Latn`.
     *
     * When omitted, KiteSSOT can discover exact locale-only directories such as
     * `values-en`, `values-pt-rBR`, and `values-b+sr+Latn` from the selected
     * Compose resources directory.
     */
    abstract val locales: ListProperty<String>

    // --- Module structure ----------------------------------------------------

    /** Legacy shared module name. Prefer the specific selectors below. */
    @Deprecated(
        "Conflates a Gradle project, directory, and Swift module; use sharedProjectPath, " +
            "composeResourcesDirectory, and iosSharedModuleName.",
    )
    abstract val sharedModule: Property<String>

    /**
     * Absolute Gradle path of the KMP project that owns generated `commonMain`
     * source, for example `:shared`.
     */
    abstract val sharedProjectPath: Property<String>

    /**
     * New CocoaPods and Swift module name for an explicit reference migration.
     * Set it with [iosPreviousSharedModuleName].
     */
    abstract val iosSharedModuleName: Property<String>

    /**
     * Previous CocoaPods and Swift module name for an explicit reference
     * migration. Set it with [iosSharedModuleName].
     */
    abstract val iosPreviousSharedModuleName: Property<String>

    /** Optional Compose resources directory used for locale discovery. */
    abstract val composeResourcesDirectory: DirectoryProperty

    /** Legacy Android app directory. Prefer [androidApplicationProjects]. */
    @Deprecated("Use androidApplicationProjects or the typed androidAppDirectory property.")
    abstract val androidAppModule: Property<String>

    /**
     * Optional Android app directory.
     *
     * KiteSSOT normally finds it from the selected Android application project.
     */
    abstract val androidAppDirectory: DirectoryProperty

    /**
     * Exact Android application project paths for app identity, versions, name,
     * locale filters, and logo output.
     *
     * Leave this empty when the build has one clear application. Android SDK
     * and JVM values still apply to every compatible module.
     */
    abstract val androidApplicationProjects: ListProperty<String>

    /** Legacy input for [iosPreviousSharedModuleName]. */
    @Deprecated("Use iosPreviousSharedModuleName.")
    abstract val oldSharedModuleName: Property<String>

    // --- App logo -------------------------------------------------------------

    /**
     * Foreground PNG used by both logo installers.
     *
     * A square 1024 by 1024 image with transparency works best. KiteSSOT keeps
     * its aspect ratio and applies Android's adaptive icon safe zone.
     */
    abstract val appLogoPngForeground: RegularFileProperty

    /**
     * Background PNG used by both logo installers.
     *
     * Set this or [appLogoBackgroundColor], never both.
     */
    abstract val appLogoPngBackground: RegularFileProperty

    /**
     * Solid logo background in `#RRGGBB` or `#AARRGGBB` form.
     *
     * Set this or [appLogoPngBackground], never both.
     */
    abstract val appLogoBackgroundColor: Property<String>

    /**
     * Fraction of Android's adaptive icon canvas used by the foreground.
     *
     * The default is `66.0 / 108.0`. A smaller value adds more padding. Valid
     * values are greater than zero and at most one.
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

    /** Legacy Apple AppIcon directory. Prefer [iosAppIconDirectory]. */
    @Deprecated("Use the typed iosAppIconDirectory property.")
    abstract val iosAppiconsetPath: Property<String>

    /**
     * Xcode `project.pbxproj` used by explicit Apple tasks.
     *
     * The default is `iosApp/iosApp.xcodeproj/project.pbxproj`.
     */
    abstract val iosPbxprojFile: RegularFileProperty

    /**
     * Podfile used by explicit shared-module migration.
     *
     * The default is `iosApp/Podfile`.
     */
    abstract val iosPodfileFile: RegularFileProperty

    /**
     * Source XML Info.plist used by explicit sanitization.
     *
     * The default is `iosApp/iosApp/Info.plist`.
     */
    abstract val iosInfoPlistFile: RegularFileProperty

    /**
     * Apple source tree searched by explicit Swift import migration.
     *
     * The default is `iosApp`.
     */
    abstract val iosAppDirectory: DirectoryProperty

    /**
     * Destination for Apple AppIcon installation.
     *
     * The default is `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset`.
     */
    abstract val iosAppIconDirectory: DirectoryProperty

    // --- Capability toggles --------------------------------------------------

    /** Apply a present [appName]. Default is `true`. */
    abstract val propagateAppName: Property<Boolean>

    /** Apply resolved platform identifiers. Default is `true`. */
    abstract val propagateBundleId: Property<Boolean>

    /** Apply present platform versions and build numbers. Default is `true`. */
    abstract val propagateVersion: Property<Boolean>

    /** Add locale metadata to enabled consumers. Default is `true`. */
    abstract val propagateLocaleList: Property<Boolean>

    /**
     * Replace the selected Android app's locale filters with [locales].
     *
     * This changes packaged resources. Default is `false`.
     */
    abstract val filterAndroidResources: Property<Boolean>

    /**
     * Enable explicitly invoked logo installers. Default is `false`.
     *
     * Set [appLogoPngForeground] and exactly one background. Apple installation
     * also requires [syncIos].
     */
    abstract val propagateLogo: Property<Boolean>

    /**
     * Enable explicit Podfile and Swift shared-module migration under [syncIos].
     * Default is `false`.
     */
    abstract val propagateSharedModule: Property<Boolean>

    /** Apply values from `android {}` to compatible modules. Default is `true`. */
    abstract val propagateAndroidSdk: Property<Boolean>

    /**
     * Add KiteSSOT's built-in interop markers to selected Native compilations.
     *
     * Default is `false`. Use [extraOptIns] for additional markers.
     */
    abstract val propagateInteropOptIns: Property<Boolean>

    /**
     * Additional fully qualified opt-in markers for the selected Native scope.
     */
    abstract val extraOptIns: ListProperty<String>

    /**
     * Exact KMP project paths for Native opt-ins.
     *
     * An empty list uses [resolvedSharedProjectPath].
     */
    abstract val interopProjectPaths: ListProperty<String>

    /**
     * Authorize explicitly invoked Apple source tasks. Default is `false`.
     *
     * Ordinary builds never invoke these tasks.
     */
    abstract val syncIos: Property<Boolean>

    /**
     * Maintain KiteSSOT references in a source XML Info.plist.
     *
     * Default is `false` and [syncIos] is also required.
     */
    abstract val sanitizeIosProject: Property<Boolean>

    /**
     * Allow Android logo installation to take over known legacy icon files.
     *
     * Default is `false`. A complete enabled replacement logo is required.
     * KiteSSOT backs up candidates before replacing them.
     */
    abstract val cleanupLegacyLogoArtifacts: Property<Boolean>

    /**
     * Preview explicitly invoked source-changing tasks. Default is `false`.
     *
     * Generated Kotlin source ignores this flag because it is a build input.
     */
    abstract val dryRun: Property<Boolean>

    /**
     * Keep first-contact recovery copies for source rewrites and Apple icons.
     *
     * Default is `true`. Android legacy takeover always keeps its own recovery
     * record when [cleanupLegacyLogoArtifacts] is enabled.
     */
    abstract val backupBeforeRewrite: Property<Boolean>

    // --- Platform-specific blocks --------------------------------------------

    /** Apple-only options. Configure them with `kiteSsot { ios { ... } }`. */
    val ios: KiteSsotIosExtension
        get() = (this as ExtensionAware).extensions.getByType(KiteSsotIosExtension::class.java)

    /** Configure the nested Apple-specific model. */
    fun ios(action: Action<in KiteSsotIosExtension>) {
        action.execute(ios)
    }

    /** Android SDK options. Configure them with `kiteSsot { android { ... } }`. */
    val android: KiteSsotAndroidExtension
        get() = (this as ExtensionAware).extensions.getByType(KiteSsotAndroidExtension::class.java)

    /** Configure the nested Android SDK/release model. */
    fun android(action: Action<in KiteSsotAndroidExtension>) {
        action.execute(android)
    }

    /** Browser Kotlin/JS options. Node.js and wasm targets are unsupported. */
    val web: KiteSsotWebExtension
        get() = (this as ExtensionAware).extensions.getByType(KiteSsotWebExtension::class.java)

    /** Configure optional browser Kotlin/JS source generation. */
    fun web(action: Action<in KiteSsotWebExtension>) {
        action.execute(web)
    }

    /** Generated runtime constants for the selected KMP project's `commonMain`. */
    val buildConfig: KiteSsotBuildConfigExtension
        get() = (this as ExtensionAware).extensions.getByType(KiteSsotBuildConfigExtension::class.java)

    /** Configure optional common Kotlin constants generation. */
    fun buildConfig(action: Action<in KiteSsotBuildConfigExtension>) {
        action.execute(buildConfig)
    }

    // --- Derived values (read-only) ------------------------------------------

    /**
     * Resolved Android version code.
     *
     * This uses [versionCodeOverride] when present. Otherwise it derives a code
     * from a plain three-part [versionName].
     */
    val versionCode: Provider<Int>
        get() = versionCodeOverride.map(::validateVersionCode).orElse(versionName.map { deriveVersionCode(it) })

    /** Read-only Android ID: [bundleIdBase] plus [androidApplicationIdSuffix]. */
    val androidApplicationId: Provider<String>
        get() = bundleIdBase.zip(androidApplicationIdSuffix.orElse("")) { base, suffix -> base + suffix }

    /** Read-only Apple ID: [bundleIdBase] plus [iosBundleSuffix]. */
    val iosBundleId: Provider<String>
        get() = bundleIdBase.zip(iosBundleSuffix.orElse("")) { base, suffix -> base + suffix }

    /** Read-only normalized and de-duplicated locale list. */
    val canonicalLocales: Provider<List<String>>
        get() = locales.map(::canonicalizeLocales)

    /** Read-only shared project path, including the legacy fallback. */
    val resolvedSharedProjectPath: Provider<String>
        get() = sharedProjectPath.orElse(sharedModule.map { if (it.startsWith(':')) it else ":$it" })

    /** Effective new iOS module name, with the pre-2.0 conflated property as a compatibility fallback. */
    internal val resolvedIosSharedModuleName: Provider<String>
        get() = iosSharedModuleName.orElse(sharedModule)

    /** Effective old iOS module name, with the pre-2.0 property as a compatibility fallback. */
    internal val resolvedIosPreviousSharedModuleName: Provider<String>
        get() = iosPreviousSharedModuleName.orElse(oldSharedModuleName)
}
