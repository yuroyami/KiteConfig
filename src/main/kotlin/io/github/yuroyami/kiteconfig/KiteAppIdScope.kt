package io.github.yuroyami.kiteconfig

import org.gradle.api.provider.Property

/**
 * Details for a platform's [KitePlatformExtension.appId], inside
 * `kiteConfig { android { appId { ... } } }`.
 *
 * | member | meaning | default |
 * |---|---|---|
 * | `suffix` | appended to the shared `appId` | none |
 * | `enabled` | deliver the identifier to this platform | true |
 *
 * ```kotlin
 * kiteConfig {
 *     appId = "com.example.jetzy"
 *     android { appId { suffix = ".android" } }   // com.example.jetzy.android
 *     ios     { appId = "com.example.jetzy.ios" } // exact, no suffix involved
 * }
 * ```
 *
 * A platform's own `appId` is the final identifier for that platform. The
 * suffix belongs to the shared base, so setting one and then the other is not
 * a combination: the exact value wins and no suffix is appended.
 */
abstract class KiteAppIdScope {

    /**
     * Appended to the shared `appId` for this platform. Default: none.
     *
     * Ignored when the platform sets its own exact `appId`.
     */
    abstract val suffix: Property<String>

    /** Deliver the effective identifier to this platform. Default: `true`. */
    abstract val enabled: Property<Boolean>
}
