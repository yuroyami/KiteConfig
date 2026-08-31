@file:Suppress("DEPRECATION")

package io.github.yuroyami.kiteconfig

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * The single source of truth for your app's identity. Apply to the **root** project.
 *
 * ## The law
 *
 * 1. **Facts always flow.** A declared fact reaches every platform found, on every
 *    build, in memory or as files under `build/`. Declaring it is the consent.
 *    `skip()` and `only()` beside the fact are the only flow control.
 * 2. **`rewrite { }` is the only word that acts on YOUR files.** It arms a by-name
 *    task that edits source. [dryRun], [backups], and `onConflict` always apply.
 * 3. **One topic, one block.** Platform corners nest inside topics. Platform
 *    blocks hold only platform-exclusive things.
 *
 * Three lines are a complete setup:
 *
 * ```kotlin
 * kiteConfig {
 *     appName = "Jetzy"
 *     version = "1.4.0"
 *     id      = "com.example.jetzy"
 * }
 * ```
 *
 * Locales auto-detect from Compose resources, the shared module and the app
 * modules are detected too. Everything else below is optional.
 *
 * ## The full surface
 *
 * ```kotlin
 * kiteConfig {
 *     appName("Jetzy") {
 *         ios("Jetzy Lite")          // platform value override
 *         skip(desktop)              // this fact does not flow there
 *     }
 *
 *     jvmTarget = 21                 // Java + Kotlin JVM level, whole build
 *
 *     id("com.example.jetzy") {
 *         android { suffix = ".android" }   // applicationId = base + suffix
 *         ios     { suffix = ".ios" }       // bundle id     = base + suffix
 *         desktop { suffix = ".desktop" }
 *     }
 *
 *     version("1.4.0") {
 *         // formula { v -> ... }        // optional: your own build-number formula
 *         android {
 *             reupload = 1               // re-upload same version to Play
 *             shipped  = 1001003090      // highest code ever shipped, guard
 *         }
 *         ios { shipped = "1001003090" }
 *         desktop { shipped = "1001003090" }
 *     }
 *
 *     locales {
 *         pin("en", "ar", "fr")          // hand list, detection skipped
 *         filterAndroidRes = true        // drop Android res outside the list
 *     }
 *
 *     logo {
 *         foreground = file("art/logo-fg.png")
 *         backgroundColor = "#0B0B0F"
 *         android { safeZone = 0.611 }
 *         desktop { roundMac = true }    // desktop icons flow from presence
 *         rewrite { replaceOld = true }  // arms kiteRewriteLogo (source edits)
 *     }
 *
 *     splash {
 *         // empty block already works: art defaults to logo, plate to its color
 *         dark { backgroundColor = "#000000" }
 *         android { theme = "AppTheme" }     // your app theme; the generated
 *                                            //   KiteSplash style inherits it
 *         rewrite { }                        // arms the iOS launch-screen delivery
 *     }
 *
 *     optIns {
 *         add("kotlinx.cinterop.ExperimentalForeignApi")
 *     }
 *
 *     android { sdk(min = 26, target = 36, compile = 36) }
 *
 *     ios {
 *         deploymentTarget = "15.0"
 *         rewrite {                      // arms kiteRewriteXcode (source edits)
 *             targets("iosApp")
 *             cleanPlist = true
 *         }
 *     }
 *
 *     desktop { linuxPackageName = "jetzy" }
 *
 *     web { ioWorker { targets("js") } }
 *
 *     buildConfig {                      // presence generates into build/
 *         packageName = "com.example.jetzy"
 *         stringField("API_HOST", "api.jetzy.app")
 *     }
 *
 *     modules { shared = ":shared" }     // only when detection guesses wrong
 *
 *     dryRun  = false
 *     backups = true
 *     // ignoreVersionGuards = true  // off the tested AGP/KGP/Compose matrix, on your own head
 * }
 * ```
 *
 * ## What you can inject where
 *
 * | You can inject | Works in | Meaning |
 * |---|---|---|
 * | `skip(p...)` / `only(p...)` | root, `appName`, `id`, `version`, `locales`, `logo`, `splash` | flow control, at root = platform master |
 * | `android("v")` / `ios("v")` / `desktop("v")` | `appName` | platform value override |
 * | `android { }` / `ios { }` / `desktop { }` corner | `id`, `version`, `logo` | platform detail scope |
 * | `pin` | `version` corners, `locales` | manual value, machinery skipped |
 * | `reupload`, `shipped`, `formula` | `version` and its corners | store counter, guard floor, number formula |
 * | `suffix` | `id` corners | appended to the base id |
 * | `rewrite { }` | `logo`, `splash`, `ios` | the only acting word, arms a task |
 * | `dark { }` | `splash` | dark-mode variant |
 * | typed `*Field(...)` | `buildConfig` | generated constants |
 *
 * ## Tasks
 *
 * `kiteCheck`, `kiteDoctor`, `kiteVerify`, `kitePlan` are read-only and always
 * safe. `kiteRewriteLogo` and `kiteRewriteXcode` run only when armed by a
 * `rewrite { }` block and only when you invoke them. CLI overrides:
 * `-Pkiteconfig.dryRun=true`, `-Pkiteconfig.backups=false`.
 *
 * ## Read-back
 *
 * This extension implements [KiteConfigValues], so every resolved value is
 * readable from any build file in the project:
 *
 * ```kotlin
 * import io.github.yuroyami.kiteconfig.kiteConfig
 *
 * versionCode = kiteConfig.versionCode.get()
 * ```
 *
 * That view is read-only and covers version, identity, locales, the shared
 * module path, and the Android SDK levels. See [KiteConfigValues] for the full
 * list and for the two values that resolve later than the rest.
 *
 * @see KiteAppNameScope for name overrides and flow.
 * @see KiteIdScope for identity suffix corners.
 * @see KiteVersionScope for the formula and version corners.
 * @see KiteLocalesScope for the pinned list and the Android res filter.
 * @see KiteConfigLogoExtension for icon art and the armed logo rewrite.
 * @see KiteSplashScope for launch-screen art on all three platforms.
 * @see VersionCodeScheme for the build-number formula input.
 */
