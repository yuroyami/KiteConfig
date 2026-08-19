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
 * The single source of truth for your app's identity.
 *
 * Apply the plugin to the **root** project and describe your app once. KiteSSOT
 * carries those values out to Android and Apple for you, so the same name,
 * version, and bundle id cannot drift apart across platforms.
 *
 * Three lines are a complete setup:
 *
 * ```kotlin
 * kiteSsot {
 *     appName = "Jetzy"
 *     version = "1.4.0"
 *     appId   = "com.example.jetzy"
 * }
 * ```
 *
 * Everything else is optional. Locales are discovered from your Compose
 * resources, the shared module is detected when there is only one, and the
 * Android application project is found the same way.
 *
 * ## How the blocks work
 *
 * Shared facts live here at the top. Anything that is genuinely Android-only or
 * Apple-only lives in [android] or [ios]. Feature blocks such as [logo] and
 * [buildConfig] switch themselves on simply by being configured, so there is no
 * second flag to remember.
 *
 * ## What runs, and when
 *
 * Applying identity happens during an ordinary build. Changing files on disk
 * never does. Logo installation and the Apple source tasks only run when you
 * invoke them by name, and [dryRun], [backups], and the conflict policy still
 * apply when you do.
 *
 * ## Wiring values into your own build logic
 *
 * Every input is a lazy Gradle `Property`, so you can hand one to another task
 * without resolving it early:
 *
 * ```kotlin
 * val minSdk = kiteSsot.android.minSdk   // Provider<Int>, still lazy
 * ```
 */
abstract class KiteSsotExtension {

    // ---------------------------------------------------------------- Identity

    /**
     * The display name users see on their home screen.
     *
     * Android receives it as the `appName` manifest placeholder. The explicit
     * Apple sync tasks use it for `PRODUCT_NAME`, `CFBundleName`, and
     * `CFBundleDisplayName`.
     */
    abstract val appName: Property<String>

    /**
     * The release version you show to users, as `x.y.z`.
     *
     * Android uses it for `versionName`, Apple for the marketing version, and
     * the build-number [scheme] derives every store counter from it.
     */
    abstract val version: Property<String>

    /**
     * Reverse-DNS base for both platform identifiers, for example
     * `com.example.jetzy`.
     *
     * KiteSSOT appends [KiteSsotAndroidExtension.idSuffix] for the Android
     * application id and [KiteSsotIosExtension.bundleIdSuffix] for the Apple
     * bundle id, so the two can differ without being declared twice.
     */
    abstract val appId: Property<String>

    /**
     * Locale tags such as `en`, `en-US`, or `sr-Latn`.
     *
     * Default: discovered from the Compose resources directory by reading
     * locale-only folders like `values-en`, `values-pt-rBR`, and
     * `values-b+sr+Latn`. Set this when you want the list pinned by hand.
     */
    abstract val locales: ListProperty<String>

    /**
     * Java and Kotlin JVM level for the whole build, for example `21`.
     *
     * This sets Java compatibility in classic Android modules and aligns
     * Kotlin JVM compile tasks. It is a build-wide policy, so project
     * selectors do not narrow it.
     */
    abstract val jvmTarget: Property<Int>

    // ---------------------------------------------------------- Build numbers

    /**
     * The one formula that turns [version] into a store build number.
     *
     * Both platforms use it. You write it once. Android takes the result as
     * `versionCode`; Apple takes the same number, as text, for
     * `CURRENT_PROJECT_VERSION`.
     *
     * Default: [VersionSchemes.DEFAULT], which packs the version as
     * `1 | major(3) | minor(3) | patch(2) | rebuild(1)`, so `1.4.0` becomes
     * `1001004000` and `1.4.1` becomes `1001004010`. That reserves ten codes
     * per version for re-uploads.
     *
     * ```kotlin
     * scheme { v -> 1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.rebuild }
     * ```
     *
     * Google Play compares codes as plain integers and remembers every one you
     * have ever uploaded, so a new formula must always produce a **larger**
     * number than your highest shipped code. Guard that with
     * [KiteSsotAndroidExtension.publishedVersionCode].
     */
    abstract val scheme: Property<VersionCodeScheme>

