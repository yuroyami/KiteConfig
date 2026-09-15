package io.github.yuroyami.kiteconfig

import org.gradle.api.Action
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * What a debug build changes, inside `kiteConfig { debug { ... } }`.
 *
 * The debug build is the one Android Studio and Xcode install while you work.
 * Give it its own identifier and it installs next to the released app instead
 * of replacing it. Give it its own name or icon and you can tell the two apart.
 *
 * ```kotlin
 * kiteConfig {
 *     appName = "Jetzy"
 *     appId   = "com.example.jetzy"
 *     debug {
 *         appId { suffix = ".debug" }               // com.example.jetzy.debug
 *         appName = "Jetzy Dev"
 *         logo { background = color("#B00020") }   // same mark, red plate
 *     }
 *     android { debug { appId { suffix = ".dev" } } }   // Android gets com.example.jetzy.dev
 * }
 * ```
 *
 * | member | meaning | default |
 * |---|---|---|
 * | `appName` | the debug build's display name | the platform's release name |
 * | `appId` | the debug build's exact identifier | the release id plus `appId { suffix }` |
 * | `appId { suffix }` | added to the end of the platform's release identifier | none |
 * | `logo { }` | the debug icon; leaves you leave unset follow the release icon | the release icon |
 *
 * Nothing here is on until you set a value, so an empty block changes nothing.
 * Each value is looked up closest first: the platform's own `debug { }`, then
 * this block, then that platform's release value.
 *
 * Android uses this for its `debug` build type, iOS for its `Debug` build
 * configuration. Desktop has no debug build, so `desktop { }` has no `debug { }`.
 */
@KiteDsl
public abstract class KiteDebugScope {

    /** The display name of the debug build. Unset means the release name. */
    public abstract val appName: Property<String>

    /**
     * The exact identifier of the debug build.
     *
     * Unset means the platform's release identifier plus the suffix from
     * `appId { suffix }`. Setting it here replaces both.
     */
    public abstract val appId: Property<String>

    /** Whether the debug build receives its own name. */
    public fun appName(action: Action<in KiteAppNameScope>): Unit = action.execute(appNameDetails)

    /** The debug identifier's suffix and delivery switch. */
    public fun appId(action: Action<in KiteAppIdScope>): Unit = action.execute(appIdDetails)

    /** The debug icon. Every leaf you leave unset follows the release icon. */
    public fun logo(action: Action<in KiteLogoScope>): Unit = action.execute(logoDetails)

    internal val appNameDetails: KiteAppNameScope
        get() = (this as ExtensionAware).extensions.getByType(KiteAppNameScope::class.java)

    internal val appIdDetails: KiteAppIdScope
        get() = (this as ExtensionAware).extensions.getByType(KiteAppIdScope::class.java)

    internal val logoDetails: KiteLogoScope
        get() = (this as ExtensionAware).extensions.getByType(KiteLogoScope::class.java)
}

/**
 * Android's debug corner, inside `kiteConfig { android { debug { ... } } }`.
 *
 * Everything in [KiteDebugScope] works here too, for Android only, plus the
 * build types that count as debug.
 */
@KiteDsl
public abstract class KiteAndroidDebugScope : KiteDebugScope() {

    /**
     * The Android build types that receive the debug values. Default: `debug`.
     * Spell each one exactly as the app module declares it.
     */
    public abstract val buildTypes: ListProperty<String>

    /** Replace the list of build types that count as debug. */
    public fun buildTypes(vararg names: String) {
        buildTypes.set(names.toList())
    }
}

/**
 * iOS's debug corner, inside `kiteConfig { ios { debug { ... } } }`.
 *
 * Everything in [KiteDebugScope] works here too, for iOS only, plus the Xcode
 * build configurations that count as debug.
 */
@KiteDsl
public abstract class KiteIosDebugScope : KiteDebugScope() {

    /**
     * The Xcode build configurations that receive the debug values. Default:
     * `Debug`, which is what Xcode's Run action builds. Spell each one exactly
     * as the Xcode project does.
     */
    public abstract val configurations: ListProperty<String>

    /** Replace the list of build configurations that count as debug. */
    public fun configurations(vararg names: String) {
        configurations.set(names.toList())
    }
}