abstract class KiteConfigExtension : KiteFlowScope(), KiteConfigValues {

    // ---------------------------------------------------------------- Identity

    /**
     * The display name users see on their home screen.
     *
     * Android receives it as the `appName` manifest placeholder. The explicit
     * Apple sync tasks use it for `PRODUCT_NAME`, `CFBundleName`, and
     * `CFBundleDisplayName`.
     */
    abstract override val appName: Property<String>

    /** Detailed form of [appName]: per-platform overrides and flow modifiers. */
    fun appName(value: String, action: Action<in KiteAppNameScope>) {
        if (appName.isPresent) doubleSetWarnings.add("appName")
        appName.set(value)
        action.execute(appNameScope)
    }

    internal val appNameScope: KiteAppNameScope
        get() = nested()

    /** Facts set twice through mixed forms; kiteDoctor warns on these. */
    internal abstract val doubleSetWarnings: org.gradle.api.provider.SetProperty<String>

    internal fun effectiveAppNameFor(p: KitePlatform): Provider<String> =
        appNameScope.overrideFor(p).orElse(appName)

    // ------------------------------------------------------------------ Splash

    /** The splash topic: launch-screen art. Presence flows Android and desktop. */
    fun splash(action: Action<in KiteSplashScope>) {
        splash.declared.set(true)
        action.execute(splash)
    }

    internal val splash: KiteSplashScope
        get() = nested()

    internal fun splashFlowsTo(p: KitePlatform): Provider<Boolean> =
        flowsTo(p).zip(splash.flowsTo(p)) { root, topic -> root && topic }

    internal val effectiveSplashImage: Provider<org.gradle.api.file.RegularFile>
        get() = splash.image.orElse(logo.foreground)

    internal val effectiveSplashColor: Provider<String>
        get() = splash.backgroundColor.orElse(logo.backgroundColor)

    internal val effectiveSplashDarkImage: Provider<org.gradle.api.file.RegularFile>
        get() = splash.dark.image

    internal val effectiveSplashDarkColor: Provider<String>
        get() = splash.dark.backgroundColor

    internal val effectiveSplashAndroidTheme: Provider<String>
        get() = splash.android.theme

    internal val effectiveAndroidSplash: Provider<Boolean>
        get() = splash.declared.orElse(false)
            .zip(splashFlowsTo(KitePlatform.ANDROID)) { d, f -> d && f }

    internal val effectiveDesktopSplash: Provider<Boolean>
        get() = splash.declared.orElse(false)
            .zip(splashFlowsTo(KitePlatform.DESKTOP)) { d, f -> d && f }

    internal val effectiveIosSplash: Provider<Boolean>
        get() = splash.rewriteArmed.orElse(false)
            .zip(effectiveSyncIos) { armed, sync -> armed && sync }
            .zip(splashFlowsTo(KitePlatform.IOS)) { on, flows -> on && flows }