    /** Set the shared build-number formula. See [scheme]. */
    fun scheme(scheme: VersionCodeScheme) {
        this.scheme.set(scheme)
    }

    // ----------------------------------------------------------------- Safety

    /**
     * Make the explicit source-changing tasks report what they would do and
     * write nothing.
     *
     * Default: `false`. Override per invocation with `-Pkitessot.dryRun=true`.
     * Generated Kotlin ignores this, because it is a build input rather than a
     * change to your source tree.
     */
    abstract val dryRun: Property<Boolean>

    /**
     * Keep a first-contact recovery copy before rewriting a file.
     *
     * Default: `true`. Override with `-Pkitessot.backups=false`. Android legacy
     * icon takeover always records its own recovery data regardless of this.
     */
    abstract val backups: Property<Boolean>

    // ----------------------------------------------------------------- Blocks

    /** Where your modules live. Configure with `kiteSsot { modules { } }`. */
    val modules: KiteSsotModulesExtension
        get() = nested()

    /** Tell KiteSSOT where the shared and Android application projects are. */
    fun modules(action: Action<in KiteSsotModulesExtension>) = action.execute(modules)

    /** Which values KiteSSOT is allowed to apply. Configure with `kiteSsot { propagate { } }`. */
    val propagate: KiteSsotPropagateExtension
        get() = nested()

    /** Turn individual value categories off without deleting their configuration. */
    fun propagate(action: Action<in KiteSsotPropagateExtension>) = action.execute(propagate)

    /** Android-only settings. Configure with `kiteSsot { android { } }`. */
    val android: KiteSsotAndroidExtension
        get() = nested()

    /** Configure SDK levels, the Android id suffix, and the Play re-upload dial. */
    fun android(action: Action<in KiteSsotAndroidExtension>) = action.execute(android)

    /** Apple-only settings. Configure with `kiteSsot { ios { } }`. */
    val ios: KiteSsotIosExtension
        get() = nested()

    /** Configure the Apple bundle suffix, build number, paths, and source sync. */
    fun ios(action: Action<in KiteSsotIosExtension>) = action.execute(ios)

    /**
     * App icon installation. Configure with `kiteSsot { logo { } }`.
     *
     * Configuring this block authorizes the logo tasks. It does not run them.
     */
    val logo: KiteSsotLogoExtension
        get() = nested()

    /** Point KiteSSOT at your foreground and background art. */
    fun logo(action: Action<in KiteSsotLogoExtension>) {
        logoConfigured.set(true)
        action.execute(logo)
    }

    /**
     * Kotlin/Native interop opt-in markers. Configure with
     * `kiteSsot { nativeOptIns { } }`.
     */
    val nativeOptIns: KiteSsotNativeOptInsExtension
        get() = nested()

    /** Add opt-in markers to the selected Kotlin/Native compilations. */
    fun nativeOptIns(action: Action<in KiteSsotNativeOptInsExtension>) {
        nativeOptInsConfigured.set(true)
        action.execute(nativeOptIns)
    }

    /** Browser Kotlin/JS helpers. Configure with `kiteSsot { web { } }`. */
    val web: KiteSsotWebExtension
        get() = nested()

    /** Configure optional browser Kotlin/JS source generation. */
    fun web(action: Action<in KiteSsotWebExtension>) = action.execute(web)

    /**
     * Generated runtime constants for `commonMain`. Configure with
     * `kiteSsot { buildConfig { } }`.
     *
     * Configuring this block turns generation on.
     */
    val buildConfig: KiteSsotBuildConfigExtension
        get() = nested()

    /** Generate a Kotlin object of public runtime configuration. */
    fun buildConfig(action: Action<in KiteSsotBuildConfigExtension>) {
        buildConfigConfigured.set(true)
        action.execute(buildConfig)
    }

