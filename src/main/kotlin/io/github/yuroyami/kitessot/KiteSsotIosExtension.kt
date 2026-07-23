package io.github.yuroyami.kitessot

import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty

/**
 * Apple platform settings inside `kiteSsot { ios { ... } }`.
 *
 * The Info.plist feature flags are optional and have no default. To apply a flag,
 * set both root options `syncIos = true` and `sanitizeIosProject = true`, then run
 * `kiteSsotSyncIosConfig` or `kiteSsotSanitizeIosProject`. KiteSSOT adds a missing
 * key. If the key already has a different value, [plistConflictPolicy] decides
 * what happens.
 *
 * The default conflict policy is [PlistConflictPolicy.FAIL], so KiteSSOT never
 * replaces a different value without permission. When a flag is unset, its plist
 * key is left unchanged. The same conflict policy also applies to SSOT string
 * keys such as `CFBundleDisplayName`.
 */
abstract class KiteSsotIosExtension {

    /**
     * The minimum iOS version that the app supports.
     *
     * This value is required when the universal iOS AppIcon installer is enabled,
     * and it must be at least `12.0`. KiteSSOT uses it only to check asset
     * compatibility. It does not change Xcode's `IPHONEOS_DEPLOYMENT_TARGET`.
     * The universal single-size catalog also requires Xcode 14 or newer.
     */
    abstract val deploymentTarget: Property<String>

    /**
     * Exact Xcode application targets whose build configurations may change.
     *
     * This selector scopes the `project.pbxproj` app settings. Project-level
     * locales and file-based plist, Podfile, and Swift work use their own paths.
     * The default is an empty list. When an enabled app-setting update needs a
     * target, an empty list can select only a sole application target.
     */
    abstract val targetNames: ListProperty<String>

    /**
     * What to do when an existing Info.plist value differs from the requested value.
     *
     * The default is [PlistConflictPolicy.FAIL]. [PlistConflictPolicy.KEEP] keeps
     * the existing value and [PlistConflictPolicy.REPLACE] allows KiteSSOT to
     * replace it.
     */
    abstract val plistConflictPolicy: Property<PlistConflictPolicy>

    /**
     * Optional value for `ITSAppUsesNonExemptEncryption` in `Info.plist`.
     *
     * KiteSSOT only writes the Boolean plist value. It does not determine your
     * export-control status, submit compliance documents, or provide legal advice.
     * Choose the value from the app's actual encryption behavior and the rules
     * that apply to it.
     *
     * The default is unset, which leaves the key unchanged.
     */
    abstract val usesNonExemptEncryption: Property<Boolean>

    /**
     * Optional value for `CADisableMinimumFrameDurationOnPhone` in `Info.plist`.
     *
     * Set it to `true` to add Apple's plist opt-in for lower minimum frame
     * durations on supported iPhone displays. This does not guarantee a refresh
     * rate or performance level. The device, OS, framework, and workload still
     * control rendering behavior.
     *
     * The default is unset, which leaves the key unchanged.
     */
    abstract val proMotion120Hz: Property<Boolean>
}