    internal fun appNameFlowsTo(p: KitePlatform): Provider<Boolean> =
        flowsTo(p).zip(appNameScope.flowsTo(p)) { root, topic -> root && topic }

    /**
     * The release version you show to users, as `x.y.z`.
     *
     * Android uses it for `versionName`, Apple for the marketing version, and
     * the build-number formula derives every store counter from it.
     */
    abstract override val version: Property<String>

    /**
     * Java and Kotlin JVM level for the whole build, for example `21`.
     *
     * This sets Java compatibility in classic Android modules and aligns
     * Kotlin JVM compile tasks. It is a build-wide policy, so project
     * selectors do not narrow it.
     */
    abstract override val jvmTarget: Property<Int>

    // ---------------------------------------------------------- Build numbers


    // ----------------------------------------------------------------- Safety

    /**
     * Make the explicit source-changing tasks report what they would do and
     * write nothing.
     *
     * Default: `false`. Override per invocation with `-Pkiteconfig.dryRun=true`.
     * Generated Kotlin ignores this, because it is a build input rather than a
     * change to your source tree.
     */
    abstract val dryRun: Property<Boolean>

    /**
     * Treat AGP, KGP, and Compose versions outside the tested range as supported.
     *
     * Default: `false`. The guards exist because only that range is exercised by
     * this release's tests; switching this on trades that proof for one loud
     * warning and keeps every typed integration active on your versions.
     *
     * Requires `@file:OptIn(io.github.yuroyami.kiteconfig.DiscouragedKiteApi::class)`
     * in the build script.
     */
    @DiscouragedKiteApi
    abstract val ignoreVersionGuards: Property<Boolean>

    /**
     * Keep a first-contact recovery copy before rewriting a file.
     *
     * Default: `true`. Override with `-Pkiteconfig.backups=false`. Android legacy
     * icon takeover always records its own recovery data regardless of this.
     */
    abstract val backups: Property<Boolean>

    // ----------------------------------------------------------------- Blocks

    /** Where your modules live. Configure with `kiteConfig { modules { } }`. */
    val modules: KiteConfigModulesExtension
        get() = nested()

    /** Tell KiteConfig where the shared and Android application projects are. */
    fun modules(action: Action<in KiteConfigModulesExtension>) = action.execute(modules)

    /** Android-only settings. Configure with `kiteConfig { android { } }`. */
    val android: KiteConfigAndroidExtension
        get() = nested()

    /** Configure SDK levels, the Android id suffix, and the Play re-upload dial. */
    fun android(action: Action<in KiteConfigAndroidExtension>) = action.execute(android)

    /** Apple-only settings. Configure with `kiteConfig { ios { } }`. */
    val ios: KiteConfigIosExtension
        get() = nested()

    /** Configure the Apple bundle suffix, build number, paths, and source sync. */
    fun ios(action: Action<in KiteConfigIosExtension>) = action.execute(ios)

    /**
     * App icon installation. Configure with `kiteConfig { logo { } }`.
     *
     * Configuring this block authorizes the logo tasks. It does not run them.
     */
    val logo: KiteConfigLogoExtension
        get() = nested()

    /** Point KiteConfig at your foreground and background art. */
    fun logo(action: Action<in KiteConfigLogoExtension>) {
        logoConfigured.set(true)
        logo.declared.set(true)
        action.execute(logo)
    }

    /**
     * Kotlin/Native interop opt-in markers. Configure with
     * `kiteConfig { optIns { } }`.
     */
    val optIns: KiteConfigNativeOptInsExtension
        get() = nested()

    /** Add opt-in markers to the selected Kotlin/Native compilations. */
    fun optIns(action: Action<in KiteConfigNativeOptInsExtension>) {
        optInsDeclared.set(true)
        action.execute(optIns)
    }

    /** Browser Kotlin/JS helpers. Configure with `kiteConfig { web { } }`. */
    val web: KiteConfigWebExtension
        get() = nested()

    /** Configure optional browser Kotlin/JS source generation. */
    fun web(action: Action<in KiteConfigWebExtension>) = action.execute(web)

    /**
     * Generated runtime constants for `commonMain`. Configure with
     * `kiteConfig { buildConfig { } }`.
     *
     * Configuring this block turns generation on.
     */
    val buildConfig: KiteConfigBuildConfigExtension
        get() = nested()

    /** Generate a Kotlin object of public runtime configuration. */
    fun buildConfig(action: Action<in KiteConfigBuildConfigExtension>) {
        buildConfigDeclared.set(true)
        action.execute(buildConfig)
    }