    private inline fun <reified T : Any> nested(): T =
        (this as ExtensionAware).extensions.getByType(T::class.java)

    // ------------------------------------------------------ Derived, read-only

    /** Android application id: [appId] plus [KiteSsotAndroidExtension.idSuffix]. */
    val androidApplicationId: Provider<String>
        get() = effectiveAppId.zip(effectiveAndroidIdSuffix.orElse("")) { base, suffix -> base + suffix }

    /** Apple bundle id: [appId] plus [KiteSsotIosExtension.bundleIdSuffix]. */
    val iosBundleId: Provider<String>
        get() = effectiveAppId.zip(effectiveIosBundleSuffix.orElse("")) { base, suffix -> base + suffix }

    /** The resolved Android `versionCode` for this build. */
    val versionCode: Provider<Int>
        get() = effectiveAndroidVersionCode

    /** Normalized, de-duplicated locale tags. */
    val canonicalLocales: Provider<List<String>>
        get() = effectiveLocales.map(::canonicalizeLocales)

    /** The selected shared KMP project path. */
    val resolvedSharedProjectPath: Provider<String>
        get() = effectiveSharedProjectPath

    // ============================================================ INTERNAL MODEL
    // The engine reads these, never the public properties, so that a value set
    // through a 3.0 block and the same value set through a deprecated 2.x
    // property resolve identically.

    /** `-Pkitessot.dryRun=true|false`, bound by the plugin. Wins over [dryRun] when present. */
    internal abstract val dryRunOverride: Property<Boolean>

    /** `-Pkitessot.backups=true|false`, bound by the plugin. Wins over [backups] when present. */
    internal abstract val backupsOverride: Property<Boolean>

    internal abstract val logoConfigured: Property<Boolean>
    internal abstract val nativeOptInsConfigured: Property<Boolean>
    internal abstract val buildConfigConfigured: Property<Boolean>

    internal val effectiveAppName: Provider<String>
        get() = appName

    internal val effectiveVersion: Provider<String>
        get() = version.orElse(versionName)

    internal val effectiveAppId: Provider<String>
        get() = appId.orElse(bundleIdBase)

    internal val effectiveJvmTarget: Provider<Int>
        get() = jvmTarget.orElse(javaVersion)

    internal val effectiveLocales: Provider<List<String>>
        get() = locales

    internal val effectiveDryRun: Provider<Boolean>
        get() = dryRunOverride.orElse(dryRun).orElse(false)

    internal val effectiveBackups: Provider<Boolean>
        get() = backupsOverride.orElse(backups).orElse(backupBeforeRewrite).orElse(true)

    // --- structure ---

    internal val effectiveSharedProjectPath: Provider<String>
        get() = modules.shared.orElse(sharedProjectPath)

    internal val effectiveComposeResources: Provider<org.gradle.api.file.Directory>
        get() = modules.composeResources.orElse(composeResourcesDirectory)

    internal val effectiveAndroidApps: Provider<List<String>>
        get() = modules.androidApps
            .map { it.ifEmpty { androidApplicationProjects.getOrElse(emptyList()) } }

    internal val effectiveAndroidAppDirectory: Provider<org.gradle.api.file.Directory>
        get() = modules.androidAppDirectory.orElse(androidAppDirectory)

    // --- apply gates ---

    internal val effectivePropagateAppName: Provider<Boolean>
        get() = propagate.appName.orElse(propagateAppName).orElse(true)

    internal val effectivePropagateBundleId: Provider<Boolean>
        get() = propagate.bundleId.orElse(propagateBundleId).orElse(true)

    internal val effectivePropagateVersion: Provider<Boolean>
        get() = propagate.version.orElse(propagateVersion).orElse(true)

    internal val effectivePropagateLocales: Provider<Boolean>
        get() = propagate.locales.orElse(propagateLocaleList).orElse(true)

    internal val effectiveApplySdkLevels: Provider<Boolean>
        get() = android.applySdkLevels.orElse(propagateAndroidSdk).orElse(true)

