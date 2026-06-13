package io.github.yuroyami.kmpssot

import org.gradle.api.provider.Property

/**
 * Android-only SDK options. Nested under `kmpSsot { android { ... } }`.
 *
 * Every value is optional (`Property` with no convention). A field is
 * propagated into each Android module (application + library) iff it is set
 * **and** the master `propagateAndroidSdk` toggle is on (default). Leave a
 * field unset to keep whatever the module declares itself.
 *
 * These are deliberately *not* part of cross-platform identity — they're the
 * Android toolchain knobs that otherwise get copy-pasted across every Android
 * module in a KMP project (`compileSdk`/`minSdk`/`targetSdk`/`ndkVersion`).
 * Centralising them here is the same SSOT win as appName/version, scoped to
 * Android.
 *
 *     kmpSsot {
 *         android {
 *             compileSdk = 36
 *             minSdk     = 26
 *             targetSdk  = 36
 *             ndkVersion = "27.0.12077973"
 *         }
 *     }
 */
abstract class KmpSsotAndroidExtension {

    /** `compileSdk` for every Android module (application + library). */
    abstract val compileSdk: Property<Int>

    /** `defaultConfig.minSdk` for every Android module (application + library). */
    abstract val minSdk: Property<Int>

    /**
     * `defaultConfig.targetSdk` for the Android **application** module(s).
     * Library modules have no `targetSdk` (AGP removed it), so this is ignored
     * there.
     */
    abstract val targetSdk: Property<Int>

    /** `ndkVersion` for every Android module (application + library). */
    abstract val ndkVersion: Property<String>
}
