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
 *     appId   = "com.example.jetzy"
 *     version = "1.4.0"
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
 *     appName = "Jetzy"
 *     appId   = "com.example.jetzy"
 *
 *     jvm {
 *         toolchain = 21             // build with this JDK
 *         target = 17                // Java and Kotlin bytecode level together
 *     }
 *
 *     version = "1.4.0"
 *     version {
 *         // scheme { v -> "..." }       // optional: your own build-number scheme
 *         rebuild = 0                    // re-upload the same version to a store
 *     }
 *
 *     locales { tags = listOf("en", "ar", "fr") }  // omit to detect them
 *
 *     logo {
 *         foreground = file("art/logo-fg.png")
 *         background = color("#0B0B0F")  // or image(file("art/bg.png"))
 *         foregroundScale = 0.75         // optional, per renderer default
 *         rewrite { replaceOld = true }  // arms kiteRewriteLogo (source edits)
 *     }
 *
 *     splash {
 *         enabled = true                 // off until you say this
 *         dark { backgroundColor = "#000000" }
 *         rewrite { }                    // arms the iOS launch-screen delivery
 *     }
 *
 *     optIns {
 *         add("kotlinx.cinterop.ExperimentalForeignApi")
 *     }
 *
 *     android {
 *         appId { suffix = ".android" }  // applicationId = appId + suffix
 *         version {
 *             rebuild = 1                        // this platform's counter
 *             publishedBuildNumber = "1001003090" // new numbers must beat it
 *         }
 *         locales { filterResources = true }  // drop res outside the list
 *         splash { theme = "AppTheme" }       // the theme the splash inherits
 *         sdk(min = 26, target = 36, compile = 36)
 *     }
 *
 *     ios {
 *         appName = "Jetzy Lite"         // only this platform differs
 *         deploymentTarget = "15.0"
 *         rewrite {                      // arms kiteRewriteXcode (source edits)
 *             targets("iosApp")
 *             cleanPlist = true
 *         }
 *     }
 *
 *     desktop {
 *         appId { suffix = ".desktop" }
 *         linuxPackageName = "jetzy"
 *     }
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
 * | `skip(p...)` / `only(p...)` | root, `version`, `locales`, `logo`, `splash` | flow control, at root = platform master |
 * | `appName` / `appId` | `android`, `ios`, `desktop` | that platform's own value |
 * | `appId { suffix }` | `android`, `ios`, `desktop` | appended to the shared `appId` |
 * | `android { }` / `ios { }` / `desktop { }` corner | `version`, `logo` | platform detail scope |
 * | `version` / `version { }` | `android`, `ios`, `desktop` | that platform's version and numbering |
 * | `rebuild`, `buildNumber`, `publishedBuildNumber` | `version` in a platform block | counter, exact number, guard floor |
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

    /**
     * The reverse-DNS identifier shared by every platform.
     *
     * A platform appends to it with `android { appId { suffix } }`, or replaces
     * it outright with `ios { appId = "..." }`.
     */
    abstract override val appId: Property<String>

    /** The platform block that holds this platform's differences and native settings. */
    internal fun platformExtension(p: KitePlatform): KitePlatformExtension = when (p) {
        KitePlatform.ANDROID -> android
        KitePlatform.IOS -> ios
        KitePlatform.DESKTOP -> desktop
    }

    internal fun effectiveAppNameFor(p: KitePlatform): Provider<String> =
        platformExtension(p).appName.orElse(appName)

    /** A platform's own identifier, otherwise the shared base plus that platform's suffix. */
    internal fun effectiveAppIdFor(p: KitePlatform): Provider<String> {
        val platform = platformExtension(p)
        return platform.appId.orElse(
            appId.zip(platform.appIdDetails.suffix.orElse("")) { base, suffix -> base + suffix },
        )
    }

    // ------------------------------------------------------------------ Splash

    /** The splash topic. Off until `splash { enabled = true }`. */
    fun splash(action: Action<in KiteSplashScope>) = action.execute(splash)

    internal val splash: KiteSplashScope
        get() = nested()

    /** Turned on for [p] when that platform, or the root, asked for a launch screen. */
    internal fun splashEnabledFor(p: KitePlatform): Provider<Boolean> =
        platformExtension(p).splashDetails.enabled.orElse(splash.enabled).orElse(false)

    internal fun splashFlowsTo(p: KitePlatform): Provider<Boolean> =
        flowsTo(p).zip(splashEnabledFor(p)) { selected, on -> selected && on }

    /** Splash art for [p]: its own, the shared one, otherwise that platform's logo. */
    internal fun effectiveSplashImageFor(p: KitePlatform): Provider<org.gradle.api.file.RegularFile> =
        platformExtension(p).splashDetails.image
            .orElse(splash.image)
            .orElse(effectiveLogoForegroundFor(p))

    /** The plate for [p]. A logo with an image background supplies no color. */
    internal fun effectiveSplashColorFor(p: KitePlatform): Provider<String> =
        platformExtension(p).splashDetails.backgroundColor
            .orElse(splash.backgroundColor)
            .orElse(effectiveLogoBackgroundColorFor(p))

    /** Dark resources are produced once a dark value exists, unless a platform declines. */
    internal fun splashDarkEnabledFor(p: KitePlatform): Provider<Boolean> {
        val declared = effectiveSplashDarkImageFor(p).map { true }
            .orElse(effectiveSplashDarkColorFor(p).map { true })
            .orElse(false)
        return platformExtension(p).splashDetails.dark.enabled
            .orElse(splash.dark.enabled)
            .orElse(declared)
    }

    internal fun effectiveSplashDarkImageFor(p: KitePlatform): Provider<org.gradle.api.file.RegularFile> =
        platformExtension(p).splashDetails.dark.image.orElse(splash.dark.image)

    internal fun effectiveSplashDarkColorFor(p: KitePlatform): Provider<String> =
        platformExtension(p).splashDetails.dark.backgroundColor.orElse(splash.dark.backgroundColor)

    internal val effectiveSplashImage: Provider<org.gradle.api.file.RegularFile>
        get() = effectiveSplashImageFor(KitePlatform.ANDROID)

    internal val effectiveSplashColor: Provider<String>
        get() = effectiveSplashColorFor(KitePlatform.ANDROID)

    internal val effectiveSplashDarkImage: Provider<org.gradle.api.file.RegularFile>
        get() = effectiveSplashDarkImageFor(KitePlatform.ANDROID)

    internal val effectiveSplashDarkColor: Provider<String>
        get() = effectiveSplashDarkColorFor(KitePlatform.ANDROID)

    internal val effectiveSplashAndroidTheme: Provider<String>
        get() = android.androidSplash.theme

    internal val effectiveAndroidSplash: Provider<Boolean>
        get() = splashFlowsTo(KitePlatform.ANDROID)

    internal val effectiveDesktopSplash: Provider<Boolean>
        get() = splashFlowsTo(KitePlatform.DESKTOP)

    internal val effectiveIosSplash: Provider<Boolean>
        get() = splash.rewriteArmed.orElse(false)
            .zip(effectiveSyncIos) { armed, sync -> armed && sync }
            .zip(splashFlowsTo(KitePlatform.IOS)) { on, flows -> on && flows }

    internal fun appNameFlowsTo(p: KitePlatform): Provider<Boolean> =
        flowsTo(p).zip(platformExtension(p).appNameDetails.enabled.orElse(true)) { selected, delivered ->
            selected && delivered
        }

    internal fun appIdFlowsTo(p: KitePlatform): Provider<Boolean> =
        flowsTo(p).zip(platformExtension(p).appIdDetails.enabled.orElse(true)) { selected, delivered ->
            selected && delivered
        }

    /**
     * The release version you show to users, as `x.y.z`.
     *
     * Android uses it for `versionName`, Apple for the marketing version, and
     * the build-number formula derives every store counter from it.
     */
    abstract override val version: Property<String>

    /** The JVM topic: the toolchain that builds and the bytecode level it emits. */
    fun jvm(action: Action<in KiteJvmScope>) = action.execute(jvmScope)

    internal val jvmScope: KiteJvmScope
        get() = nested()

    /** The bytecode level [p] compiles to, its own if it set one. */
    internal fun effectiveJvmTargetFor(p: KitePlatform): Provider<Int> = when (p) {
        KitePlatform.ANDROID -> android.jvmDetails.target.orElse(jvmScope.target)
        KitePlatform.DESKTOP -> desktop.jvmDetails.target.orElse(jvmScope.target)
        KitePlatform.IOS -> jvmScope.target
    }

    /** The JDK [p] builds with, its own if it set one. */
    internal fun effectiveJvmToolchainFor(p: KitePlatform): Provider<Int> = when (p) {
        KitePlatform.ANDROID -> android.jvmDetails.toolchain.orElse(jvmScope.toolchain)
        KitePlatform.DESKTOP -> desktop.jvmDetails.toolchain.orElse(jvmScope.toolchain)
        KitePlatform.IOS -> jvmScope.toolchain
    }

    /** The shared bytecode level, readable from any build file. */
    override val jvmTarget: Provider<Int>
        get() = jvmScope.target

    /** The shared JDK selection, readable from any build file. */
    override val jvmToolchain: Provider<Int>
        get() = jvmScope.toolchain

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
     * App icon art. Configure with `kiteConfig { logo { } }`.
     *
     * The topic is on as soon as [KiteLogoScope.foreground] is set.
     */
    val logo: KiteLogoScope
        get() = nested()

    /** Point KiteConfig at your foreground and background art. */
    fun logo(action: Action<in KiteLogoScope>) {
        logoConfigured.set(true)
        action.execute(logo)
    }

    /** The art [p] uses, its own if it set some. */
    internal fun effectiveLogoForegroundFor(p: KitePlatform): Provider<org.gradle.api.file.RegularFile> =
        platformExtension(p).logoDetails.foreground.orElse(logo.foreground)

    internal fun effectiveLogoBackgroundFor(p: KitePlatform): Provider<LogoBackground> =
        platformExtension(p).logoDetails.background.orElse(logo.background)

    /** The background image, absent when the background is a color or unset. */
    internal fun effectiveLogoBackgroundImageFor(p: KitePlatform): Provider<java.io.File> =
        effectiveLogoBackgroundFor(p).map { (it as? LogoBackground.Image)?.file }

    /** The background color, absent when the background is an image or unset. */
    internal fun effectiveLogoBackgroundColorFor(p: KitePlatform): Provider<String> =
        effectiveLogoBackgroundFor(p).map { (it as? LogoBackground.Color)?.hex }

    /** How much of the canvas [p] gives the mark, when the build asked for a ratio. */
    internal fun effectiveLogoScaleFor(p: KitePlatform): Provider<Double> =
        platformExtension(p).logoDetails.foregroundScale.orElse(logo.foregroundScale)

    /** The topic is on for [p] once art exists, unless that platform turned it off. */
    internal fun logoFlowsTo(p: KitePlatform): Provider<Boolean> =
        flowsTo(p)
            .zip(platformExtension(p).logoDetails.enabled.orElse(logoDeclared)) { selected, on ->
                selected && on
            }

    /** The shared plate color, absent when the shared background is an image. */
    internal val rootLogoBackgroundColor: Provider<String>
        get() = logo.background.map { (it as? LogoBackground.Color)?.hex }

    /** Art was configured somewhere, which is what turns the topic on. */
    internal val logoDeclared: Provider<Boolean>
        get() = logo.foreground.map { true }.orElse(false)

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

    /** Android application id: the effective [appId] for Android. */
    override val androidApplicationId: Provider<String>
        get() = effectiveAppIdFor(KitePlatform.ANDROID)

    /** Apple bundle id: the effective [appId] for iOS. */
    override val iosBundleId: Provider<String>
        get() = effectiveAppIdFor(KitePlatform.IOS)

    /**
     * Desktop bundle id: the effective [appId] for desktop.
     *
     * @throws org.gradle.api.GradleException when read, if the result is not a
     *   valid reverse-DNS identifier.
     */
    override val desktopBundleId: Provider<String>
        get() = effectiveAppIdFor(KitePlatform.DESKTOP).map(::validateAppleBundleId)

    /** The identifier [platform] receives, suffix or exact override applied. */
    override fun appIdFor(platform: KitePlatform): Provider<String> =
        effectiveAppIdFor(platform)

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
        get() = jvmScope.target

    internal val effectiveJvmToolchain: Provider<Int>
        get() = jvmScope.toolchain

    /** The locales topic: the tag list every platform starts from. */
    fun locales(action: Action<in KiteLocalesScope>) = action.execute(localesScope)

    internal val localesScope: KiteLocalesScope
        get() = nested()

    internal fun localesFlowsTo(p: KitePlatform): Provider<Boolean> =
        flowsTo(p).zip(platformExtension(p).localesDetails.enabled.orElse(true)) { selected, delivered ->
            selected && delivered
        }

    /** The tags [p] ships: its own list if it declared one, otherwise the shared list. */
    internal fun effectiveLocalesFor(p: KitePlatform): Provider<List<String>> =
        platformExtension(p).localesDetails.tags

    private fun anywhere(gate: (KitePlatform) -> Provider<Boolean>): Provider<Boolean> =
        gate(KitePlatform.ANDROID).zip(gate(KitePlatform.IOS)) { a, i -> a || i }
            .zip(gate(KitePlatform.DESKTOP)) { x, d -> x || d }

    internal fun appNameFlowsAnywhere(): Provider<Boolean> = anywhere(::appNameFlowsTo)
    internal fun appIdFlowsAnywhere(): Provider<Boolean> = anywhere(::appIdFlowsTo)
    internal fun localesFlowsAnywhere(): Provider<Boolean> = anywhere(::localesFlowsTo)

    internal val effectiveLocales: Provider<List<String>>
        get() = localesScope.tags

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
        get() = android.androidLocales.filterResources.orElse(false)

    // --- version numbers ---

    /** Details for [version]: the shared scheme and rebuild counter. */
    fun version(action: Action<in KiteVersionScope>) = action.execute(versionScope)

    internal val versionScope: KiteVersionScope
        get() = nested()

    internal fun versionFlowsTo(p: KitePlatform): Provider<Boolean> =
        flowsTo(p).zip(platformExtension(p).versionDetails.enabled.orElse(true)) { selected, delivered ->
            selected && delivered
        }

    /** The release version [p] shows, its own if it set one. */
    internal fun effectiveVersionFor(p: KitePlatform): Provider<String> =
        platformExtension(p).version.orElse(version)

    private fun effectiveRebuildFor(p: KitePlatform): Provider<Int> =
        platformExtension(p).versionDetails.rebuild.orElse(versionScope.rebuild).orElse(0)

    private fun effectiveSchemeFor(p: KitePlatform): Provider<VersionCodeScheme> =
        platformExtension(p).versionDetails.schemeProp
            .orElse(versionScope.schemeProp)
            .orElse(VersionSchemes.DEFAULT)

    /** The build number [p] receives: its own exact value, otherwise the scheme's answer. */
    internal fun effectiveBuildNumberFor(p: KitePlatform): Provider<String> =
        platformExtension(p).versionDetails.buildNumber.orElse(
            effectiveVersionFor(p).zip(
                effectiveRebuildFor(p).zip(effectiveSchemeFor(p)) { rebuild, scheme -> rebuild to scheme },
            ) { version, (rebuild, scheme) ->
                computeBuildNumber(scheme, version, rebuild, p.name.lowercase())
            },
        )

    internal val effectiveAndroidVersionCode: Provider<Int>
        get() = effectiveBuildNumberFor(KitePlatform.ANDROID).map(::androidVersionCodeFrom)

    /** True only when Android was given an exact number, so no scheme was consulted. */
    internal val effectiveHasExplicitVersionCode: Provider<Boolean>
        get() = android.versionDetails.buildNumber.map { true }.orElse(false)

    internal val effectiveIosBuildNumber: Provider<String>
        get() = effectiveBuildNumberFor(KitePlatform.IOS)

    internal val effectiveIosMarketingVersion: Provider<String>
        get() = effectiveVersionFor(KitePlatform.IOS)

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

    internal val effectiveLogoBackground: Provider<java.io.File>
        get() = effectiveLogoBackgroundImageFor(KitePlatform.ANDROID)

    internal val effectiveLogoBackgroundColor: Provider<String>
        get() = effectiveLogoBackgroundColorFor(KitePlatform.ANDROID)

    /** Android's adaptive safe zone: the shared scale when set, else Android's own ratio. */
    internal val effectiveLogoSafeZone: Provider<Double>
        get() = effectiveLogoScaleFor(KitePlatform.ANDROID).orElse(DEFAULT_ANDROID_SAFE_ZONE)

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
        get() = effectiveBuildNumberFor(KitePlatform.DESKTOP)

    // Logo art flowing to desktop: presence of art, not an armed rewrite.
    internal val effectiveDesktopIcons: Provider<Boolean>
        get() = logoFlowsTo(KitePlatform.DESKTOP)

    internal companion object {
        internal const val DEFAULT_ANDROID_SAFE_ZONE: Double = 66.0 / 108.0
        internal const val DEFAULT_GENERATED_PACKAGE: String = "kiteconfig.generated"
    }
}
