package io.github.yuroyami.kiteconfig

import org.gradle.api.Action
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Property

/**
 * What every platform block shares: the app facts it may differ on.
 *
 * A platform block holds two kinds of thing. Differences from the shared facts
 * live here. Settings that mean nothing anywhere else live on the block itself,
 * so `sdk(...)` exists only on Android and `roundMac` only on desktop.
 *
 * ```kotlin
 * kiteConfig {
 *     appName = "Jetzy"
 *     appId   = "com.example.jetzy"
 *
 *     android {
 *         appId { suffix = ".android" }   // com.example.jetzy.android
 *     }
 *     desktop {
 *         appName = "Jetzy Desktop"       // only this platform differs
 *     }
 * }
 * ```
 *
 * Nothing is copied when the block opens. A leaf you do not set here keeps
 * following the shared value, whichever order the two were written in.
 *
 * @see KiteAppIdScope for the suffix and the delivery switch.
 * @see KiteAppNameScope for the name's delivery switch.
 */
@KiteDsl
abstract class KitePlatformExtension : KitePlatformRef {

    /**
     * The display name on this platform. Unset means the shared `appName`.
     */
    abstract val appName: Property<String>

    /**
     * The exact application identifier on this platform.
     *
     * Unset means the shared `appId` plus this platform's [KiteAppIdScope.suffix].
     * Setting it here replaces both.
     */
    abstract val appId: Property<String>

    /**
     * The release version on this platform. Unset means the shared `version`.
     *
     * On iOS this is the marketing version customers see.
     */
    abstract val version: Property<String>

    /** This platform's build number, counter, scheme, and delivery switch. */
    fun version(action: Action<in KitePlatformVersionScope>) = action.execute(versionDetails)

    internal val versionDetails: KitePlatformVersionScope
        get() = (this as ExtensionAware).extensions.getByType(KitePlatformVersionScope::class.java)

    /** Whether this platform receives the app name. */
    fun appName(action: Action<in KiteAppNameScope>) = action.execute(appNameDetails)

    /** This platform's identifier suffix and delivery switch. */
    fun appId(action: Action<in KiteAppIdScope>) = action.execute(appIdDetails)

    internal val splashDetails: KiteSplashScope
        get() = (this as ExtensionAware).extensions.getByType(KiteSplashScope::class.java)

    internal val logoDetails: KiteLogoScope
        get() = (this as ExtensionAware).extensions.getByType(KiteLogoScope::class.java)

    internal val localesDetails: KiteLocalesScope
        get() = (this as ExtensionAware).extensions.getByType(KiteLocalesScope::class.java)

    internal val appNameDetails: KiteAppNameScope
        get() = (this as ExtensionAware).extensions.getByType(KiteAppNameScope::class.java)

    internal val appIdDetails: KiteAppIdScope
        get() = (this as ExtensionAware).extensions.getByType(KiteAppIdScope::class.java)
}
