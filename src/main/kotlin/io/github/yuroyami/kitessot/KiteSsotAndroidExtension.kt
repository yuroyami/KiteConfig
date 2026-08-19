package io.github.yuroyami.kitessot

import org.gradle.api.provider.Property

/**
 * Everything Android-only, inside `kiteSsot { android { ... } }`.
 *
 * ```kotlin
 * kiteSsot {
 *     appId = "com.example.jetzy"
 *     version = "1.4.0"
 *
 *     android {
 *         idSuffix = ".debug"
 *         rebuild = 1
 *
 *         compileSdk = 36
 *         minSdk = 26
 *         targetSdk = 36
 *     }
 * }
 * ```
 *
 * Shared truth stays at root. This block holds only what Android alone needs:
 * the ID suffix, the version code dials, SDK levels, and two apply switches.
 *
 * The plugin also exposes a read-only `applicationId: Provider<String>` here.
 * It is the root `appId` joined with [idSuffix]. Read it to wire other build
 * logic, for example into a manifest placeholder. You cannot set it.
 *
 * ## Which module types receive each SDK setting
 *
 * A library DSL simply has no `targetSdk`, and the KMP-native Android library
 * DSL has neither `targetSdk` nor `ndkVersion`, so those are skipped there and
 * reported rather than applied silently.
 *
 * | Setting | Application | Classic library | KMP-native library |
 * |---|---|---|---|
 * | [compileSdk] | yes | yes | yes |
 * | [minSdk] | yes | yes | yes |
 * | [targetSdk] | yes | not in the DSL | not in the DSL |
 * | [ndk] | yes | yes | not in the DSL |
 *
 * ## Picking the right version-code dial
 *
 * | You want | Set | Effect |
 * |---|---|---|
 * | The usual: one code per release | nothing | root [KiteSsotExtension.scheme] applies |
 * | To re-upload the same app version | [rebuild] | bumps the low digits only |
 * | A different formula on Android only | [scheme] | replaces the root formula here |
 * | One exact number, no formula | [versionCode] | bypasses the scheme and [rebuild] |
 * | To be told before Play rejects you | [publishedVersionCode] | fails the build if the code did not grow |
 *
 * @see KiteSsotExtension.scheme for the formula both platforms share.
 * @see KiteSsotIosExtension for the Apple half of the same identity.
 * @see KiteSsotModulesExtension.androidApps when more than one module is an application.
 */
abstract class KiteSsotAndroidExtension {

    // --- Identity -------------------------------------------------------------

    /**
     * Text appended to the root `appId` to build the Android application ID.
     *
     * Default: empty, so the application ID equals `appId`.
     *
     * Play treats a changed application ID as a different app, with a separate
     * listing and no upgrade path for installed users. Pick it once, then leave
     * it alone, or vary it per build type only.
     */
    abstract val idSuffix: Property<String>

    // --- Version code ---------------------------------------------------------

    /**
     * The number Play uses to order uploads. An Int in `1..2100000000`, which is
     * Play's documented cap.
     *
     * Default: the root scheme, applied with this block's [rebuild].
     * Assigning a value bypasses the scheme entirely, [rebuild] included.
     *
     * @throws org.gradle.api.GradleException during configuration when the value
     *   falls outside `1..2100000000`.
     */
    abstract val versionCode: Property<Int>

    /**
     * The "Play already ate that number" dial: it bumps the last digits so the
     * same app version can be uploaded again.
     *
     * Default: `0`. Range `0..9` under the built-in scheme, where `1.4.0` gives
     * 1001004000 and the first re-upload gives 1001004001.
     *
     * Play keeps every uploaded version code forever, even when you discard the
     * release draft, so a second upload of the same app version needs a fresh
     * number. Turn this dial instead of faking a patch release. It never needs
     * resetting: the next version bump moves a higher digit.
     *
     * @throws org.gradle.api.GradleException during configuration when the
     *   built-in scheme is in use and this exceeds `9`, which would overflow into
     *   the patch digits. A custom [scheme] sets its own range.
     */
    abstract val rebuild: Property<Int>

    /**
     * Formula that turns the root version into the number above, for Android only.
     *
     * Default: the root scheme, the one both platforms share. Override it here
     * only when the two platforms genuinely need different numbers.
     */
    abstract val scheme: Property<VersionCodeScheme>

    /**
     * Same as assigning [scheme]. Lets both the Kotlin and Groovy DSLs write
     * `scheme { v -> ... }`.
     */
    fun scheme(s: VersionCodeScheme) {
        scheme.set(s)
    }

    // --- SDK levels -----------------------------------------------------------

    /**
     * API level that supported Android applications and libraries compile against,
     * written as `compileSdk`.
     *
     * Default: unset, which leaves each module's own value alone.
     */
    abstract val compileSdk: Property<Int>

    /**
     * Lowest API level the app runs on, written as `defaultConfig.minSdk` in
     * supported Android applications and libraries.
     *
     * Default: unset, which leaves each module's own value alone.
     */
    abstract val minSdk: Property<Int>

    /**
     * API level the app declares it was built for, written as
     * `defaultConfig.targetSdk` in Android application modules.
     *
     * Default: unset. Android library modules do not expose this setting, so it
     * never reaches them.
     */
    abstract val targetSdk: Property<Int>

    /**
     * Exact NDK toolchain version for classic Android modules, in Android's
     * `major.minor.build` form, for example `27.0.12077973`.
     *
     * Default: unset. The KMP-native Android library DSL has no such setting, so
     * those modules ignore it.
     *
     * @throws org.gradle.api.GradleException during configuration when the value
     *   is not in Android's `major.minor.build` form.
     */
    abstract val ndk: Property<String>

    // --- Guards and gates -----------------------------------------------------

    /**
     * The highest code you have already uploaded to the store.
     *
     * Default: unset, no check. When set, the resolved code must be greater than
     * it, or the build fails with a clear message instead of the store rejecting
     * your upload later.
     *
     * This check is offline. KiteSSOT never contacts a store, never guesses this
     * value, and never writes it into any DSL, manifest, or generated file.
     *
     * @throws org.gradle.api.GradleException during configuration when the
     *   resolved code is not greater than this baseline.
     * @see KiteSsotIosExtension.publishedBuildNumber for the TestFlight equivalent.
     */
    abstract val publishedVersionCode: Property<Int>

    /**
     * Whether [compileSdk], [minSdk], [targetSdk], and [ndk] are written into
     * compatible modules.
     *
     * Default: `true`. Set it to `false` to keep this block as plain
     * documentation, with every module keeping its own levels.
     */
    abstract val applySdkLevels: Property<Boolean>

    /**
     * Whether the selected app's resource locale filters are replaced with the
     * root locale list.
     *
     * Default: `false`.
     *
     * This one changes what ships: languages outside the list are dropped from
     * packaged resources in the APK or AAB. Enable it only when the list is
     * complete, or users will see untranslated strings.
     *
     * @throws org.gradle.api.GradleException during configuration when enabled
     *   with an empty locale list, which would strip every language.
     * @see KiteSsotExtension.locales for the list this filters to.
     */
    abstract val filterResourcesToLocales: Property<Boolean>
}
