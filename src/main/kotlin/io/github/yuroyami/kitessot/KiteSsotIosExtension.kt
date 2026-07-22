package io.github.yuroyami.kitessot

import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty

/**
 * iOS-specific options. Nested under `kiteSsot { ios { ... } }`.
 *
 * Every plist feature flag is optional (`Property<Boolean>` with no convention).
 * With root `syncIos=true` and `sanitizeIosProject=true`, a configured flag is
 * propagated into `Info.plist` by the explicit `kiteSsotSyncIosConfig` transaction
 * or the separately named `kiteSsotSanitizeIosProject` task: inserted when missing
 * and handled according to [plistConflictPolicy] when an existing value differs.
 * The default policy is [PlistConflictPolicy.FAIL], so divergence is never
 * overwritten implicitly. When unset, the key is left untouched.
 *
 * These differ from the SSOT-pointing string keys (`CFBundleDisplayName` etc.)
 * only in their value type. All conflicting existing values use the same
 * explicit FAIL/KEEP/REPLACE policy.
 */
abstract class KiteSsotIosExtension {

    /**
     * Declared minimum iOS deployment target used to validate compatibility of
     * generated assets. Required when Apple universal AppIcon propagation is
     * enabled and must be at least 12.0. This is an assertion, not a writer for
     * Xcode's `IPHONEOS_DEPLOYMENT_TARGET`; configure that setting in the Apple
     * project. Producing the single-size catalog also requires Xcode 14 or newer.
     */
    abstract val deploymentTarget: Property<String>

    /**
     * Exact Xcode application target names that may be changed by iOS migration tasks.
     * Empty (default) permits auto-selection only when the project contains exactly one
     * application target. Set names explicitly to select one or more app targets.
     */
    abstract val targetNames: ListProperty<String>

    /**
     * Policy for existing conflicting Info.plist values. Default [PlistConflictPolicy.FAIL]
     * makes drift visible; [PlistConflictPolicy.KEEP] preserves it and
     * [PlistConflictPolicy.REPLACE] explicitly authorizes replacement.
     */
    abstract val plistConflictPolicy: Property<PlistConflictPolicy>

    /**
     * Controls `ITSAppUsesNonExemptEncryption` in `Info.plist`.
     *
     * This property only writes the Boolean plist declaration; it does not
     * determine export-control status, file compliance documents, or provide
     * legal advice. Choose the value from your application's actual encryption
     * behavior and the current Apple/jurisdictional requirements.
     *
     * Unset (default): the key is not touched.
     */
    abstract val usesNonExemptEncryption: Property<Boolean>

    /**
     * Controls `CADisableMinimumFrameDurationOnPhone` in `Info.plist`.
     *
     * Set to `true` to set the Apple plist opt-in that permits lower minimum
     * frame durations on supported iPhone displays. It does not guarantee a
     * particular refresh rate or application performance; rendering cadence
     * remains subject to the device, OS, framework, and workload.
     *
     * Unset (default): the key is not touched.
     */
    abstract val proMotion120Hz: Property<Boolean>
}
