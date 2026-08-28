package io.github.yuroyami.kitessot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Apple source sync policy inside `kiteSsot { ios { sync { ... } } }`.
 *
 * Writing this block is the opt-in. Its presence is what authorizes the
 * explicit Apple source tasks, and it replaces the old `syncIos = true` flag.
 *
 * ```kotlin
 * kiteSsot {
 *     ios {
 *         sync {
 *             targets("iosApp")
 *             cleanPlist = true
 *             onConflict = PlistConflictPolicy.KEEP
 *
 *             renameSharedModule(from = "OldShared", to = "Shared")
 *         }
 *     }
 * }
 * ```
 *
 * Authorizing is not running. This block starts nothing. An ordinary build
 * still never touches an Apple source file. You run `kiteSsotSyncIosConfig`,
 * `kiteSsotSanitizeIosProject`, or `kiteSsotSyncIosLogo` yourself, and every
 * safety rail still applies: the root `dryRun` previews instead of writing, the
 * root `backups` keeps recovery copies, and [onConflict] decides what happens
 * when an existing plist value disagrees with yours.
 *
 * ## Which task this block unlocks
 *
 * | Task | Writes | Also needs |
 * |---|---|---|
 * | `kiteSsotSyncIosConfig` | `project.pbxproj`, Podfile, Swift imports | nothing else |
 * | `kiteSsotSanitizeIosProject` | source `Info.plist` | [cleanPlist] |
 * | `kiteSsotSyncIosLogo` | `AppIcon.appiconset` | a `logo { }` block and [KiteSsotIosExtension.deploymentTarget] |
 *
 * ## What happens on a plist conflict
 *
 * | [onConflict] | Existing value | Build |
 * |---|---|---|
 * | [PlistConflictPolicy.FAIL] (default) | untouched | fails, nothing is written |
 * | [PlistConflictPolicy.KEEP] | kept | continues with a warning |
 * | [PlistConflictPolicy.REPLACE] | overwritten | continues |
 *
 * @see KiteSsotIosExtension for the values these tasks propagate.
 * @see PlistConflictPolicy for the policy this block selects.
 */
abstract class KiteSsotIosSyncExtension {


    /**
     * The exact Xcode application targets whose build configurations may change.
     *
     * This scopes the `project.pbxproj` app settings only. Project-level locales
     * and the file-based plist, Podfile, and Swift work use their own paths.
     *
     * Default: empty. An empty list can still select a sole application target
     * when an enabled update needs one.
     *
     * @throws org.gradle.api.GradleException at task time when a named target is
     *   missing, or when the list is empty and the project holds more than one
     *   application target, which would make the choice a guess.
     */
    abstract val targets: ListProperty<String>

    /** Add Xcode application target names, for example `targets("iosApp")`. */
    fun targets(vararg names: String) {
        targets.addAll(*names)
    }

    /**
     * Whether KiteSSOT maintains its own keys in the source XML `Info.plist`.
     *
     * Default: `false`. Turn it on before setting [nonExemptEncryption] or
     * [proMotion], since those keys live in that file.
     *
     * @throws org.gradle.api.GradleException at task time when the project
     *   generates its `Info.plist` (`GENERATE_INFOPLIST_FILE`), because there is
     *   no source file to maintain.
     */
    abstract val cleanPlist: Property<Boolean>

    /**
     * What to do when an existing `Info.plist` value differs from the requested
     * value.
     *
     * Default: [PlistConflictPolicy.FAIL], so KiteSSOT never replaces a value
     * you did not agree to lose. [PlistConflictPolicy.KEEP] keeps what is there
     * and warns. [PlistConflictPolicy.REPLACE] lets KiteSSOT overwrite it.
     *
     * The same policy also covers SSOT string keys such as `CFBundleDisplayName`.
     */
    abstract val onConflict: Property<PlistConflictPolicy>

    /**
     * Value for `ITSAppUsesNonExemptEncryption` in `Info.plist`.
     *
     * Default: unset, which leaves the key unchanged.
     *
     * KiteSSOT only writes the Boolean plist value. It does not determine your
     * export-control status, submit compliance documents, or provide legal
     * advice. Choose the value from the app's actual encryption behavior and the
     * rules that apply to it.
     */
    abstract val nonExemptEncryption: Property<Boolean>

    /**
     * Value for `CADisableMinimumFrameDurationOnPhone` in `Info.plist`.
     *
     * Set it to `true` to add Apple's plist opt-in for lower minimum frame
     * durations on supported iPhone displays.
     *
     * Default: unset, which leaves the key unchanged.
     *
     * This does not guarantee a refresh rate or performance level. The device,
     * OS, framework, and workload still control rendering behavior.
     */
    abstract val proMotion: Property<Boolean>

    /**
     * Rename the CocoaPods and Swift module across the Apple sources: `from` is
     * the name in the files today, `to` is the name you want.
     *
     * Calling this is the opt-in for the Podfile and Swift migration. One call
     * replaces the old `propagateSharedModule`, `iosSharedModuleName`, and
     * `iosPreviousSharedModuleName` trio.
     *
     * ```kotlin
     * renameSharedModule(from = "OldShared", to = "Shared")
     * ```
     *
     * This rewrites your Swift source. Run it once, read the diff, and commit.
     * The root `dryRun` shows the plan first, and `backups` keeps a recovery
     * copy.
     */
    fun renameSharedModule(from: String, to: String) {
        previousSharedModuleName.set(from)
        newSharedModuleName.set(to)
    }

    /** Set when `ios { rewrite { } }` arms the Xcode tasks. */
    internal abstract val rewriteArmed: Property<Boolean>

    /** Backing field for the `from` argument of [renameSharedModule]. */
    internal abstract val previousSharedModuleName: Property<String>

    /** Backing field for the `to` argument of [renameSharedModule]. */
    internal abstract val newSharedModuleName: Property<String>
}
