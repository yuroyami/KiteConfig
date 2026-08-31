package io.github.yuroyami.kiteconfig

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Kotlin/Native opt-in markers, inside `kiteConfig { optIns { ... } }`.
 *
 * ```kotlin
 * kiteConfig {
 *     optIns {
 *         add("kotlin.experimental.ExperimentalObjCRefinement")
 *     }
 * }
 * ```
 *
 * Obj-C and cinterop calls each want an `@OptIn(...)` above them. This block
 * sets the markers once at the compiler level, and only on Native compilations,
 * where those markers actually resolve. The annotations leave your call sites
 * and your shared code is never edited.
 *
 * An empty `optIns { }` already does useful work. Opening the block turns
 * the feature on, and the built-in set covers the usual interop cases.
 *
 * ## What ends up on the compiler command line
 *
 * | You write | Markers applied |
 * |---|---|
 * | no block at all | none, the feature is off |
 * | `optIns { }` | the built-in interop set |
 * | `optIns { add("x") }` | the built-in set plus `x` |
 * | `optIns { builtIns = false; add("x") }` | only `x` |
 *
 * Markers reach Kotlin/Native compilations only, in the shared project or in
 * whatever [projects] names. They are never written into your source.
 *
 * @see KiteConfigModulesExtension.shared for the project used when [projects] is empty.
 */
abstract class KiteConfigNativeOptInsExtension {

    /**
     * Whether KiteConfig's own interop set is applied:
     * `kotlinx.cinterop.ExperimentalForeignApi`,
     * `kotlin.experimental.ExperimentalObjCName`, and
     * `kotlin.experimental.ExperimentalNativeApi`.
     *
     * Default: `true`. Off leaves only [markers], so an empty list then opts in
     * to nothing at all.
     */
    abstract val builtIns: Property<Boolean>

    /**
     * Extra opt-in markers, fully qualified, such as
     * `"kotlin.experimental.ExperimentalObjCRefinement"`.
     *
     * Default: empty. These join the built-in set unless [builtIns] is off.
     * Duplicates are dropped and the order you declare is kept.
     */
    abstract val markers: ListProperty<String>

    /**
     * Adds one or more fully qualified markers to [markers].
     *
     * This adds, never replaces, so repeated calls pile up.
     *
     * ```kotlin
     * add("kotlin.experimental.ExperimentalObjCRefinement")
     * ```
     */
    fun add(vararg markers: String) {
        this.markers.addAll(*markers)
    }

    /**
     * Exact KMP project paths that receive the markers, absolute, such as
     * `":shared"` or `":core:network"`.
     *
     * Default: empty, which means the project resolved by `modules.shared`.
     * Every path you list must apply Kotlin Multiplatform.
     */
    abstract val projects: ListProperty<String>

    /**
     * Adds one or more project paths to [projects].
     *
     * This adds, never replaces, so repeated calls pile up.
     *
     * ```kotlin
     * projects(":shared", ":core:network")
     * ```
     */
    fun projects(vararg paths: String) {
        projects.addAll(*paths)
    }
}
