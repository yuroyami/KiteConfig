package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * Everything KiteConfig resolved, as a read-only view.
 *
 * Reach it from any build file with the [kiteConfig] accessor. Every member is a
 * lazy [Provider], so wiring one into another task's property never forces a
 * value at configuration time.
 *
 * ## Reading a value
 *
 * These accessors supply no defaults and never return null. A value the root
 * build file never declared has no value at all, and reading it fails the build:
 *
 * ```kotlin
 * kiteConfig.version.get()   // declared     -> the value
 *                            // not declared -> throws, the build stops
 * ```
 *
 * That is deliberate. Reading a value the root never set is a mistake in the
 * consuming build file, and a silent fallback would put a second copy of the
 * value in the consumer, which is what this plugin exists to prevent.
 */
public interface KiteConfigValues {

    // ------------------------------------------------------------------ version

    /** The declared version string, for example `1.4.0`. */
    public val version: Provider<String>

    /** The resolved Android `versionCode` for this build. */
    public val versionCode: Provider<Int>

    /** The resolved Apple build number, `CFBundleVersion`. */
    public val iosBuildNumber: Provider<String>

    /** The resolved Apple marketing version, `CFBundleShortVersionString`. */
    public val iosMarketingVersion: Provider<String>

    /** The resolved desktop build number. */
    public val desktopBuildNumber: Provider<String>

    // ----------------------------------------------------------------- identity

    /** The declared app name, before any platform corner overrides it. */
    public val appName: Provider<String>

    /**
     * The app name resolved for [platform], with its corner override applied.
     *
     * This is the value the platform would receive. It does not account for
     * `skip()` / `only()`: flow control decides whether KiteConfig writes the
     * value, not what the value is. The same holds for [appIdFor],
     * [androidApplicationId], [iosBundleId], and [desktopBundleId].
     */
    public fun appNameFor(platform: KitePlatform): Provider<String>

    /** The declared base identifier, before any platform suffix or override. */
    public val appId: Provider<String>

    /**
     * The identifier resolved for [platform]: that platform's own `appId`, or the
     * shared one plus that platform's suffix.
     *
     * Like [appNameFor], this is the value the platform would receive. It does
     * not account for selection or for a platform that turned delivery off.
     */
    public fun appIdFor(platform: KitePlatform): Provider<String>

    /** Android application id: the effective [appId] for Android. See [appNameFor]. */
    public val androidApplicationId: Provider<String>

    /** Apple bundle id: the effective [appId] for iOS. See [appNameFor]. */
    public val iosBundleId: Provider<String>

    /**
     * Desktop bundle id: the effective [appId] for desktop.
     *
     * See [appNameFor]. Unlike the other values here, this one validates on read
     * and throws when the result is not a valid reverse-DNS identifier.
     */
    public val desktopBundleId: Provider<String>

    /**
     * The identifier the debug build of [platform] gets: its `debug { }` value,
     * otherwise the release identifier from [appIdFor]. Desktop has no debug
     * build, so it always answers with the release identifier.
     */
    public fun debugAppIdFor(platform: KitePlatform): Provider<String> = appIdFor(platform)

    /**
     * The name the debug build of [platform] shows: its `debug { }` value,
     * otherwise the release name from [appNameFor].
     */
    public fun debugAppNameFor(platform: KitePlatform): Provider<String> = appNameFor(platform)

    // -------------------------------------------------------------------- build

    /**
     * Normalized, de-duplicated locale tags.
     *
     * Resolves later than the rest of this view. When the list is auto-detected
     * rather than pinned, it depends on finding the shared module, which is only
     * known after every project has been evaluated. Wire it into a task and let
     * it resolve at execution time:
     *
     * ```kotlin
     * someTask.localeList.set(kiteConfig.canonicalLocales)
     * ```
     *
     * Calling `get()` during configuration returns an empty list, because
     * detection has not run yet. That answer is local to the caller and does not
     * affect the list the build uses.
     */
    public val canonicalLocales: Provider<List<String>>

    /** The Java release level applied to JVM compilation. */
    public val jvmTarget: Provider<Int>

    /** The JDK major version the build uses, when `jvm { toolchain }` selected one. */
    public val jvmToolchain: Provider<Int>

    /**
     * The selected shared KMP project path.
     *
     * Resolves later than the rest of this view unless `modules { shared }` is
     * declared. Without it the path comes from detecting a sole Kotlin
     * Multiplatform project, which is only known after every project has been
     * evaluated, so `get()` during configuration fails with no value. Either
     * declare the module or read this through a task input.
     */
    public val resolvedSharedProjectPath: Provider<String>

    /** Lowest Android API level the app runs on. */
    public val minSdk: Provider<Int>

    /** Android API level the app targets. */
    public val targetSdk: Provider<Int>

    /** Android API level the app compiles against. */
    public val compileSdk: Provider<Int>

    /** Pinned Android NDK version, in Android's `major.minor.build` form. */
    public val ndk: Provider<String>
}

/**
 * Everything KiteConfig resolved, readable from any project in the build.
 *
 * ```kotlin
 * import io.github.yuroyami.kiteconfig.kiteConfig
 *
 * android {
 *     defaultConfig {
 *         versionCode = kiteConfig.versionCode.get()
 *     }
 * }
 * ```
 *
 * The returned view is read-only: configure the plugin in the root build file
 * and read it everywhere else. The model is frozen before subprojects are
 * evaluated, so what you read is what the build uses. Two values are the
 * exception and settle later: see [KiteConfigValues.canonicalLocales] and
 * [KiteConfigValues.resolvedSharedProjectPath].
 *
 * This reads across projects, so it is not compatible with Gradle Isolated
 * Projects. Neither is the rest of the plugin.
 *
 * If the plugin is missing from the build entirely, this import does not
 * resolve and Kotlin reports that first. The exception below covers the
 * narrower case where the plugin is on the classpath but was never applied to
 * the root, for example when it was declared with `apply false`.
 *
 * @throws GradleException if the plugin is on the classpath but not applied to
 *   the root project.
 */
public val Project.kiteConfig: KiteConfigValues
    get() = rootProject.extensions.findByType(KiteConfigExtension::class.java)
        ?: throw GradleException(
            "KiteConfig values are unavailable in '$path': apply the " +
                "io.github.yuroyami.kiteconfig plugin to the root project first."
        )
