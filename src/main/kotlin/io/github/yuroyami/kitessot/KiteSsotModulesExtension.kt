package io.github.yuroyami.kitessot

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Where your projects live, inside `kiteSsot { modules { ... } }`.
 *
 * ```kotlin
 * kiteSsot {
 *     modules {
 *         shared = ":shared"
 *         androidApps(":androidApp")
 *     }
 * }
 * ```
 *
 * Most builds never open this block. KiteSSOT finds the shared KMP project and
 * the Android app by itself, so a standard template repo configures nothing
 * here.
 *
 * Auto-detection fails closed. When two projects apply Kotlin Multiplatform, or
 * two projects are Android applications, the build stops and names the
 * candidates instead of guessing. One line here ends the ambiguity for good.
 */
abstract class KiteSsotModulesExtension {

    /**
     * Absolute Gradle path of the KMP project that owns generated `commonMain`
     * source, for example `":shared"` or `":core:shared"`.
     *
     * Default: the single project that applies Kotlin Multiplatform. Two or more
     * candidates is a hard error that lists them and asks for this value.
     */
    abstract val shared: Property<String>

    /**
     * Exact Android application project paths, absolute, such as `":androidApp"`.
     * These receive app identity, versions, display name, locale filters, and
     * logo output.
     *
     * Default: empty, which means KiteSSOT uses the one Android application it
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
     * Directory of the Android app module on disk.
     *
     * Default: found from the selected Android application project. Set it only
     * when the app's files do not sit in that project's own directory.
     */
    abstract val androidAppDirectory: DirectoryProperty

    /**
     * Compose resources directory that locale discovery reads.
     *
     * Default: the shared project's `commonMain` compose resources. KiteSSOT
     * scans it for locale-only folders such as `values-en`, `values-pt-rBR`, and
     * `values-b+sr+Latn`. It is not read at all once you set `locales` yourself.
     */
    abstract val composeResources: DirectoryProperty
}
