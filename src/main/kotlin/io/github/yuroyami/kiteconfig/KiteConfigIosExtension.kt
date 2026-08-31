package io.github.yuroyami.kiteconfig

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Property

/**
 * Apple settings inside `kiteConfig { ios { ... } }`.
 *
 * The root block holds the truth about your app: its name, its version, its ID.
 * This block holds only what Apple needs on top of that: the bundle suffix, the
 * two version fields, and where your Xcode tree lives.
 *
 * ```kotlin
 * kiteConfig {
 *     appName = "Jetzy"
 *     id("com.example.jetzy") { ios { suffix = ".iosApp" } }
 *     version("1.4.0") { ios { reupload = 3 } }
 *
 *     ios {
 *         rewrite {
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
 *
 * ## The two Apple version fields
 *
 * Apple splits what Android keeps in one place. Getting them confused is the
 * usual cause of a rejected TestFlight upload.
 *
 * | Field | Xcode setting | Who sees it | Rule |
 * |---|---|---|---|
 * | [marketingVersion] | `MARKETING_VERSION` | App Store customers | may repeat across uploads |
 * | [buildNumber] | `CURRENT_PROJECT_VERSION` | TestFlight testers | must be new for each upload of the same version |
 *
 * So a re-upload of `1.4.0` keeps the marketing version and needs a fresh build
 * number. Turn [rebuild] rather than inventing a version nobody shipped.
 *
 * ## What this block does and does not touch
 *
 * | Property | Written into your Xcode project |
 * |---|---|
 * | [bundleIdSuffix], [marketingVersion], [buildNumber] | yes, by an explicit [sync] task |
 * | [deploymentTarget] | **no**, it only validates the AppIcon catalog |
 * | [pbxproj], [podfile], [infoPlist], [appDirectory], [appIconDirectory] | no, they say where to look |
 *
 * @see KiteConfigIosSyncExtension for the gate that authorizes those writes.
 * @see KiteConfigAndroidExtension for the Android half of the same identity.
 * @see KiteConfigExtension.scheme for the formula both platforms share.
 */
abstract class KiteConfigIosExtension : KitePlatformRef {

    final override val platform: KitePlatform = KitePlatform.IOS

    // --- Identity -------------------------------------------------------------


    // --- Versions -------------------------------------------------------------






    // --- Assets ---------------------------------------------------------------

    /**
     * The lowest iOS version your app supports, such as `"14.0"`. The minimum
     * accepted value is `"12.0"`.
     *
     * KiteConfig reads it only to check that the universal AppIcon asset is valid
     * for that version. It does NOT change Xcode's `IPHONEOS_DEPLOYMENT_TARGET`.
     * The single-size universal catalog also needs Xcode 14 or newer.
     *
     * Required before the Apple icon installer will run.
     *
     * @throws org.gradle.api.GradleException during configuration when the value
     *   is below `12.0` or is not a valid version string.
     * @see KiteConfigLogoExtension for the icon source this validates against.
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
     * `kiteConfig { ios { sync { ... } } }`.
     */
    val rewrite: KiteConfigIosSyncExtension
        get() = (this as ExtensionAware).extensions.getByType(KiteConfigIosSyncExtension::class.java)

    /**
     * Configure the nested Apple sync model.
     *
     * Calling this authorizes the explicit Apple source tasks. It does not run
     * them. See [KiteConfigIosSyncExtension].
     */
    fun rewrite(action: Action<in KiteConfigIosSyncExtension>) {
        rewrite.rewriteArmed.set(true)
        action.execute(rewrite)
    }
}
