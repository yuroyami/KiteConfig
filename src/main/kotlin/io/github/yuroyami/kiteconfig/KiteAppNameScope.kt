package io.github.yuroyami.kiteconfig

import org.gradle.api.provider.Property

/**
 * Details for a platform's [KitePlatformExtension.appName], inside
 * `kiteConfig { android { appName { ... } } }`.
 *
 * The name itself is the scalar next to this block. This block only decides
 * whether the platform receives it.
 *
 * | member | meaning | default |
 * |---|---|---|
 * | `enabled` | deliver the app name to this platform | true |
 *
 * ```kotlin
 * kiteConfig {
 *     appName = "Jetzy"
 *     ios { appName { enabled = false } }   // iOS keeps whatever it has
 * }
 * ```
 *
 * Turning delivery off keeps the value readable. `kiteConfig.appNameFor(IOS)`
 * still answers; only the write to the iOS project stops.
 */
@KiteDsl
abstract class KiteAppNameScope {

    /** Deliver the effective app name to this platform. Default: `true`. */
    abstract val enabled: Property<Boolean>
}