    /**
     * Compose Desktop settings. Configure with `kiteConfig { desktop { } }`.
     *
     * Opening this block turns desktop propagation on.
     */
    val desktop: KiteConfigDesktopExtension
        get() = nested()

    /** Apply app identity, versions, and installer values to Compose Desktop. */
    fun desktop(action: Action<in KiteConfigDesktopExtension>) {
        desktop.configured.set(true)
        action.execute(desktop)
    }

    private inline fun <reified T : Any> nested(): T =
        (this as ExtensionAware).extensions.getByType(T::class.java)

    // ------------------------------------------------------ Derived, read-only

    /** Reverse-DNS id base shared by every platform. Simple form of [id]. */
    abstract override val id: Property<String>

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
    override val androidApplicationId: Provider<String>
        get() = effectiveIdFor(KitePlatform.ANDROID)

    /** Apple bundle id: [id] plus its ios corner suffix. */
    override val iosBundleId: Provider<String>
        get() = effectiveIdFor(KitePlatform.IOS)

    /**
     * Desktop bundle id: [id] plus its desktop corner suffix.
     *
     * @throws org.gradle.api.GradleException when read, if the result is not a
     *   valid reverse-DNS identifier.
     */
    override val desktopBundleId: Provider<String>
        get() = effectiveIdFor(KitePlatform.DESKTOP).map(::validateAppleBundleId)

    /** The resolved Android `versionCode` for this build. */
    override val versionCode: Provider<Int>
        get() = effectiveAndroidVersionCode

    /** Normalized, de-duplicated locale tags. */
    override val canonicalLocales: Provider<List<String>>
        get() = effectiveLocales.map(::canonicalizeLocales)

    /** The selected shared KMP project path. */
    override val resolvedSharedProjectPath: Provider<String>
        get() = effectiveSharedProjectPath

    /** The resolved Apple build number, `CFBundleVersion`. */
    override val iosBuildNumber: Provider<String>
        get() = effectiveIosBuildNumber

    /** The resolved Apple marketing version, `CFBundleShortVersionString`. */
    override val iosMarketingVersion: Provider<String>
        get() = effectiveIosMarketingVersion

    /** The resolved desktop build number. */
    override val desktopBuildNumber: Provider<String>
        get() = effectiveDesktopBuildNumber

    /** The app name as [platform] receives it, corner overrides applied. */
    override fun appNameFor(platform: KitePlatform): Provider<String> =
        effectiveAppNameFor(platform)

    /** Lowest Android API level the app runs on. */
    override val minSdk: Provider<Int>
        get() = android.minSdk

    /** Android API level the app targets. */
    override val targetSdk: Provider<Int>
        get() = android.targetSdk

    /** Android API level the app compiles against. */
    override val compileSdk: Provider<Int>
        get() = android.compileSdk

    /** Pinned Android NDK version, in Android's `major.minor.build` form. */
    override val ndk: Provider<String>
        get() = android.ndk

    // ============================================================ INTERNAL MODEL
    // The engine reads these, never the public properties, so that a value set
    // through a 3.0 block and the same value set through a deprecated 2.x
    // property resolve identically.

    /** `-Pkiteconfig.dryRun=true|false`, bound by the plugin. Wins over [dryRun] when present. */
    internal abstract val dryRunOverride: Property<Boolean>

    /** `-Pkiteconfig.backups=true|false`, bound by the plugin. Wins over [backups] when present. */
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

    /** Armed logo rewrite that also asked to ride ordinary Android builds. */
    internal val effectiveAutoRewriteLogo: Provider<Boolean>
        @OptIn(DiscouragedKiteApi::class)
        get() = effectiveLogoRewriteArmed.zip(logo.rewriteSpec.auto.orElse(false)) { armed, auto -> armed && auto }

    /** Armed Xcode rewrite that also asked to ride ordinary iOS builds. */
    internal val effectiveAutoRewriteXcode: Provider<Boolean>
        @OptIn(DiscouragedKiteApi::class)
        get() = effectiveSyncIos.zip(ios.rewrite.auto.orElse(false)) { armed, auto -> armed && auto }

    internal val effectiveIgnoreVersionGuards: Provider<Boolean>
        @OptIn(DiscouragedKiteApi::class)
        get() = ignoreVersionGuards.orElse(false)

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

    // Corner formula beats the topic formula, which beats the default.
    private fun activeFormula(corner: Provider<VersionCodeScheme>): Provider<VersionCodeScheme> =
        corner.orElse(versionScope.formulaProp).orElse(VersionSchemes.DEFAULT)

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
        internal const val DEFAULT_GENERATED_PACKAGE: String = "kiteconfig.generated"
    }
}
