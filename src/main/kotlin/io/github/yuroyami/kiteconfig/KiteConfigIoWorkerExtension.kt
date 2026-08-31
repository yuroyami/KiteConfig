package io.github.yuroyami.kiteconfig

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * The generated browser Web Worker helper, inside
 * `kiteConfig { web { ioWorker { ... } } }`.
 *
 * ```kotlin
 * kiteConfig {
 *     web {
 *         ioWorker {
 *             targets("js")
 *             packageName = "com.acme.app.generated"
 *         }
 *     }
 * }
 * ```
 *
 * Opening this block turns generation on. KiteConfig then writes a
 * `kiteConfigOffload` function into each listed target's source set, so the plain
 * `js` target gets it in `jsMain`. The function runs one JavaScript job on a
 * throwaway worker, waits for the answer, and terminates the worker.
 *
 * This is a small browser helper, not `Dispatchers.IO` and not a cross-platform
 * worker runtime. It needs the browser `Worker`, `Blob`, and object-URL APIs, so
 * Node.js-only targets and wasmJs are unsupported. You also add
 * `kotlinx-coroutines-core` yourself: KiteConfig never adds a dependency for you.
 *
 * The helper executes raw JavaScript source as code. Only pass source you wrote
 * and trust, never anything built from user-controlled data. The deployed
 * Content Security Policy must allow Blob workers too, normally with
 * `worker-src blob:`.
 *
 * ## Before this compiles in your app
 *
 * | You must | Because |
 * |---|---|
 * | name a browser target with [targets] | browser capability is never inferred |
 * | add `kotlinx-coroutines-core` yourself | the generated code suspends, and KiteConfig adds no dependencies |
 * | allow `worker-src blob:` in your CSP | the worker is created from a Blob URL |
 *
 * ## What it is not
 *
 * | Not | Instead |
 * |---|---|
 * | `Dispatchers.IO` | one job, one throwaway worker, no pool |
 * | a cross-platform API | browser Kotlin/JS only |
 * | a typed transport | strings in, strings out |
 *
 * @see KiteConfigWebExtension for the enclosing block.
 */
abstract class KiteConfigIoWorkerExtension {

    /** Set when the enclosing `ioWorker { }` block is opened. Presence is the opt-in. */
    internal abstract val declared: Property<Boolean>

    /**
     * Exact Kotlin/JS target names that run in a browser.
     *
     * Default: empty, and at least one name is required while [enabled] is
     * `true`. Plain `js { browser() }` is `"js"`, and `js("web")` is `"web"`.
     *
     * KiteConfig does not guess whether a Kotlin/JS target is a browser target or
     * a Node.js target, so nothing is generated for a target you leave out.
     * Every name must match a declared Kotlin/JS target.
     */
    abstract val targets: ListProperty<String>

    /**
     * Adds one or more browser target names to [targets].
     *
     * This adds, never replaces, so repeated calls pile up.
     *
     * ```kotlin
     * targets("js")
     * ```
     */
    fun targets(vararg names: String) {
        targets.addAll(*names)
    }

    /**
     * Exact Gradle project paths that receive the helper, absolute, such as
     * `":shared"` or `":apps:web"`. Each one must apply Kotlin Multiplatform.
     *
     * Default: empty, which means `modules.shared`. Generation fails when no
     * single project can be chosen safely.
     */
    abstract val projects: ListProperty<String>

    /**
     * Adds one or more project paths to [projects].
     *
     * This adds, never replaces, so repeated calls pile up.
     *
     * ```kotlin
     * projects(":shared", ":apps:web")
     * ```
     */
    fun projects(vararg paths: String) {
        projects.addAll(*paths)
    }

    /**
     * Kotlin package of the generated helper, written as dot-separated
     * identifiers such as `com.acme.app.generated`.
     *
     * Default: `kiteconfig.generated`. Kotlin hard keywords are rejected as
     * segments because the generated file would not compile.
     */
    abstract val packageName: Property<String>
}
