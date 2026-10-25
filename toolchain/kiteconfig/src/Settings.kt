package io.github.yuroyami.kiteconfig.toolchain

import org.jetbrains.amper.plugins.Configurable

/** The `plugins: kiteconfig:` block of a module. */
@Configurable
interface KiteConfigSettings {
    /** The app name that `KiteBuildConfig.appName` carries. */
    val appName: String

    /** The marketing version, three plain numbers such as `4.1.1`. */
    val version: String

    /** A counter for rebuilds of the same version. It is the last digit of the default build number. */
    val rebuild: Int get() = 0

    /** An exact build number. When set, it replaces the default layout. */
    val buildNumber: String?

    /** The app id for every platform that has no id of its own. */
    val appId: String?

    /** The Android application id. Default: [appId]. */
    val androidApplicationId: String?

    /** The iOS bundle id. Default: [appId]. */
    val iosBundleId: String?

    /** BCP 47 locale tags, such as `en` or `pt-BR`. */
    val locales: List<String> get() = emptyList()

    /** The generated `KiteBuildConfig` object. */
    val buildConfig: BuildConfigSettings

    /** The Xcode project that `./kotlin do kiteSyncIos` keeps in line. */
    val ios: IosSettings
}

/** The `plugins: kiteconfig: buildConfig:` block. */
@Configurable
interface BuildConfigSettings {
    /** Whether the module gets the generated object. */
    val enabled: Boolean get() = true

    /** The package of the generated object. */
    val packageName: String get() = "kiteconfig.generated"

    /** The name of the generated object. */
    val className: String get() = "KiteBuildConfig"

    /** Extra String constants, by name. */
    val fields: Map<String, String> get() = emptyMap()
}

/** The `plugins: kiteconfig: ios:` block. */
@Configurable
interface IosSettings {
    /** The `.xcodeproj` folder, relative to the project root. Unset, the iOS command and check do nothing. */
    val xcodeProject: String?

    /** The application targets to update. Empty means every application target. */
    val targets: List<String> get() = emptyList()
}