    internal val effectiveFilterAndroidResources: Provider<Boolean>
        get() = android.filterResourcesToLocales.orElse(filterAndroidResources).orElse(false)

    // --- version numbers ---

    internal val effectiveAndroidVersionCode: Provider<Int>
        get() = android.versionCode
            .orElse(versionCodeOverride)
            .orElse(
                effectiveVersion.zip(
                    android.rebuild.orElse(0).zip(android.scheme.orElse(schemeOrDefault)) { r, s -> r to s },
                ) { version, (rebuild, activeScheme) ->
                    computeVersionCode(activeScheme, version, rebuild, "android")
                },
            )

    /** True only when a code was pinned by hand, so no scheme was consulted. */
    internal val effectiveHasExplicitVersionCode: Provider<Boolean>
        get() = android.versionCode.orElse(versionCodeOverride).map { true }.orElse(false)

    internal val effectiveIosBuildNumber: Provider<String>
        get() = ios.buildNumber
            .orElse(iosBuildNumber)
            .orElse(
                effectiveVersion.zip(
                    ios.rebuild.orElse(0).zip(ios.scheme.orElse(schemeOrDefault)) { r, s -> r to s },
                ) { version, (rebuild, activeScheme) ->
                    computeVersionCode(activeScheme, version, rebuild, "ios").toString()
                },
            )

    internal val effectiveIosMarketingVersion: Provider<String>
        get() = ios.marketingVersion.orElse(iosMarketingVersion).orElse(effectiveVersion)

    private val schemeOrDefault: Provider<VersionCodeScheme>
        get() = scheme.orElse(VersionSchemes.DEFAULT)

    // --- ios ---

    internal val effectiveIosBundleSuffix: Provider<String>
        get() = ios.bundleIdSuffix.orElse(iosBundleSuffix)

    internal val effectiveIosPbxproj: Provider<org.gradle.api.file.RegularFile>
        get() = ios.pbxproj.orElse(iosPbxprojFile)

    internal val effectiveIosPodfile: Provider<org.gradle.api.file.RegularFile>
        get() = ios.podfile.orElse(iosPodfileFile)

    internal val effectiveIosInfoPlist: Provider<org.gradle.api.file.RegularFile>
        get() = ios.infoPlist.orElse(iosInfoPlistFile)

    internal val effectiveIosAppDirectory: Provider<org.gradle.api.file.Directory>
        get() = ios.appDirectory.orElse(iosAppDirectory)

    internal val effectiveIosAppIconDirectory: Provider<org.gradle.api.file.Directory>
        get() = ios.appIconDirectory.orElse(iosAppIconDirectory)

    internal val effectiveSyncIos: Provider<Boolean>
        get() = ios.sync.configured.orElse(false)
            .zip(syncIos.orElse(false)) { configured, legacy -> configured || legacy }
            .zip(ios.sync.enabled.orElse(true)) { on, enabled -> on && enabled }

    internal val effectiveSanitizeIosProject: Provider<Boolean>
        get() = ios.sync.sanitizePlist.orElse(sanitizeIosProject).orElse(false)

    internal val effectiveIosTargets: Provider<List<String>>
        get() = ios.sync.targets

    internal val effectivePlistConflictPolicy: Provider<PlistConflictPolicy>
        get() = ios.sync.onConflict.orElse(PlistConflictPolicy.FAIL)

    internal val effectiveNonExemptEncryption: Provider<Boolean>
        get() = ios.sync.nonExemptEncryption

    internal val effectiveProMotion: Provider<Boolean>
        get() = ios.sync.proMotion

    internal val effectiveIosSharedModuleName: Provider<String>
        get() = ios.sync.newSharedModuleName.orElse(iosSharedModuleName)

    internal val effectiveIosPreviousSharedModuleName: Provider<String>
        get() = ios.sync.previousSharedModuleName.orElse(iosPreviousSharedModuleName)

