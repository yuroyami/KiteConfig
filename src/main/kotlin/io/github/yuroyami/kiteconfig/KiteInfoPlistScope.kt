package io.github.yuroyami.kiteconfig

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * Facts your app declares in its `Info.plist`, inside
 * `kiteConfig { ios { infoPlist { ... } } }`.
 *
 * These are app declarations, not rewrite options. They used to sit inside the
 * rewrite block, which made them read like switches for the machinery rather
 * than statements about the app.
 *
 * ```kotlin
 * kiteConfig {
 *     ios {
 *         infoPlist {
 *             proMotion = true
 *             nonExemptEncryption = false
 *         }
 *     }
 * }
 * ```
 *
 * A key you do not set is left exactly as it is.
 */
@KiteDsl
abstract class KiteInfoPlistScope {

    /**
     * The source XML `Info.plist` KiteConfig maintains.
     *
     * Default: `iosApp/iosApp/Info.plist`. Set it only when your tree differs.
     */
    abstract val path: RegularFileProperty

    /**
     * Value for `ITSAppUsesNonExemptEncryption`. Default: unset, key untouched.
     *
     * KiteConfig writes the boolean you choose. It does not determine your
     * export-control status, submit compliance documents, or give legal advice.
     */
    abstract val nonExemptEncryption: Property<Boolean>

    /**
     * Value for `CADisableMinimumFrameDurationOnPhone`. Default: unset, key untouched.
     *
     * Setting it true adds Apple's opt-in for lower minimum frame durations on
     * supported iPhone displays. It does not guarantee a refresh rate: the
     * device, the OS, the framework, and the workload still decide.
     */
    abstract val proMotion: Property<Boolean>
}
