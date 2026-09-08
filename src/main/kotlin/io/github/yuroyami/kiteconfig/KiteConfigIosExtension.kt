package io.github.yuroyami.kiteconfig

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.ListProperty
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
 *     appId = "com.example.jetzy"
 *     ios { appId { suffix = ".iosApp" } }
 *     version = "1.4.0"
 *     ios { version { rebuild = 3 } }
 *
 *     ios {
 *         infoPlist { proMotion = true }
 *     }
 * }
 * ```
 *
 * Every path here already points at the standard Kotlin Multiplatform layout.
 * Set one only when your tree looks different.
 *
 * Nothing in this block writes a file on its own. Apple sources change only
 * when `autoApply` is on, or you run `kiteApply` yourself.
 *
 * The resolved bundle id is readable as `kiteConfig.iosBundleId`: the root `appId`
 * followed by the suffix from `ios { appId { suffix } }`.
 *
 * ## The two Apple version fields
 *
 * Apple splits what Android keeps in one place. Getting them confused is the
 * usual cause of a rejected TestFlight upload.
 *
 * Both are set in the version topic's `ios { }` corner, not here.
 *
 * | Field | Set with | Xcode setting | Who sees it | Rule |
 * |---|---|---|---|---|
 * | Marketing version | `ios { version = "..." }`, else the shared `version` | `MARKETING_VERSION` | App Store customers | may repeat across uploads |
 * | Build number | `ios { version { buildNumber } }`, else the scheme | `CURRENT_PROJECT_VERSION` | TestFlight testers | must be new for each upload of the same version |
 *
 * So a re-upload of `1.4.0` keeps the marketing version and needs a fresh build
 * number. Turn `ios { version { rebuild } }` rather than inventing a
 * version nobody shipped.
 *
 * ## What this block does and does not touch
 *
 * | Value | Written into your Xcode project |
 * |---|---|
 * | Bundle id, marketing version, build number | yes, by an explicit `kiteApplyIos` run |
 * | [pbxproj], [podfile], [infoPlist], [appDirectory], [appIconDirectory] | no, they say where to look |
 *
 * Those three are declared elsewhere: the bundle id in `ios { appId { suffix } }`,
 * and both version fields in the version topic's `ios { }` corner.
 *
 * @see KiteConfigIosSyncExtension for the gate that authorizes those writes.
 * @see KiteConfigAndroidExtension for the Android half of the same identity.
 * @see KiteVersionScope for the scheme every platform shares.
 */
@KiteDsl
abstract class KiteConfigIosExtension : KitePlatformExtension() {

    /** This platform's launch screen. */
    fun splash(action: org.gradle.api.Action<in KiteSplashScope>) = action.execute(splashDetails)


    /** This platform's icon art and how much of the canvas the mark fills. */
    fun logo(action: org.gradle.api.Action<in KiteLogoScope>) = action.execute(logoDetails)


    /** The locale list this platform ships, when it differs from the shared one. */
    fun locales(action: org.gradle.api.Action<in KiteLocalesScope>) = action.execute(localesDetails)


    final override val platform: KitePlatform = KitePlatform.IOS

    // --- Identity -------------------------------------------------------------


    // --- Versions -------------------------------------------------------------






    // --- Assets ---------------------------------------------------------------

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

    /** Facts your app declares in its `Info.plist`, and where that file lives. */
    fun infoPlist(action: Action<in KiteInfoPlistScope>) = action.execute(infoPlist)

    internal val infoPlist: KiteInfoPlistScope
        get() = (this as ExtensionAware).extensions.getByType(KiteInfoPlistScope::class.java)

    // --- Selection ------------------------------------------------------------

    /**
     * The exact Xcode application targets whose build settings may change.
     *
     * Needed only when the project has more than one application target. With
     * one, KiteConfig finds it. Naming a target that is not an application
     * target, or one that matches twice, is reported with the candidates.
     */
    fun xcodeTargets(vararg names: String) {
        xcodeTargetNames.addAll(*names)
    }

    internal abstract val xcodeTargetNames: ListProperty<String>

    // --- Transitions ----------------------------------------------------------

    /**
     * Rename the CocoaPods and Swift module across the Apple sources.
     *
     * `from` is the name in the files today, `to` is the name you want. This
     * rewrites your Swift source, so read the diff once and commit it. It
     * renames no directories, no Gradle projects, and no package declarations.
     *
     * ```kotlin
     * ios { renameSharedModule(from = "OldShared", to = "Shared") }
     * ```
     *
     * Once the references are renamed it does nothing on later builds.
     */
    fun renameSharedModule(from: String, to: String) {
        previousSharedModuleName.set(from)
        newSharedModuleName.set(to)
    }

    internal abstract val previousSharedModuleName: Property<String>

    internal abstract val newSharedModuleName: Property<String>
}
