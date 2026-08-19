package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import javax.inject.Inject

/**
 * Generated Kotlin constants for your shared code, inside
 * `kiteSsot { buildConfig { ... } }`.
 *
 * ```kotlin
 * kiteSsot {
 *     appName = "Jetzy"
 *     version = "1.4.0"
 *     appId = "com.example.jetzy"
 *     modules { shared = ":shared" }
 *
 *     buildConfig {
 *         packageName = "com.example.jetzy.generated"
 *
 *         stringField("BASE_URL", "https://api.acme.com")
 *         intField("API_TIMEOUT_MS", 30_000)
 *         booleanField("ANALYTICS_ENABLED", true)
 *     }
 * }
 * ```
 *
 * Opening this block turns generation on. KiteSSOT writes one Kotlin object into
 * the shared project's `commonMain`, so every platform reads the same constants
 * without an `expect` and `actual` pair.
 *
 * App identity rides along by default: app name, version, version code, Android
 * application ID, iOS bundle ID, and locales. See [includeIdentity] to turn that
 * off. Android SDK and toolchain values are never included; declare a custom
 * field when runtime code needs one.
 *
 * The generated file lives in a plugin-owned build directory and never enters
 * your hand-written source tree. Package, object, and field names must be valid
 * Kotlin names.
 *
 * This is not a secret store. Only public client configuration belongs here. A
 * provider can keep a value out of the build script and out of version control,
 * but the resolved value still reaches generated source and Gradle task inputs.
 * It can also surface in build scans, KLIBs, APKs, IPAs, decompiled binaries,
 * and a trusted build cache when [allowBuildCache] is on. Never add passwords,
 * private API keys, signing material, or other credentials.
 */
abstract class KiteSsotBuildConfigExtension {

    /** Gradle-injected, used only to build the failure path for an empty provider. */
    @get:Inject
    protected abstract val providerFactory: ProviderFactory

    /**
     * Whether the object is generated.
     *
     * Default: `true` once this block is configured. Set it to `false` to keep
     * the configuration in place and skip the work, for example from CI. `false`
     * always wins.
     *
     * Generation needs `modules.shared`, and that project must apply Kotlin
     * Multiplatform and contain `commonMain`.
     */
    abstract val enabled: Property<Boolean>

    /**
     * Kotlin package of the generated object, written as dot-separated
     * identifiers such as `com.acme.app.generated`.
     *
     * Default: `kitessot.generated`.
     */
    abstract val packageName: Property<String>

    /**
     * Name of the generated object. It must be a valid Kotlin identifier.
     *
     * Default: `BuildConfig`.
     */
    abstract val className: Property<String>

    /**
     * Whether KiteSSOT app identity is baked into the object: app name, version,
     * version code, the resolved Android and iOS IDs, and the canonical locales.
     *
     * Default: `true`, which requires `appName`, `version`, and `appId` while
     * generation is on.
     *
     * Set it to `false` for a custom-fields-only object. In that mode, generation
     * reads no identity provider at all.
     */
    abstract val includeIdentity: Property<Boolean>

    /**
     * Whether Gradle may store the generated source in a build cache.
     *
     * Default: `false`, because every generated value becomes part of the cache
     * entry. Turn it on only when all fields are public client configuration and
     * every local and remote cache is trusted.
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
     * Limits: 512 entries, 65,536 characters per entry, and 1,048,576 characters
     * in total.
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
     *
     * The provider must have a value. A bare `providers.gradleProperty("x")` has
     * none until someone passes `-Px`, and that fails the build naming this field.
     * Give it a fallback when the value is optional:
     *
     * ```kotlin
     * stringField("CHANNEL", providers.gradleProperty("publicChannel").orElse("stable"))
     * ```
     */
    fun stringField(name: String, value: Provider<String>) {
        val checkedName = name.checkedFieldName()
        // Gradle voids the WHOLE list when an added provider has no value, so an
        // unset -P would otherwise surface as "customFields doesn't have a
        // configured value" with nothing pointing back to the field that caused it.
        fields.add(
            value.map { BuildConfigField.StringValue(checkedName, it).renderBody() }
                .orElse(providerFactory.provider { missingFieldValue(checkedName) }),
        )
    }

    private fun missingFieldValue(name: String): String = throw GradleException(
        "kiteSsot { buildConfig { stringField(\"$name\", ...) } } was given a provider with no " +
            "value, so the field cannot be generated. Supply a fallback with orElse(...), or pass " +
            "a plain String.",
    )

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
