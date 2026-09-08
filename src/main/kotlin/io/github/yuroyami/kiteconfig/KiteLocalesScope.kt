package io.github.yuroyami.kiteconfig

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * The locales topic, inside `kiteConfig { locales { ... } }`.
 *
 * | member | meaning | default |
 * |---|---|---|
 * | `tags` | the BCP 47 tags your app ships | detected from Compose resources |
 * | `enabled` | deliver the list to this platform | true |
 *
 * ```kotlin
 * kiteConfig {
 *     locales { tags = listOf("en", "ar", "fr") }
 *     ios { locales { tags = listOf("en", "fr") } }   // iOS ships fewer
 * }
 * ```
 *
 * Assigning the list replaces it. `tags = emptyList()` means this app has no
 * locale list, not "go and detect one".
 */
@KiteDsl
abstract class KiteLocalesScope {

    /**
     * The BCP 47 tags your app ships, for example `listOf("en", "ar")`.
     *
     * Leave it unset and KiteConfig reads the Compose resource folders of the
     * shared module; leave it unset in a platform block and that platform ships
     * the shared list. Tags are normalized and de-duplicated, and declaration
     * order is kept.
     */
    abstract val tags: ListProperty<String>

    /** Deliver the locale list to this platform. Default: `true`. */
    abstract val enabled: Property<Boolean>
}

/**
 * Android's locale details, inside `kiteConfig { android { locales { ... } } }`.
 *
 * Everything in [KiteLocalesScope] applies here too, plus one Android-only
 * policy.
 */
@KiteDsl
abstract class KiteAndroidLocalesScope : KiteLocalesScope() {

    /**
     * Drop Android resource folders whose locale is not in the list.
     * Default: `false`.
     *
     * The list has to be known before AGP settles its resource configuration.
     * When detection cannot answer that early, KiteConfig says so and asks for
     * `locales { tags }` or `modules { shared }` rather than guessing.
     */
    abstract val filterResources: Property<Boolean>
}
