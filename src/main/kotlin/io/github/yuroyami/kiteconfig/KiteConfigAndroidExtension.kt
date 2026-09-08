package io.github.yuroyami.kiteconfig

import org.gradle.api.provider.Property

/**
 * Everything Android-only, inside `kiteConfig { android { ... } }`.
 *
 * ```kotlin
 * kiteConfig {
 *     appId = "com.example.jetzy"
 *     android { appId { suffix = ".debug" } }
 *     version = "1.4.0"
 *     android { version { rebuild = 1 } }
 *
 *     android {
 *         sdk(min = 26, target = 36, compile = 36)
 *     }
 * }
 * ```
 *
 * Shared truth stays at root. This block holds only the Android SDK and NDK
 * levels. The identifier suffix lives in `android { appId { suffix } }`, and the
 * build-number dials live in `version("x") { android { } }`.
 *
 * The resolved application id is readable as `kiteConfig.androidApplicationId`:
 * the root `appId` joined with that android suffix. Read it to wire other build
 * logic of your own. You cannot set it here.
 *
 * ## Which module types receive each SDK setting
 *
 * A library DSL simply has no `targetSdk`, and the KMP-native Android library
 * DSL has neither `targetSdk` nor `ndkVersion`, so those are skipped there and
 * reported rather than applied silently.
 *
 * | Setting | Application | Classic library | KMP-native library |
 * |---|---|---|---|
 * | [compileSdk] | yes | yes | yes |
 * | [minSdk] | yes | yes | yes |
 * | [targetSdk] | yes | not in the DSL | not in the DSL |
 * | [ndk] | yes | yes | not in the DSL |
 *
 * ## Picking the right version-code dial
 *
 * Build numbers are not set here. They live in the version topic, in its
 * `android { }` corner:
 *
 * | You want | Set | Effect |
 * |---|---|---|
 * | The usual: one code per release | nothing | the shared formula applies |
 * | To re-upload the same app version | `version("x") { android { reupload } }` | bumps the low digits only |
 * | A different formula on Android only | `version("x") { android { formula { } } }` | replaces the shared formula here |
 * | One exact number, no formula | `version("x") { android { pin } }` | bypasses the formula entirely |
 * | To be told before Play rejects you | `version("x") { android { shipped } }` | fails the build if the code did not grow |
 *
 * @see KiteVersionScope.formula for the formula every platform shares.
 * @see KiteVersionScope.AndroidCorner for the Android build-number corner.
 * @see KiteConfigIosExtension for the Apple half of the same identity.
 * @see KiteConfigModulesExtension.androidApps when more than one module is an application.
 */
@KiteDsl
abstract class KiteConfigAndroidExtension : KitePlatformExtension() {

    /** This platform's launch screen. */
    fun splash(action: org.gradle.api.Action<in KiteAndroidSplashScope>) = action.execute(androidSplash)

    internal val androidSplash: KiteAndroidSplashScope
        get() = (this as org.gradle.api.plugins.ExtensionAware)
            .extensions.getByType(KiteAndroidSplashScope::class.java)


    /** This platform's icon art and how much of the canvas the mark fills. */
    fun logo(action: org.gradle.api.Action<in KiteLogoScope>) = action.execute(logoDetails)


    /** The JVM toolchain and bytecode level for this platform's compilations. */
    fun jvm(action: org.gradle.api.Action<in KiteJvmScope>) = action.execute(jvmDetails)

    internal val jvmDetails: KiteJvmScope
        get() = (this as org.gradle.api.plugins.ExtensionAware)
            .extensions.getByType(KiteJvmScope::class.java)


    /** Android's locale list and its resource filter. */
    fun locales(action: org.gradle.api.Action<in KiteAndroidLocalesScope>) =
        action.execute(androidLocales)

    internal val androidLocales: KiteAndroidLocalesScope
        get() = (this as org.gradle.api.plugins.ExtensionAware)
            .extensions.getByType(KiteAndroidLocalesScope::class.java)


    final override val platform: KitePlatform = KitePlatform.ANDROID

    // --- Identity -------------------------------------------------------------


    // --- Version code ---------------------------------------------------------




    /** Set any subset of the three SDK levels in one line. */
    fun sdk(min: Int? = null, target: Int? = null, compile: Int? = null) {
        min?.let(minSdk::set)
        target?.let(targetSdk::set)
        compile?.let(compileSdk::set)
    }

    // --- SDK levels -----------------------------------------------------------

    /**
     * API level that supported Android applications and libraries compile against,
     * written as `compileSdk`.
     *
     * Default: unset, which leaves each module's own value alone.
     */
    abstract val compileSdk: Property<Int>

    /**
     * Lowest API level the app runs on, written as `defaultConfig.minSdk` in
     * supported Android applications and libraries.
     *
     * Default: unset, which leaves each module's own value alone.
     */
    abstract val minSdk: Property<Int>

    /**
     * API level the app declares it was built for, written as
     * `defaultConfig.targetSdk` in Android application modules.
     *
     * Default: unset. Android library modules do not expose this setting, so it
     * never reaches them.
     */
    abstract val targetSdk: Property<Int>

    /**
     * Exact NDK toolchain version for classic Android modules, in Android's
     * `major.minor.build` form, for example `27.0.12077973`.
     *
     * Default: unset. The KMP-native Android library DSL has no such setting, so
     * those modules ignore it.
     *
     * @throws org.gradle.api.GradleException during configuration when the value
     *   is not in Android's `major.minor.build` form.
     */
    abstract val ndk: Property<String>

    // --- Guards and gates -----------------------------------------------------



}