    internal val effectivePropagateSharedModule: Provider<Boolean>
        get() = ios.sync.newSharedModuleName.map { true }.orElse(false)
            .zip(propagateSharedModule.orElse(false)) { block, legacy -> block || legacy }

    // --- logo ---

    internal val effectivePropagateLogo: Provider<Boolean>
        get() = logoConfigured.orElse(false)
            .zip(propagateLogo.orElse(false)) { configured, legacy -> configured || legacy }
            .zip(logo.enabled.orElse(true)) { on, enabled -> on && enabled }

    internal val effectiveLogoForeground: Provider<org.gradle.api.file.RegularFile>
        get() = logo.foreground.orElse(appLogoPngForeground)

    internal val effectiveLogoBackground: Provider<org.gradle.api.file.RegularFile>
        get() = logo.background.orElse(appLogoPngBackground)

    internal val effectiveLogoBackgroundColor: Provider<String>
        get() = logo.backgroundColor.orElse(appLogoBackgroundColor)

    internal val effectiveLogoSafeZone: Provider<Double>
        get() = logo.androidSafeZone.orElse(appLogoAndroidSafeZoneRatio).orElse(DEFAULT_ANDROID_SAFE_ZONE)

    internal val effectiveTakeOverLegacyIcons: Provider<Boolean>
        get() = effectivePropagateLogo.zip(
            logo.takeOverLegacyIcons.orElse(cleanupLegacyLogoArtifacts).orElse(false),
        ) { logoOn, takeOver -> logoOn && takeOver }

    // --- native opt-ins ---

    internal val effectiveNativeOptInsEnabled: Provider<Boolean>
        get() = nativeOptInsConfigured.orElse(false)
            .zip(propagateInteropOptIns.orElse(false)) { configured, legacy -> configured || legacy }
            .zip(nativeOptIns.enabled.orElse(true)) { on, enabled -> on && enabled }

    internal val effectiveNativeOptInBuiltIns: Provider<Boolean>
        get() = nativeOptIns.builtIns.orElse(true)

    internal val effectiveAndroidIdSuffix: Provider<String>
        get() = android.idSuffix.orElse(androidApplicationIdSuffix)

    internal val effectiveNativeOptInMarkers: Provider<List<String>>
        get() = nativeOptIns.markers.map { it.ifEmpty { extraOptIns.getOrElse(emptyList()) } }

    internal val effectiveNativeOptInProjects: Provider<List<String>>
        get() = nativeOptIns.projects.map { it.ifEmpty { interopProjectPaths.getOrElse(emptyList()) } }

    // --- web ---

    internal val effectiveIoWorkerEnabled: Provider<Boolean>
        get() = web.ioWorker.configured.orElse(false)
            .zip(web.ioWorker.enabled.orElse(true)) { configured, enabled -> configured && enabled }

    internal val effectiveIoWorkerTargets: Provider<List<String>>
        get() = web.ioWorker.targets

    internal val effectiveIoWorkerProjects: Provider<List<String>>
        get() = web.ioWorker.projects

    internal val effectiveIoWorkerPackage: Provider<String>
        get() = web.ioWorker.packageName.orElse(DEFAULT_GENERATED_PACKAGE)

    // --- build config ---

    internal val effectiveBuildConfigEnabled: Provider<Boolean>
        get() = buildConfigConfigured.orElse(false)
            .zip(buildConfig.enabled.orElse(true)) { configured, enabled -> configured && enabled }

    internal companion object {
        internal const val DEFAULT_ANDROID_SAFE_ZONE: Double = 66.0 / 108.0
        internal const val DEFAULT_GENERATED_PACKAGE: String = "kitessot.generated"
    }

    // ========================================================== DEPRECATED 2.x
    // Kept so a 2.x build keeps working while you migrate. Each one feeds the
    // same internal model as its 3.0 replacement. They are removed in 4.0.

    /** Use [version]. */
    @Deprecated("Renamed to version.", ReplaceWith("version"))
    abstract val versionName: Property<String>

