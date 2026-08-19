package io.github.yuroyami.kitessot

import org.gradle.api.provider.Property

/**
 * The hands-off switchboard, inside `kiteSsot { propagate { ... } }`.
 *
 * ```kotlin
 * kiteSsot {
 *     version = "1.4.0"
 *
 *     propagate {
 *         version = false   // this repo edits versionName by hand
 *     }
 * }
 * ```
 *
 * Every flag here answers one question: may KiteSSOT apply a value it already
 * knows? Turn one off and KiteSSOT goes quiet. Your Android and Apple DSLs keep
 * exactly what they already say. Nothing is removed, cleared, or rewritten.
 *
 * The value does not disappear when a flag is off. `kiteSsotDoctor` still
 * resolves it and prints it, so you can see what would have been applied. These
 * are not safety switches either: no ordinary build mutates source, with or
 * without them.
 */
abstract class KiteSsotPropagateExtension {

    /**
     * Whether the app display name is applied.
     *
     * Default: `true`. Off leaves the Android manifest placeholder and the Apple
     * name keys as they are.
     */
    abstract val appName: Property<Boolean>

    /**
     * Whether the resolved platform identifiers are applied: the Android
     * application ID and the Apple bundle ID.
     *
     * Default: `true`. Off still computes and reports them, it just writes
     * nowhere.
     */
    abstract val bundleId: Property<Boolean>

    /**
     * Whether versions and build numbers are applied: Android `versionName` and
     * `versionCode`, Apple marketing version and build number.
     *
     * Default: `true`. Off beats every other version setting, including a
     * `scheme`, a `rebuild`, and a hand-written value. No number is written
     * anywhere.
     */
    abstract val version: Property<Boolean>

    /**
     * Whether the locale list reaches the features that consume it.
     *
     * Default: `true`. Packaging the Android app for those locales is a separate
     * decision and stays with `android.filterResourcesToLocales`.
     */
    abstract val locales: Property<Boolean>
}
