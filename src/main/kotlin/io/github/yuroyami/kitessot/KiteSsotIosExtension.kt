package io.github.yuroyami.kitessot

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Property

/**
 * Apple settings inside `kiteSsot { ios { ... } }`.
 *
 * The root block holds the truth about your app: its name, its version, its ID.
 * This block holds only what Apple needs on top of that: the bundle suffix, the
 * two version fields, and where your Xcode tree lives.
 *
 * ```kotlin
 * kiteSsot {
 *     appName = "Jetzy"
 *     version = "1.4.0"
 *     appId = "com.example.jetzy"
 *
 *     ios {
 *         bundleIdSuffix = ".iosApp"
 *         rebuild = 3
 *
 *         sync {
 *             targets("iosApp")
 *         }
 *     }
 * }
 * ```
 *
 * Every path here already points at the standard Kotlin Multiplatform layout.
 * Set one only when your tree looks different.
 *
 * Nothing in this block writes a file on its own. Apple sources change only
 * when [sync] is configured and you run a sync task yourself.
 *
 * There is one derived read-only value, `bundleId: Provider<String>`: the root
 * `appId` followed by [bundleIdSuffix].
 */
abstract class KiteSsotIosExtension {

    // --- Identity -------------------------------------------------------------

    /**
     * Appended to the root `appId` to build the Apple bundle ID.
     *
     * Default: empty, so the bundle ID equals `appId`. A common value is
     * `".iosApp"`, which turns `com.example.jetzy` into `com.example.jetzy.iosApp`.
     */
    abstract val bundleIdSuffix: Property<String>

    // --- Versions -------------------------------------------------------------

    /**
     * The version users read in the App Store, written as
     * `CFBundleShortVersionString` and `MARKETING_VERSION`.
     *
     * Default: the root `version`. It needs three numeric parts, like `1.4.0`.
     */
    abstract val marketingVersion: Property<String>

    /**
     * The upload counter, written as `CURRENT_PROJECT_VERSION`. One to three
     * numeric dot parts, for example `"42"` or `"1001004000"`.
     *
     * Default: the result of the root `scheme`, rendered as a string. Assigning
     * a value bypasses the scheme, so [rebuild] stops having any effect.
     */
    abstract val buildNumber: Property<String>

    /**
     * Feeds the scheme as `v.rebuild`, so a value of `3` turns `1001004000` into
     * `1001004003`.
     *
     * Default: `0`. TestFlight refuses a build number it has already seen for the
     * same marketing version, so bump this for every re-upload that does not
     * bump the version.
     *
     * It is separate from `android.rebuild` on purpose. Play and TestFlight burn
     * numbers on different days.
     */
    abstract val rebuild: Property<Int>

    /**
     * The formula that turns the version into a build number, for Apple only.
     *
     * Default: the root `scheme`. Set it only when the two platforms genuinely
     * need different formulas, which is rare.
     */
    abstract val scheme: Property<VersionCodeScheme>

    /**
     * Set [scheme] from a lambda: `scheme { v -> ... }`.
     *
     * The lambda receives `v.major`, `v.minor`, `v.patch`, and `v.rebuild`.
     * KiteSSOT renders the returned number as the Apple build number.
     */
    fun scheme(s: VersionCodeScheme) {
        scheme.set(s)
    }

    /**
     * The highest build number you already uploaded for the current marketing
     * version. The next resolved number must beat it.
     *
     * Default: unset, so no check runs. Comparison is componentwise and numeric,
     * with missing parts read as `0`, so `"2.1"` outranks `"2"`.
     *
     * The check is offline. KiteSSOT never contacts App Store Connect, and it
     * never writes this value into any file.
     */
    abstract val publishedBuildNumber: Property<String>

    // --- Assets ---------------------------------------------------------------

    /**
     * The lowest iOS version your app supports, such as `"14.0"`. The minimum
     * accepted value is `"12.0"`.
     *
     * KiteSSOT reads it only to check that the universal AppIcon asset is valid
     * for that version. It does NOT change Xcode's `IPHONEOS_DEPLOYMENT_TARGET`.
     * The single-size universal catalog also needs Xcode 14 or newer.
     */
    abstract val deploymentTarget: Property<String>

    // --- Paths ----------------------------------------------------------------

    /**
     * The Xcode project file that the explicit Apple tasks rewrite.
     *
     * Default: `iosApp/iosApp.xcodeproj/project.pbxproj`.
     */
    abstract val pbxproj: RegularFileProperty

    /**
     * The CocoaPods manifest read by the shared-module rename.
     *
     * Default: `iosApp/Podfile`.
     */
    abstract val podfile: RegularFileProperty

    /**
     * The source XML `Info.plist` that plist sanitization maintains.
     *
     * Default: `iosApp/iosApp/Info.plist`.
     */
    abstract val infoPlist: RegularFileProperty

    /**
     * The Apple source tree searched when Swift imports are migrated.
     *
     * Default: `iosApp`.
     */
    abstract val appDirectory: DirectoryProperty

    /**
     * Where the Apple app icon is installed.
     *
     * Default: `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset`.
     */
    abstract val appIconDirectory: DirectoryProperty

    // --- Nested blocks --------------------------------------------------------

    /**
     * Apple source sync policy. Configure it with
     * `kiteSsot { ios { sync { ... } } }`.
     */
    val sync: KiteSsotIosSyncExtension
        get() = (this as ExtensionAware).extensions.getByType(KiteSsotIosSyncExtension::class.java)

    /**
     * Configure the nested Apple sync model.
     *
     * Calling this authorizes the explicit Apple source tasks. It does not run
     * them. See [KiteSsotIosSyncExtension].
     */
    fun sync(action: Action<in KiteSsotIosSyncExtension>) {
        sync.configured.set(true)
        action.execute(sync)
    }
}
