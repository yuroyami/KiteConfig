package io.github.yuroyami.kitessot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Browser Kotlin/JS settings inside `kiteSsot { web { ... } }`.
 *
 * KiteSSOT can generate a small, single-use Web Worker helper for selected
 * Kotlin/JS browser targets. For the normal `js` target, the generated source is
 * added to `jsMain`.
 *
 * This feature does not provide `Dispatchers.IO` and is not a cross-platform
 * worker runtime.
 */
abstract class KiteSsotWebExtension {

    init {
        // Fail closed: the plugin cannot infer whether a Kotlin/JS target is
        // browser-backed merely from KotlinPlatformType.js.
        browserTargetNames.convention(emptyList())
        projectPaths.convention(emptyList())
    }

    /**
     * Generate `kiteSsotOffload` for each selected browser target.
     *
     * The default is `false`. When enabled, the consumer must add
     * `kotlinx-coroutines-core`; KiteSSOT does not add it. The generated API needs
     * browser `Worker`, `Blob`, and object-URL APIs. It throws a clear exception when
     * those APIs are unavailable. Node.js-only targets and wasmJs are not supported.
     *
     * The helper executes raw JavaScript source as code. Only pass trusted source.
     * Never build it from user-controlled data. The deployed Content Security
     * Policy must allow Blob workers, normally with `worker-src blob:`.
     */
    abstract val generateIoWorker: Property<Boolean>

    /**
     * Exact Kotlin/JS target names that run in a browser.
     *
     * The default is an empty list. At least one name is required when
     * [generateIoWorker] is `true`. KiteSSOT does not guess whether a target is a
     * browser or Node.js target.
     *
     * For the normal `js { browser() }` target:
     *
     * ```kotlin
     * browserTargetNames.add("js")
     * ```
     *
     * Use the declared name for a custom target. For example, `js("web")` uses
     * `"web"`. Every listed name must resolve to a Kotlin/JS target.
     */
    abstract val browserTargetNames: ListProperty<String>

    /**
     * Exact Gradle project paths that may receive the generated helper.
     *
     * Use absolute paths such as `":shared"` or `":apps:web"`. Each project must
     * apply Kotlin Multiplatform. The default is an empty list. When empty,
     * KiteSSOT may use the one resolved shared KMP project. If it cannot choose one
     * project safely, generation fails.
     */
    abstract val projectPaths: ListProperty<String>

    /** Kotlin package for the generated helper. The default is `kitessot.generated`. */
    abstract val ioWorkerPackage: Property<String>
}