    /** Use [appId]. */
    @Deprecated("Renamed to appId.", ReplaceWith("appId"))
    abstract val bundleIdBase: Property<String>

    /** Use [jvmTarget]. */
    @Deprecated("Renamed to jvmTarget.", ReplaceWith("jvmTarget"))
    abstract val javaVersion: Property<Int>

    /** Use `android { versionCode = ... }`. */
    @Deprecated("Moved to android { versionCode }.", ReplaceWith("android.versionCode"))
    abstract val versionCodeOverride: Property<Int>

    /** Use `ios { marketingVersion = ... }`. */
    @Deprecated("Moved to ios { marketingVersion }.", ReplaceWith("ios.marketingVersion"))
    abstract val iosMarketingVersion: Property<String>

    /** Use `ios { buildNumber = ... }`. */
    @Deprecated("Moved to ios { buildNumber }.", ReplaceWith("ios.buildNumber"))
    abstract val iosBuildNumber: Property<String>

    /** Use `ios { bundleIdSuffix = ... }`. */
    @Deprecated("Moved to ios { bundleIdSuffix }.", ReplaceWith("ios.bundleIdSuffix"))
    abstract val iosBundleSuffix: Property<String>

    /** Use `android { idSuffix = ... }`. */
    @Deprecated("Moved to android { idSuffix }.", ReplaceWith("android.idSuffix"))
    abstract val androidApplicationIdSuffix: Property<String>

    /** Use `modules { shared = ... }`. */
    @Deprecated("Moved to modules { shared }.", ReplaceWith("modules.shared"))
    abstract val sharedProjectPath: Property<String>

    /** Use `modules { androidApps(...) }`. */
    @Deprecated("Moved to modules { androidApps }.", ReplaceWith("modules.androidApps"))
    abstract val androidApplicationProjects: ListProperty<String>

    /** Use `modules { androidAppDirectory = ... }`. */
    @Deprecated("Moved to modules { androidAppDirectory }.", ReplaceWith("modules.androidAppDirectory"))
    abstract val androidAppDirectory: DirectoryProperty

    /** Use `modules { composeResources = ... }`. */
    @Deprecated("Moved to modules { composeResources }.", ReplaceWith("modules.composeResources"))
    abstract val composeResourcesDirectory: DirectoryProperty

    /** Use `ios { sync { renameSharedModule(from, to) } }`. */
    @Deprecated("Moved to ios { sync { renameSharedModule() } }.")
    abstract val iosSharedModuleName: Property<String>

    /** Use `ios { sync { renameSharedModule(from, to) } }`. */
    @Deprecated("Moved to ios { sync { renameSharedModule() } }.")
    abstract val iosPreviousSharedModuleName: Property<String>

    /** Use `ios { pbxproj = ... }`. */
    @Deprecated("Moved to ios { pbxproj }.", ReplaceWith("ios.pbxproj"))
    abstract val iosPbxprojFile: RegularFileProperty

    /** Use `ios { podfile = ... }`. */
    @Deprecated("Moved to ios { podfile }.", ReplaceWith("ios.podfile"))
    abstract val iosPodfileFile: RegularFileProperty

    /** Use `ios { infoPlist = ... }`. */
    @Deprecated("Moved to ios { infoPlist }.", ReplaceWith("ios.infoPlist"))
    abstract val iosInfoPlistFile: RegularFileProperty

    /** Use `ios { appDirectory = ... }`. */
    @Deprecated("Moved to ios { appDirectory }.", ReplaceWith("ios.appDirectory"))
    abstract val iosAppDirectory: DirectoryProperty

    /** Use `ios { appIconDirectory = ... }`. */
    @Deprecated("Moved to ios { appIconDirectory }.", ReplaceWith("ios.appIconDirectory"))
    abstract val iosAppIconDirectory: DirectoryProperty

    /** Use `logo { foreground = ... }`. */
    @Deprecated("Moved to logo { foreground }.", ReplaceWith("logo.foreground"))
    abstract val appLogoPngForeground: RegularFileProperty

