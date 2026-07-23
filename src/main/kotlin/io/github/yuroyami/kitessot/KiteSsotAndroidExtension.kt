package io.github.yuroyami.kitessot

import org.gradle.api.provider.Property

/**
 * Android build settings inside `kiteSsot { android { ... } }`.
 *
 * Every value is optional and has no default. When the root
 * `propagateAndroidSdk` setting is `true`, which is the default, KiteSSOT copies
 * each SDK value you set into supported Android modules. An unset value leaves
 * the module's own setting unchanged.
 *
 * These values control the Android toolchain. They are separate from
 * cross-platform app identity. [publishedVersionCode] is only an offline release
 * check and is never copied into an Android DSL.
 *
 * ```kotlin
 * kiteSsot {
 *     android {
 *         compileSdk = 36
 *         minSdk = 26
 *         targetSdk = 36
 *         ndkVersion = "27.0.12077973"
 *     }
 * }
 * ```
 */
abstract class KiteSsotAndroidExtension {

    /**
     * The highest version code you have already published.
     *
     * This is optional. When Android version propagation is active for a detected
     * application, the next resolved version code must be greater than this value.
     * KiteSSOT does not contact an app store or guess this value.
     */
    abstract val publishedVersionCode: Property<Int>

    /** Optional `compileSdk` for every supported Android application and library. */
    abstract val compileSdk: Property<Int>

    /** Optional `defaultConfig.minSdk` for every supported Android application and library. */
    abstract val minSdk: Property<Int>

    /**
     * Optional `defaultConfig.targetSdk` for Android application modules.
     *
     * Android library modules do not expose `targetSdk`, so this value does not
     * apply to them.
     */
    abstract val targetSdk: Property<Int>

    /**
     * Optional `ndkVersion` for classic Android modules.
     *
     * The KMP-native Android library DSL does not expose this setting.
     */
    abstract val ndkVersion: Property<String>
}
