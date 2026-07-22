package io.github.yuroyami.kmpssot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * Runtime constants codegen. Nested under `kmpSsot { buildConfig { ... } }`.
 *
 * A single-plugin replacement for buildKonfig for the common case: declare typed
 * constants once, read them from every KMP source set (no `expect/actual`). The
 * generated object also carries the identity SSOT the plugin already computes
 * (appName, versionName, versionCode, bundle ids, locales) unless
 * [includeIdentity] is turned off.
 *
 * Generated into a plugin-owned `commonMain` source dir on the shared module,
 * wired via the source set — never your hand-authored tree.
 *
 * ```
 * kmpSsot {
 *     buildConfig {
 *         enabled     = true
 *         className   = "BuildConfig"      // default; the generated object's name
 *         packageName = "com.acme.app"     // default: kmpssot.generated
 *         stringField("BASE_URL", "https://api.acme.com")
 *         intField("API_TIMEOUT_MS", 30_000)
 *         booleanField("ANALYTICS_ENABLED", true)
 *         stringField("SENTRY_DSN", providers.gradleProperty("sentryDsn")) // from gradle.properties
 *     }
 * }
 * ```
 *
 * **This is not a secret store.** Only public client configuration belongs here.
 * A provider can keep a value out of the build script and version control, but its
 * resolved value still becomes generated source and a Gradle task input. It can also
 * enter build scans, KLIBs, APKs/IPAs, decompiled binaries, and—when
 * [allowBuildCache] is explicitly enabled—local or remote build caches. Never
 * generate passwords, private API keys, signing material, or any other credential
 * with this API.
 */
abstract class KmpSsotBuildConfigExtension {

    /**
     * Generate the object into the selected KMP shared project's `commonMain`.
     * Default false; enabling requires `sharedProjectPath` (or its legacy fallback).
     */
    abstract val enabled: Property<Boolean>

    /** Validated Kotlin package for the generated object. Default `kmpssot.generated`. */
    abstract val packageName: Property<String>

    /** Validated Kotlin identifier for the generated object. Default `BuildConfig`. */
    abstract val className: Property<String>

    /**
     * Include the identity SSOT (appName/versionName/versionCode/bundle ids/locales)
     * in the generated object. Default true and requires complete app name,
     * version, and bundle-id inputs when generation is enabled. Turn off for a
     * fields-only object; generation then does not resolve any identity provider.
     */
    abstract val includeIdentity: Property<Boolean>

    /**
     * Permit Gradle build-cache storage of generated BuildConfig source. Default
     * false because field values become cache payload. Enable only when every
     * field is public client configuration and the configured local/remote cache
     * is trusted.
     */
    abstract val allowBuildCache: Property<Boolean>

    /**
     * Legacy Gradle transport for typed fields. Prefer the `*Field` methods: direct
     * entries are parsed against the supported type/literal grammar and rejected if
     * they contain arbitrary Kotlin source. Duplicate and identity-colliding names
     * are rejected during generation. The model accepts at most 512 entries,
     * 65,536 characters per entry, and 1,048,576 characters in total.
     */
    abstract val fields: ListProperty<String>

    /** Add a validated Kotlin `String` constant of at most 10,000 characters. */
    fun stringField(name: String, value: String) {
        fields.add(BuildConfigField.StringValue(name.checkedFieldName(), value).renderBody())
    }

    /**
     * String field sourced lazily and resolved at generation time. This keeps the value
     * out of the build script; it does **not** keep it out of generated source, caches,
     * build scans, or application binaries. The resolved value is limited to
     * 10,000 characters. Do not use it for credentials.
     */
    fun stringField(name: String, value: Provider<String>) {
        val checkedName = name.checkedFieldName()
        fields.add(value.map { BuildConfigField.StringValue(checkedName, it).renderBody() })
    }

    /** Add a validated Kotlin `Int` constant; `Int.MIN_VALUE` is emitted canonically. */
    fun intField(name: String, value: Int) {
        fields.add(BuildConfigField.IntValue(name.checkedFieldName(), value).renderBody())
    }

    /** Add a validated Kotlin `Long` constant; `Long.MIN_VALUE` is emitted canonically. */
    fun longField(name: String, value: Long) {
        fields.add(BuildConfigField.LongValue(name.checkedFieldName(), value).renderBody())
    }

    /** Add a validated Kotlin `Boolean` constant. */
    fun booleanField(name: String, value: Boolean) {
        fields.add(BuildConfigField.BooleanValue(name.checkedFieldName(), value).renderBody())
    }

    /** Add a finite Kotlin `Double` constant. */
    fun doubleField(name: String, value: Double) {
        fields.add(BuildConfigField.DoubleValue(name.checkedFieldName(), value).renderBody())
    }

    private fun String.checkedFieldName(): String = apply {
        require(isValidKotlinIdentifier(this)) {
            "kmpSsot { buildConfig } field name \"$this\" is not a valid Kotlin identifier."
        }
    }
}
