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
 *     id      = "com.example.jetzy"
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
abstract class KiteSsotExtension : KiteFlowScope() {

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
        flowsTo(p).zip(appNameScope.flowsTo(p)) { root, topic -> root && topic }

    /**
     * The release version you show to users, as `x.y.z`.
     *
     * Android uses it for `versionName`, Apple for the marketing version, and
     * the build-number formula derives every store counter from it.
     */
    abstract val version: Property<String>

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
     * formula { v -> 1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.reupload }
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
        val base = id
        return base.zip(idScope.suffixFor(p).orElse("")) { b, s -> b + s }
    }

    internal fun idFlowsTo(p: KitePlatform): Provider<Boolean> =
        flowsTo(p).zip(idScope.flowsTo(p)) { root, topic -> root && topic }

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
        get() = version

    internal val effectiveJvmTarget: Provider<Int>
        get() = jvmTarget

    /** The locales topic: pinned list, Android res filter, and flow modifiers. */
    fun locales(action: Action<in KiteLocalesScope>) = action.execute(localesScope)

    internal val localesScope: KiteLocalesScope
        get() = nested()

    internal fun localesFlowsTo(p: KitePlatform): Provider<Boolean> =
        flowsTo(p).zip(localesScope.flowsTo(p)) { root, topic -> root && topic }

    private fun anywhere(gate: (KitePlatform) -> Provider<Boolean>): Provider<Boolean> =
        gate(KitePlatform.ANDROID).zip(gate(KitePlatform.IOS)) { a, i -> a || i }
            .zip(gate(KitePlatform.DESKTOP)) { x, d -> x || d }

    internal fun appNameFlowsAnywhere(): Provider<Boolean> = anywhere(::appNameFlowsTo)
    internal fun idFlowsAnywhere(): Provider<Boolean> = anywhere(::idFlowsTo)
    internal fun localesFlowsAnywhere(): Provider<Boolean> = anywhere(::localesFlowsTo)

    internal val effectiveLocales: Provider<List<String>>
        get() = localesScope.pinned

    internal val effectiveDryRun: Provider<Boolean>
        get() = dryRunOverride.orElse(dryRun).orElse(false)

    internal val effectiveBackups: Provider<Boolean>
        get() = backupsOverride.orElse(backups).orElse(true)

    // --- structure ---

    /**
     * Detection result, set by the plugin at projectsEvaluated when exactly one
     * project applies Kotlin Multiplatform. Last in the chain: an explicit choice,
     * 3.0 or 2.x, always beats what discovery found.
     */
    internal abstract val detectedSharedProject: Property<String>

    internal val effectiveSharedProjectPath: Provider<String>
        get() = modules.shared.orElse(detectedSharedProject)

    internal val effectiveComposeResources: Provider<org.gradle.api.file.Directory>
        get() = modules.composeResources

    internal val effectiveAndroidApps: Provider<List<String>>
        get() = modules.androidApps


    internal val effectiveAndroidAppDirectory: Provider<org.gradle.api.file.Directory>
        get() = modules.androidAppDirectory

    // --- apply gates ---

    internal val effectivePropagateVersion: Provider<Boolean>
        get() = versionFlowsTo(KitePlatform.ANDROID)
            .zip(versionFlowsTo(KitePlatform.IOS)) { a, i -> a || i }
            .zip(versionFlowsTo(KitePlatform.DESKTOP)) { s0, d -> s0 || d }

    internal val effectiveApplySdkLevels: Provider<Boolean>
        get() = android.minSdk.map { true }
            .orElse(android.targetSdk.map { true })
            .orElse(android.compileSdk.map { true })
            .orElse(android.ndk.map { true })
            .orElse(false)

    internal val effectiveFilterAndroidResources: Provider<Boolean>
        get() = localesScope.filterAndroidRes.orElse(false)

    // --- version numbers ---

    /** Detailed form of [version]: the shared formula and platform corners. */
    fun version(value: String, action: Action<in KiteVersionScope>) {
        if (version.isPresent) doubleSetWarnings.add("version")
        version.set(value)
        action.execute(versionScope)
    }

    internal val versionScope: KiteVersionScope
        get() = nested()

    internal fun versionFlowsTo(p: KitePlatform): Provider<Boolean> =
        flowsTo(p).zip(versionScope.flowsTo(p)) { root, topic -> root && topic }

    // Transition bridge: corner formula > topic formula > legacy platform scheme
    // > legacy root scheme > default. The purge task deletes the legacy legs.
    private fun activeFormula(corner: Provider<VersionCodeScheme>): Provider<VersionCodeScheme> =
        corner.orElse(versionScope.formulaProp).orElse(schemeOrDefault)

    internal val effectiveAndroidVersionCode: Provider<Int>
        get() = versionScope.android.pin
            .orElse(
                effectiveVersion.zip(
                    versionScope.android.reupload.orElse(0)
                        .zip(activeFormula(versionScope.android.formulaProp)) { r, s -> r to s },
                ) { version, (reupload, activeScheme) ->
                    computeVersionCode(activeScheme, version, reupload, "android")
                },
            )

    /** True only when a code was pinned by hand, so no formula was consulted. */
    internal val effectiveHasExplicitVersionCode: Provider<Boolean>
        get() = versionScope.android.pin.map { true }.orElse(false)

    internal val effectiveIosBuildNumber: Provider<String>
        get() = versionScope.ios.pin
            .orElse(
                effectiveVersion.zip(
                    versionScope.ios.reupload.orElse(0)
                        .zip(activeFormula(versionScope.ios.formulaProp)) { r, s -> r to s },
                ) { version, (reupload, activeScheme) ->
                    computeVersionCode(activeScheme, version, reupload, "ios").toString()
                },
            )

    internal val effectiveIosMarketingVersion: Provider<String>
        get() = versionScope.ios.marketingVersion.orElse(effectiveVersion)

    private val schemeOrDefault: Provider<VersionCodeScheme>
        get() = scheme.orElse(VersionSchemes.DEFAULT)

    // --- ios ---

    internal val effectiveIosPbxproj: Provider<org.gradle.api.file.RegularFile>
        get() = ios.pbxproj

    internal val effectiveIosPodfile: Provider<org.gradle.api.file.RegularFile>
        get() = ios.podfile

    internal val effectiveIosInfoPlist: Provider<org.gradle.api.file.RegularFile>
        get() = ios.infoPlist

    internal val effectiveIosAppDirectory: Provider<org.gradle.api.file.Directory>
        get() = ios.appDirectory

    internal val effectiveIosAppIconDirectory: Provider<org.gradle.api.file.Directory>
        get() = ios.appIconDirectory

    internal val effectiveSyncIos: Provider<Boolean>
        get() = ios.rewrite.rewriteArmed.orElse(false)

    internal val effectiveSanitizeIosProject: Provider<Boolean>
        get() = ios.rewrite.cleanPlist.orElse(false)

    internal val effectiveIosTargets: Provider<List<String>>
        get() = ios.rewrite.targets

    internal val effectivePlistConflictPolicy: Provider<PlistConflictPolicy>
        get() = ios.rewrite.onConflict.orElse(PlistConflictPolicy.FAIL)

    internal val effectiveNonExemptEncryption: Provider<Boolean>
        get() = ios.rewrite.nonExemptEncryption

    internal val effectiveProMotion: Provider<Boolean>
        get() = ios.rewrite.proMotion

    internal val effectiveIosSharedModuleName: Provider<String>
        get() = ios.rewrite.newSharedModuleName

    internal val effectiveIosPreviousSharedModuleName: Provider<String>
        get() = ios.rewrite.previousSharedModuleName

    internal val effectivePropagateSharedModule: Provider<Boolean>
        get() = ios.rewrite.newSharedModuleName.map { true }.orElse(false)

    // --- logo ---

    /** True when `logo { rewrite { } }` armed the source-writing tasks. */
    internal val effectiveLogoRewriteArmed: Provider<Boolean>
        get() = logo.rewriteArmed.orElse(false)

    internal val effectivePropagateLogo: Provider<Boolean>
        get() = effectiveLogoRewriteArmed

    internal val effectiveLogoForeground: Provider<org.gradle.api.file.RegularFile>
        get() = logo.foreground

    internal val effectiveLogoBackground: Provider<org.gradle.api.file.RegularFile>
        get() = logo.background

    internal val effectiveLogoBackgroundColor: Provider<String>
        get() = logo.backgroundColor

    internal val effectiveLogoSafeZone: Provider<Double>
        get() = logo.android.safeZone.orElse(DEFAULT_ANDROID_SAFE_ZONE)

    internal val effectiveTakeOverLegacyIcons: Provider<Boolean>
        get() = effectiveLogoRewriteArmed.zip(
            logo.rewriteSpec.replaceOld.orElse(false),
        ) { armed, takeOver -> armed && takeOver }

    // --- native opt-ins ---

    internal val effectiveNativeOptInsEnabled: Provider<Boolean>
        get() = optInsDeclared.orElse(false)

    internal val effectiveNativeOptInBuiltIns: Provider<Boolean>
        get() = optIns.builtIns.orElse(true)

    internal val effectiveNativeOptInMarkers: Provider<List<String>>
        get() = optIns.markers

    internal val effectiveNativeOptInProjects: Provider<List<String>>
        get() = optIns.projects

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

    // Desktop identity flows automatically; module presence gates the wiring.
    internal val effectiveDesktopEnabled: Provider<Boolean>
        get() = flowsTo(KitePlatform.DESKTOP)

    /** No 2.x fallback chain: `desktop { }` has no deprecated predecessor. */
    internal val effectiveDesktopApps: Provider<List<String>>
        get() = modules.desktopApps

    internal val effectiveDesktopBuildNumber: Provider<String>
        get() = versionScope.desktop.pin
            .orElse(
                effectiveVersion.zip(
                    versionScope.desktop.reupload.orElse(0)
                        .zip(activeFormula(versionScope.desktop.formulaProp)) { r, s -> r to s },
                ) { version, (reupload, activeScheme) ->
                    computeVersionCode(activeScheme, version, reupload, "desktop").toString()
                },
            )

    // Logo art flowing to desktop: presence of art, not an armed rewrite.
    internal val effectiveDesktopIcons: Provider<Boolean>
        get() = logo.declared.orElse(false)
            .zip(logo.flowsTo(KitePlatform.DESKTOP)) { declared, flows -> declared && flows }
            .zip(flowsTo(KitePlatform.DESKTOP)) { topic, root -> topic && root }

    internal companion object {
        internal const val DEFAULT_ANDROID_SAFE_ZONE: Double = 66.0 / 108.0
        internal const val DEFAULT_GENERATED_PACKAGE: String = "kitessot.generated"
    }
}
