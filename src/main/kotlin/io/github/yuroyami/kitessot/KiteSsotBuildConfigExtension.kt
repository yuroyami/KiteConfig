package io.github.yuroyami.kitessot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * Generates a Kotlin object with public runtime configuration.
 *
 * Configure it inside `kiteSsot { buildConfig { ... } }`. KiteSSOT adds the
 * generated object to the selected shared project's `commonMain`. Production
 * source sets that depend on `commonMain` can read the same constants without
 * `expect` and `actual` declarations.
 *
 * By default, the object includes app name, version name, version code, Android
 * application ID, iOS bundle ID, and locales. Set [includeIdentity] to `false`
 * when you only want custom fields. Generated source stays in a plugin-owned
 * build directory and never enters your hand-written source tree. Every package,
 * object, and custom field name must be a valid Kotlin name.
 *
 * Android SDK and toolchain values are not included automatically. If runtime
 * code needs one, declare a custom field from the same root value that configures
 * the Android block.
 *
 * ```kotlin
 * kiteSsot {
 *     sharedProjectPath = ":shared"
 *
 *     buildConfig {
 *         enabled = true
 *         className = "BuildConfig"       // default
 *         packageName = "com.acme.app"    // default: kitessot.generated
 *
 *         stringField("BASE_URL", "https://api.acme.com")
 *         intField("API_TIMEOUT_MS", 30_000)
 *         booleanField("ANALYTICS_ENABLED", true)
 *         stringField(
 *             "PUBLIC_CHANNEL",
 *             providers.gradleProperty("publicChannel"),
 *         )
 *     }
 * }
 * ```
 *
 * **This is not a secret store.** Only put public client configuration here. A
 * provider can keep a value out of the build script and version control, but the
 * resolved value still enters generated source and Gradle task inputs. It may
 * also appear in build scans, KLIBs, APKs, IPAs, decompiled binaries, and trusted
 * build caches when [allowBuildCache] is enabled. Never add passwords, private
 * API keys, signing material, or other credentials.
 */
abstract class KiteSsotBuildConfigExtension {

    /**
     * Whether to generate the object. The default is `false`.
     *
     * Enabling generation requires `sharedProjectPath` or its legacy fallback.
     * The selected project must apply Kotlin Multiplatform and contain
     * `commonMain`.
     */
    abstract val enabled: Property<Boolean>

    /** Valid Kotlin package for the generated object. The default is `kitessot.generated`. */
    abstract val packageName: Property<String>

    /** Valid Kotlin identifier for the generated object. The default is `BuildConfig`. */
    abstract val className: Property<String>

    /**
     * Whether to include KiteSSOT app identity in the generated object.
     *
     * The default is `true`. When generation is enabled, this requires `appName`,
     * `versionName`, a derived version code or `versionCodeOverride`, and
     * `bundleIdBase`. The generated object also includes the resolved Android and
     * iOS IDs and canonical locales.
     *
     * Set this to `false` for a custom-fields-only object. In that mode, generation
     * does not read any identity provider.
     */
    abstract val includeIdentity: Property<Boolean>

    /**
     * Whether Gradle may store the generated source in a build cache.
     *
     * The default is `false` because every generated value becomes part of the
     * cache entry. Enable this only when all fields are public client
     * configuration and every local or remote cache is trusted.
     */
    abstract val allowBuildCache: Property<Boolean>

    /**
     * Legacy list representation for custom fields.
     *
     * Prefer [stringField], [intField], [longField], [booleanField], and
     * [doubleField]. Direct entries must match the supported type and literal
     * grammar. Arbitrary Kotlin source, duplicate names, and names that collide
     * with generated identity fields are rejected.
     *
     * The list accepts at most 512 entries, 65,536 characters per entry, and
     * 1,048,576 characters in total.
     */
    abstract val fields: ListProperty<String>

    /** Add a validated Kotlin `String` constant of at most 10,000 characters. */
    fun stringField(name: String, value: String) {
        fields.add(BuildConfigField.StringValue(name.checkedFieldName(), value).renderBody())
    }

    /**
     * Add a validated Kotlin `String` constant from a provider.
     *
     * KiteSSOT resolves the provider when it generates the source. This can keep
     * the value out of the build script, but not out of generated source, task
     * inputs, build scans, application binaries, or an enabled build cache. The
     * resolved value may contain at most 10,000 characters. Do not use this for
     * credentials.
     */
    fun stringField(name: String, value: Provider<String>) {
        val checkedName = name.checkedFieldName()
        fields.add(value.map { BuildConfigField.StringValue(checkedName, it).renderBody() })
    }

    /** Add a validated Kotlin `Int` constant. `Int.MIN_VALUE` is emitted as valid Kotlin source. */
    fun intField(name: String, value: Int) {
        fields.add(BuildConfigField.IntValue(name.checkedFieldName(), value).renderBody())
    }

    /** Add a validated Kotlin `Long` constant. `Long.MIN_VALUE` is emitted as valid Kotlin source. */
    fun longField(name: String, value: Long) {
        fields.add(BuildConfigField.LongValue(name.checkedFieldName(), value).renderBody())
    }

    /** Add a validated Kotlin `Boolean` constant. */
    fun booleanField(name: String, value: Boolean) {
        fields.add(BuildConfigField.BooleanValue(name.checkedFieldName(), value).renderBody())
    }

    /** Add a validated finite Kotlin `Double` constant. NaN and infinite values are rejected. */
    fun doubleField(name: String, value: Double) {
        fields.add(BuildConfigField.DoubleValue(name.checkedFieldName(), value).renderBody())
    }

    private fun String.checkedFieldName(): String = apply {
        require(isValidKotlinIdentifier(this)) {
            "kiteSsot { buildConfig } field name \"$this\" is not a valid Kotlin identifier."
        }
    }
}
