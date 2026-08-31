package io.github.yuroyami.kiteconfig

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Where your projects live, inside `kiteConfig { modules { ... } }`.
 *
 * ```kotlin
 * kiteConfig {
 *     modules {
 *         shared = ":shared"
 *         androidApps(":androidApp")
 *     }
 * }
 * ```
 *
 * Most builds never open this block. KiteConfig finds the shared KMP project and
 * the Android app by itself, so a standard template repo configures nothing
 * here.
 *
 * Auto-detection fails closed. When two projects apply Kotlin Multiplatform and
 * a shared-scoped feature such as `buildConfig` needs one, or two projects are
 * Android applications, the build stops and names the candidates instead of
 * guessing. One line here ends the ambiguity for good.
 *
 * ## What each selector controls
 *
 * | Property | Selects | Detected when you say nothing |
 * |---|---|---|
 * | [shared] | where generated `commonMain` source lands | the sole project applying Kotlin Multiplatform |
 * | [androidApps] | which apps receive identity, versions, and logo output | the sole Android application project |
 * | [desktopApps] | which desktop apps receive identity, versions, and installer icons | the sole Compose Desktop application project |
 * | [androidAppDirectory] | where launcher resources are written | the selected application's own directory |
 * | [composeResources] | where locale discovery reads | the shared project's `commonMain/composeResources` |
 *
 * Detection reports rather than guesses. Zero or several candidates fails with
 * the list of what it found and the one line that settles it.
 *
 * @see KiteConfigExtension.locales for what [composeResources] feeds.
 * @see KiteConfigBuildConfigExtension for the feature that most often needs [shared].
 */
abstract class KiteConfigModulesExtension {

    /**
     * Absolute Gradle path of the KMP project that owns generated `commonMain`
     * source, for example `":shared"` or `":core:shared"`.
     *
     * Default: the single project that applies Kotlin Multiplatform. Two or more
     * candidates is a hard error that lists them and asks for this value.
     *
     * Detection resolves once every project has been evaluated, which is the
     * earliest point the count is known.
     *
     * @throws org.gradle.api.GradleException during configuration when a
     *   shared-scoped feature is enabled and the path is missing, names a project
     *   that does not exist, or names one that does not apply Kotlin Multiplatform.
     */
    abstract val shared: Property<String>

    /**
     * Exact Android application project paths, absolute, such as `":androidApp"`.
     * These receive app identity, versions, display name, locale filters, and
     * logo output.
     *
     * Default: empty, which means KiteConfig uses the one Android application it
     * finds. SDK levels and the JVM target still reach every compatible module,
     * listed or not.
     */
    abstract val androidApps: ListProperty<String>

    /**
     * Adds one or more application paths to [androidApps].
     *
     * This adds, never replaces, so repeated calls pile up.
     *
     * ```kotlin
     * androidApps(":androidApp", ":demoApp")
     * ```
     */
    fun androidApps(vararg paths: String) {
        androidApps.addAll(*paths)
    }

    /**
     * Exact Compose Desktop application project paths, absolute, such as
     * `":desktopApp"`. These receive app identity, versions, packaging names, and
     * installer icons.
     *
     * Default: empty, which means KiteConfig uses the one Compose Desktop
     * application it finds. Two or more candidates is a hard error that lists
     * them and asks for this value.
     *
     * @see KiteConfigDesktopExtension for what those projects then receive.
     */
    abstract val desktopApps: ListProperty<String>

    /**
     * Adds one or more desktop application paths to [desktopApps].
     *
     * This adds, never replaces, so repeated calls pile up.
     *
     * ```kotlin
     * desktopApps(":desktopApp")
     * ```
     */
    fun desktopApps(vararg paths: String) {
        desktopApps.addAll(*paths)
    }

    /**
     * Directory of the Android app module on disk.
     *
     * Default: found from the selected Android application project. Set it only
     * when the app's files do not sit in that project's own directory.
     */
    abstract val androidAppDirectory: DirectoryProperty

    /**
     * Compose resources directory that locale discovery reads.
     *
     * Default: the shared project's `commonMain` compose resources. KiteConfig
     * scans it for locale-only folders such as `values-en`, `values-pt-rBR`, and
     * `values-b+sr+Latn`. It is not read at all once you set `locales` yourself.
     */
    abstract val composeResources: DirectoryProperty
}
