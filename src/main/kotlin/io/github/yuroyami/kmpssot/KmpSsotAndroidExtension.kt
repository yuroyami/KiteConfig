package io.github.yuroyami.kmpssot

import org.gradle.api.provider.Property

/**
 * Android-only SDK options. Nested under `kmpSsot { android { ... } }`.
 *
 * Every value is optional (`Property` with no convention). The SDK fields are
 * propagated into each compatible Android module iff they are set and the root
 * `propagateAndroidSdk` toggle is on. Leave an SDK field unset to keep whatever
 * the module declares itself. [publishedVersionCode] is an offline validation
 * guard, not a value propagated into Android DSLs.
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

    /**
     * Optional highest version code already published to an Android store. When
     * set, model finalization fails unless the resolved next version code is
     * strictly greater while Android version propagation is active for a detected
     * application. This is an offline guard; the plugin never contacts a store or
     * guesses the baseline.
     */
    abstract val publishedVersionCode: Property<Int>

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

    /** `ndkVersion` for classic Android modules. AGP's KMP-native library DSL does not expose it. */
    abstract val ndkVersion: Property<String>
}
