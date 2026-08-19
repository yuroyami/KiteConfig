package io.github.yuroyami.kitessot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Kotlin/Native opt-in markers, inside `kiteSsot { nativeOptIns { ... } }`.
 *
 * ```kotlin
 * kiteSsot {
 *     nativeOptIns {
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
 * An empty `nativeOptIns { }` already does useful work. Opening the block turns
 * the feature on, and the built-in set covers the usual interop cases.
 */
abstract class KiteSsotNativeOptInsExtension {

    /**
     * Forces the whole block off without deleting it.
     *
     * Default: `true` as soon as the block is configured. `false` always wins,
     * so CI or a convention plugin can silence the feature while the
     * configuration stays in the build file.
     */
    abstract val enabled: Property<Boolean>

    /**
     * Whether KiteSSOT's own interop set is applied:
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
