package io.github.yuroyami.kmpssot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * Runtime build-config codegen. Nested under `kmpSsot { buildConfig { ... } }`.
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
 * Not a secret store: prefer `providers.gradleProperty(...)` / env over inline
 * literals for anything sensitive, so it never lands in version control.
 */
abstract class KmpSsotBuildConfigExtension {

    /** Generate the object into the shared module's commonMain. Default false. */
    abstract val enabled: Property<Boolean>

    /** Package for the generated object. Default `kmpssot.generated`. */
    abstract val packageName: Property<String>

    /** Name of the generated object. Default `BuildConfig`. */
    abstract val className: Property<String>

    /**
     * Include the identity SSOT (appName/versionName/versionCode/bundle ids/locales)
     * in the generated object. Default true. Turn off for a fields-only object.
     */
    abstract val includeIdentity: Property<Boolean>

    /** Pre-rendered `NAME: TYPE = LITERAL` field bodies. Populated by the `*Field` methods. */
    abstract val fields: ListProperty<String>

    fun stringField(name: String, value: String) {
        fields.add(buildConfigFieldLine("String", name, kotlinStringLiteral(value)))
    }

    /** String field sourced lazily (e.g. `providers.gradleProperty(...)`), resolved at build time. */
    fun stringField(name: String, value: Provider<String>) {
        fields.add(value.map { buildConfigFieldLine("String", name, kotlinStringLiteral(it)) })
    }

    fun intField(name: String, value: Int) {
        fields.add(buildConfigFieldLine("Int", name, value.toString()))
    }

    fun longField(name: String, value: Long) {
        fields.add(buildConfigFieldLine("Long", name, "${value}L"))
    }

    fun booleanField(name: String, value: Boolean) {
        fields.add(buildConfigFieldLine("Boolean", name, value.toString()))
    }

    fun doubleField(name: String, value: Double) {
        fields.add(buildConfigFieldLine("Double", name, value.toString()))
    }
}
