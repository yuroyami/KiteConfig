package io.github.yuroyami.kiteconfig

import org.gradle.api.provider.Property

/**
 * Details for [KiteConfigExtension.version], inside `kiteConfig { version { ... } }`.
 *
 * The release version itself is the scalar next to this block. This block says
 * how a build number is derived from it.
 *
 * | member | meaning | default |
 * |---|---|---|
 * | `scheme(...)` | how the version becomes a build number | [VersionSchemes.DEFAULT] |
 * | `rebuild` | extra counter for re-uploading the same version | 0 |
 *
 * ```kotlin
 * kiteConfig {
 *     version = "1.4.0"
 *     version {
 *         rebuild = 1
 *     }
 * }
 * ```
 *
 * A platform block carries the same members plus its own exact build number and
 * published baseline. See [KitePlatformVersionScope].
 */
@KiteDsl
abstract class KiteVersionScope {

    internal abstract val schemeProp: Property<VersionCodeScheme>

    /**
     * Extra counter for re-uploading the same release version to a store.
     * Default: `0`. The scheme reads it.
     */
    abstract val rebuild: Property<Int>

    /** Use a built-in scheme, for example `scheme(VersionSchemes.DEFAULT)`. */
    fun scheme(scheme: VersionCodeScheme) = schemeProp.set(scheme)
}

/**
 * A platform's version details, inside `kiteConfig { android { version { ... } } }`.
 *
 * Everything in [KiteVersionScope] applies here too, for this platform only.
 *
 * | member | meaning | default |
 * |---|---|---|
 * | `buildNumber` | exact build number, the scheme is skipped | derived |
 * | `publishedBuildNumber` | highest number already shipped here | none |
 * | `enabled` | deliver the version to this platform | true |
 *
 * ```kotlin
 * kiteConfig {
 *     version = "1.4.0"
 *     android {
 *         version {
 *             rebuild = 1
 *             publishedBuildNumber = "1001004001"
 *         }
 *     }
 * }
 * ```
 *
 * Both numbers are text at this boundary. Android reads its own as a decimal
 * integer and holds it to the store's range; Apple compares by numeric
 * component, so a dotted build number is fine there.
 *
 * Publication histories are separate. A baseline set here says nothing about
 * any other platform.
 */
@KiteDsl
abstract class KitePlatformVersionScope : KiteVersionScope() {

    /**
     * Exact build number for this platform. The scheme is not consulted.
     *
     * Android needs a plain decimal integer inside the store's range.
     */
    abstract val buildNumber: Property<String>

    /**
     * The highest build number already published for this platform.
     *
     * The resolved number must beat it, or the build fails before release.
     * Nothing contacts a store; this is a value you keep yourself.
     */
    abstract val publishedBuildNumber: Property<String>

    /** Deliver the version and build number to this platform. Default: `true`. */
    abstract val enabled: Property<Boolean>
}