    /** Use `logo { background = ... }`. */
    @Deprecated("Moved to logo { background }.", ReplaceWith("logo.background"))
    abstract val appLogoPngBackground: RegularFileProperty

    /** Use `logo { backgroundColor = ... }`. */
    @Deprecated("Moved to logo { backgroundColor }.", ReplaceWith("logo.backgroundColor"))
    abstract val appLogoBackgroundColor: Property<String>

    /** Use `logo { androidSafeZone = ... }`. */
    @Deprecated("Moved to logo { androidSafeZone }.", ReplaceWith("logo.androidSafeZone"))
    abstract val appLogoAndroidSafeZoneRatio: Property<Double>

    /** Use `logo { takeOverLegacyIcons = ... }`. */
    @Deprecated("Moved to logo { takeOverLegacyIcons }.", ReplaceWith("logo.takeOverLegacyIcons"))
    abstract val cleanupLegacyLogoArtifacts: Property<Boolean>

    /** Use `propagate { appName = ... }`. */
    @Deprecated("Moved to propagate { appName }.", ReplaceWith("propagate.appName"))
    abstract val propagateAppName: Property<Boolean>

    /** Use `propagate { bundleId = ... }`. */
    @Deprecated("Moved to propagate { bundleId }.", ReplaceWith("propagate.bundleId"))
    abstract val propagateBundleId: Property<Boolean>

    /** Use `propagate { version = ... }`. */
    @Deprecated("Moved to propagate { version }.", ReplaceWith("propagate.version"))
    abstract val propagateVersion: Property<Boolean>

    /** Use `propagate { locales = ... }`. */
    @Deprecated("Moved to propagate { locales }.", ReplaceWith("propagate.locales"))
    abstract val propagateLocaleList: Property<Boolean>

    /** Use `android { applySdkLevels = ... }`. */
    @Deprecated("Moved to android { applySdkLevels }.", ReplaceWith("android.applySdkLevels"))
    abstract val propagateAndroidSdk: Property<Boolean>

    /** Use `android { filterResourcesToLocales = ... }`. */
    @Deprecated(
        "Moved to android { filterResourcesToLocales }.",
        ReplaceWith("android.filterResourcesToLocales"),
    )
    abstract val filterAndroidResources: Property<Boolean>

    /** Configure `logo { }` instead. Configuring the block is the opt-in. */
    @Deprecated("Configure the logo { } block instead.")
    abstract val propagateLogo: Property<Boolean>

    /** Configure `ios { sync { } }` instead. Configuring the block is the opt-in. */
    @Deprecated("Configure the ios { sync { } } block instead.")
    abstract val syncIos: Property<Boolean>

    /** Use `ios { sync { sanitizePlist = ... } }`. */
    @Deprecated("Moved to ios { sync { sanitizePlist } }.")
    abstract val sanitizeIosProject: Property<Boolean>

    /** Call `ios { sync { renameSharedModule(from, to) } }` instead. */
    @Deprecated("Call ios { sync { renameSharedModule() } } instead.")
    abstract val propagateSharedModule: Property<Boolean>

    /** Configure `nativeOptIns { }` instead. Configuring the block is the opt-in. */
    @Deprecated("Configure the nativeOptIns { } block instead.")
    abstract val propagateInteropOptIns: Property<Boolean>

    /** Use `nativeOptIns { add(...) }`. */
    @Deprecated("Moved to nativeOptIns { add() }.", ReplaceWith("nativeOptIns.markers"))
    abstract val extraOptIns: ListProperty<String>

    /** Use `nativeOptIns { projects(...) }`. */
    @Deprecated("Moved to nativeOptIns { projects() }.", ReplaceWith("nativeOptIns.projects"))
    abstract val interopProjectPaths: ListProperty<String>

    /** Use [backups]. */
    @Deprecated("Renamed to backups.", ReplaceWith("backups"))
    abstract val backupBeforeRewrite: Property<Boolean>
}
