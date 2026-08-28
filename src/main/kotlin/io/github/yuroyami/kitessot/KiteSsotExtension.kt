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
 * ## Where each value lands
 *
 * | You write | Android receives | Apple receives |
 * |---|---|---|
 * | [appName] | `appName` manifest placeholder | `PRODUCT_NAME`, `CFBundleName`, `CFBundleDisplayName` |
 * | [version] | `versionName` | `MARKETING_VERSION` |
 * | [appId] | `applicationId` + [KiteSsotAndroidExtension.idSuffix] | bundle id + [KiteSsotIosExtension.bundleIdSuffix] |
 * | [scheme] | `versionCode` | `CURRENT_PROJECT_VERSION` |
 * | [locales] | resource locale filter | `knownRegions` |
 * | [jvmTarget] | Java + Kotlin JVM level | not applicable |
 *
 * Android values are applied during an ordinary build. Apple values are written
 * only by the explicit sync tasks, never as a side effect of building.
 *
 * ## How the blocks work
 *
 * Shared facts live here at the top. Anything that is genuinely Android-only or
 * Apple-only lives in [android] or [ios]. Feature blocks such as [logo] and
 * [buildConfig] switch themselves on simply by being configured, so there is no
 * second flag to remember.
 *
 * | Block | Turns on by | Effect |
 * |---|---|---|
 * | [android], [ios], [propagate], [modules] | always present | configuration only |
 * | [buildConfig] | being configured | generates Kotlin into `build/` |
 * | [desktop] | being configured | applies identity to Compose Desktop packaging |
 * | [optIns], [web] | being configured | affects compilation |
 * | [logo], `ios { sync { } }` | being configured | **authorizes** source-writing tasks; never runs them |
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
 *
 * @see KiteSsotAndroidExtension for Android-only identity, SDK levels, and NDK.
 * @see KiteSsotIosExtension for Apple-only identity and the source sync gate.
 * @see VersionCodeScheme for the build-number formula both platforms share.
 * @see KiteSsotModulesExtension when detection cannot pick your modules for you.
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

    /** Detailed form of [appName]: per-platform overrides and flow modifiers. */
    fun appName(value: String, action: Action<in KiteAppNameScope>) {
        if (appName.isPresent) doubleSetWarnings.add("appName")
        appName.set(value)
        action.execute(appNameScope)
    }

    internal val appNameScope: KiteAppNameScope
        get() = nested()

    /** Facts set twice through mixed forms; kiteSsotDoctor warns on these. */
    internal abstract val doubleSetWarnings: org.gradle.api.provider.SetProperty<String>

    internal fun effectiveAppNameFor(p: KitePlatform): Provider<String> =
        appNameScope.overrideFor(p).orElse(appName)

    internal fun appNameFlowsTo(p: KitePlatform): Provider<Boolean> =
        appNameScope.flowsTo(p)

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
     *
     * Discovery reads the shared module, so it needs one to be selected or
     * detected. Tags are canonicalized and de-duplicated before use.
     *
     * @throws org.gradle.api.GradleException during configuration when a tag is
     *   not a well-formed BCP 47 language tag.
     * @see KiteSsotModulesExtension.composeResources to point discovery elsewhere.
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
     * scheme { v -> 1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.reupload }
     * ```
     *
     * Google Play compares codes as plain integers and remembers every one you
     * have ever uploaded, so a new formula must always produce a **larger**
     * number than your highest shipped code. Guard that with
     * [KiteSsotAndroidExtension.publishedVersionCode].
     *
     * @throws org.gradle.api.GradleException at task time when the formula
     *   returns a value outside `1..2_100_000_000`, the range Google Play
     *   accepts.
     * @see VersionSchemes.DEFAULT for the layout used when you set nothing.
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
        logo.declared.set(true)
        action.execute(logo)
    }

    /**
     * Kotlin/Native interop opt-in markers. Configure with
     * `kiteSsot { optIns { } }`.
     */
    val optIns: KiteSsotNativeOptInsExtension
        get() = nested()

    /** Add opt-in markers to the selected Kotlin/Native compilations. */
    fun optIns(action: Action<in KiteSsotNativeOptInsExtension>) {
        optInsDeclared.set(true)
        action.execute(optIns)
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
        buildConfigDeclared.set(true)
        action.execute(buildConfig)
    }

    /**
     * Compose Desktop settings. Configure with `kiteSsot { desktop { } }`.
     *
     * Opening this block turns desktop propagation on.
     */
    val desktop: KiteSsotDesktopExtension
        get() = nested()

    /** Apply app identity, versions, and installer values to Compose Desktop. */
    fun desktop(action: Action<in KiteSsotDesktopExtension>) {
        desktop.configured.set(true)
        action.execute(desktop)
    }

    private inline fun <reified T : Any> nested(): T =
        (this as ExtensionAware).extensions.getByType(T::class.java)

    // ------------------------------------------------------ Derived, read-only

    /** Reverse-DNS id base shared by every platform. Simple form of [id]. */
    abstract val id: Property<String>

    /** Detailed form of [id]: per-platform suffix corners and flow modifiers. */
    fun id(value: String, action: Action<in KiteIdScope>) {
        if (id.isPresent) doubleSetWarnings.add("id")
        id.set(value)
        action.execute(idScope)
    }

    internal val idScope: KiteIdScope
        get() = nested()

    // Transition bridge: falls back to the 2.x/3.0 chains until the purge task
    // deletes them. New surface wins wherever both are set.
    internal fun effectiveIdFor(p: KitePlatform): Provider<String> {
        val base = id.orElse(effectiveAppId)
        val legacySuffix = when (p) {
            KitePlatform.ANDROID -> effectiveAndroidIdSuffix
            KitePlatform.IOS -> effectiveIosBundleSuffix
            KitePlatform.DESKTOP -> desktop.idSuffix
        }
        return base.zip(idScope.suffixFor(p).orElse(legacySuffix).orElse("")) { b, s -> b + s }
    }

    internal fun idFlowsTo(p: KitePlatform): Provider<Boolean> = idScope.flowsTo(p)

    /** Android application id: [id] plus its android corner suffix. */
    val androidApplicationId: Provider<String>
        get() = effectiveIdFor(KitePlatform.ANDROID)

    /** Apple bundle id: [id] plus its ios corner suffix. */
    val iosBundleId: Provider<String>
        get() = effectiveIdFor(KitePlatform.IOS)

    /**
     * Desktop bundle id: [id] plus its desktop corner suffix.
     *
     * @throws org.gradle.api.GradleException when read, if the result is not a
     *   valid reverse-DNS identifier.
     */
    val desktopBundleId: Provider<String>
        get() = effectiveIdFor(KitePlatform.DESKTOP).map(::validateAppleBundleId)

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
    internal abstract val optInsDeclared: Property<Boolean>
    internal abstract val buildConfigDeclared: Property<Boolean>

    internal val effectiveAppName: Provider<String>
        get() = appName

    internal val effectiveVersion: Provider<String>
        get() = version.orElse(versionName)

    internal val effectiveAppId: Provider<String>
        get() = appId.orElse(bundleIdBase)

    internal val effectiveJvmTarget: Provider<Int>
        get() = jvmTarget.orElse(javaVersion)

    /** The locales topic: pinned list, Android res filter, and flow modifiers. */
    fun locales(action: Action<in KiteLocalesScope>) = action.execute(localesScope)

    internal val localesScope: KiteLocalesScope
        get() = nested()

    internal fun localesFlowsTo(p: KitePlatform): Provider<Boolean> = localesScope.flowsTo(p)

    // Transition bridge: the pinned list wins, the legacy root list follows.
    internal val effectiveLocales: Provider<List<String>>
        get() = localesScope.pinned.map { it.ifEmpty { locales.getOrElse(emptyList()) } }

    internal val effectiveDryRun: Provider<Boolean>
        get() = dryRunOverride.orElse(dryRun).orElse(false)

    internal val effectiveBackups: Provider<Boolean>
        get() = backupsOverride.orElse(backups).orElse(backupBeforeRewrite).orElse(true)

    // --- structure ---

    /**
     * Detection result, set by the plugin at projectsEvaluated when exactly one
     * project applies Kotlin Multiplatform. Last in the chain: an explicit choice,
     * 3.0 or 2.x, always beats what discovery found.
     */
    internal abstract val detectedSharedProject: Property<String>

    internal val effectiveSharedProjectPath: Provider<String>
        get() = modules.shared.orElse(sharedProjectPath).orElse(detectedSharedProject)

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
        get() = localesScope.filterAndroidRes
            .orElse(android.filterResourcesToLocales).orElse(filterAndroidResources).orElse(false)

    // --- version numbers ---

    /** Detailed form of [version]: the shared formula and platform corners. */
    fun version(value: String, action: Action<in KiteVersionScope>) {
        if (version.isPresent) doubleSetWarnings.add("version")
        version.set(value)
        action.execute(versionScope)
    }

    internal val versionScope: KiteVersionScope
        get() = nested()

    internal fun versionFlowsTo(p: KitePlatform): Provider<Boolean> = versionScope.flowsTo(p)

    // Transition bridge: corner formula > topic formula > legacy platform scheme
    // > legacy root scheme > default. The purge task deletes the legacy legs.
    private fun activeFormula(
        corner: Provider<VersionCodeScheme>,
        legacyPlatform: Provider<VersionCodeScheme>,
    ): Provider<VersionCodeScheme> =
        corner.orElse(versionScope.formulaProp).orElse(legacyPlatform).orElse(schemeOrDefault)

    internal val effectiveAndroidVersionCode: Provider<Int>
        get() = versionScope.android.pin
            .orElse(android.versionCode)
            .orElse(versionCodeOverride)
            .orElse(
                effectiveVersion.zip(
                    versionScope.android.reupload.orElse(android.rebuild).orElse(0)
                        .zip(activeFormula(versionScope.android.formulaProp, android.scheme)) { r, s -> r to s },
                ) { version, (reupload, activeScheme) ->
                    computeVersionCode(activeScheme, version, reupload, "android")
                },
            )

    /** True only when a code was pinned by hand, so no formula was consulted. */
    internal val effectiveHasExplicitVersionCode: Provider<Boolean>
        get() = versionScope.android.pin
            .orElse(android.versionCode).orElse(versionCodeOverride)
            .map { true }.orElse(false)

    internal val effectiveIosBuildNumber: Provider<String>
        get() = versionScope.ios.pin
            .orElse(ios.buildNumber)
            .orElse(iosBuildNumber)
            .orElse(
                effectiveVersion.zip(
                    versionScope.ios.reupload.orElse(ios.rebuild).orElse(0)
                        .zip(activeFormula(versionScope.ios.formulaProp, ios.scheme)) { r, s -> r to s },
                ) { version, (reupload, activeScheme) ->
                    computeVersionCode(activeScheme, version, reupload, "ios").toString()
                },
            )

    internal val effectiveIosMarketingVersion: Provider<String>
        get() = versionScope.ios.marketingVersion
            .orElse(ios.marketingVersion).orElse(iosMarketingVersion).orElse(effectiveVersion)

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

    /** True when `logo { rewrite { } }` armed the source-writing tasks. */
    internal val effectiveLogoRewriteArmed: Provider<Boolean>
        get() = logo.rewriteArmed.orElse(false)
            .zip(propagateLogo.orElse(false)) { armed, legacy -> armed || legacy }

    internal val effectivePropagateLogo: Provider<Boolean>
        get() = effectiveLogoRewriteArmed

    internal val effectiveLogoForeground: Provider<org.gradle.api.file.RegularFile>
        get() = logo.foreground.orElse(appLogoPngForeground)

    internal val effectiveLogoBackground: Provider<org.gradle.api.file.RegularFile>
        get() = logo.background.orElse(appLogoPngBackground)

    internal val effectiveLogoBackgroundColor: Provider<String>
        get() = logo.backgroundColor.orElse(appLogoBackgroundColor)

    internal val effectiveLogoSafeZone: Provider<Double>
        get() = logo.android.safeZone.orElse(appLogoAndroidSafeZoneRatio).orElse(DEFAULT_ANDROID_SAFE_ZONE)

    internal val effectiveTakeOverLegacyIcons: Provider<Boolean>
        get() = effectiveLogoRewriteArmed.zip(
            logo.rewriteSpec.replaceOld.orElse(cleanupLegacyLogoArtifacts).orElse(false),
        ) { armed, takeOver -> armed && takeOver }

    // --- native opt-ins ---

    internal val effectiveNativeOptInsEnabled: Provider<Boolean>
        get() = optInsDeclared.orElse(false)
            .zip(propagateInteropOptIns.orElse(false)) { declared, legacy -> declared || legacy }

    internal val effectiveNativeOptInBuiltIns: Provider<Boolean>
        get() = optIns.builtIns.orElse(true)

    internal val effectiveAndroidIdSuffix: Provider<String>
        get() = android.idSuffix.orElse(androidApplicationIdSuffix)

    internal val effectiveNativeOptInMarkers: Provider<List<String>>
        get() = optIns.markers.map { it.ifEmpty { extraOptIns.getOrElse(emptyList()) } }

    internal val effectiveNativeOptInProjects: Provider<List<String>>
        get() = optIns.projects.map { it.ifEmpty { interopProjectPaths.getOrElse(emptyList()) } }

    // --- web ---

    internal val effectiveIoWorkerEnabled: Provider<Boolean>
        get() = web.ioWorker.declared.orElse(false)

    internal val effectiveIoWorkerTargets: Provider<List<String>>
        get() = web.ioWorker.targets

    internal val effectiveIoWorkerProjects: Provider<List<String>>
        get() = web.ioWorker.projects

    internal val effectiveIoWorkerPackage: Provider<String>
        get() = web.ioWorker.packageName.orElse(DEFAULT_GENERATED_PACKAGE)

    // --- build config ---

    internal val effectiveBuildConfigEnabled: Provider<Boolean>
        get() = buildConfigDeclared.orElse(false)

    // --- desktop ---

    internal val effectiveDesktopEnabled: Provider<Boolean>
        get() = desktop.configured.orElse(false)
            .zip(desktop.enabled.orElse(true)) { configured, enabled -> configured && enabled }

    /** No 2.x fallback chain: `desktop { }` has no deprecated predecessor. */
    internal val effectiveDesktopApps: Provider<List<String>>
        get() = modules.desktopApps

    internal val effectiveDesktopBuildNumber: Provider<String>
        get() = versionScope.desktop.pin
            .orElse(desktop.buildNumber)
            .orElse(
                effectiveVersion.zip(
                    versionScope.desktop.reupload.orElse(desktop.rebuild).orElse(0)
                        .zip(activeFormula(versionScope.desktop.formulaProp, desktop.scheme)) { r, s -> r to s },
                ) { version, (reupload, activeScheme) ->
                    computeVersionCode(activeScheme, version, reupload, "desktop").toString()
                },
            )

    // Logo art flowing to desktop: presence of art, not an armed rewrite.
    internal val effectiveDesktopIcons: Provider<Boolean>
        get() = logo.declared.orElse(false)
            .zip(desktop.icons.orElse(true)) { declared, icons -> declared && icons }
            .zip(logo.flowsTo(KitePlatform.DESKTOP)) { wanted, flows -> wanted && flows }

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

    /** Configure `optIns { }` instead. Configuring the block is the opt-in. */
    @Deprecated("Configure the optIns { } block instead.")
    abstract val propagateInteropOptIns: Property<Boolean>

    /** Use `optIns { add(...) }`. */
    @Deprecated("Moved to optIns { add() }.", ReplaceWith("optIns.markers"))
    abstract val extraOptIns: ListProperty<String>

    /** Use `optIns { projects(...) }`. */
    @Deprecated("Moved to optIns { projects() }.", ReplaceWith("optIns.projects"))
    abstract val interopProjectPaths: ListProperty<String>

    /** Use [backups]. */
    @Deprecated("Renamed to backups.", ReplaceWith("backups"))
    abstract val backupBeforeRewrite: Property<Boolean>
}
