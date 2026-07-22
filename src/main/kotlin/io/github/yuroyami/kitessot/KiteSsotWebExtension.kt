package io.github.yuroyami.kitessot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Browser Kotlin/JS options. Nested under `kiteSsot { web { ... } }`.
 *
 * This does not provide `Dispatchers.IO` and is not a cross-platform worker runtime.
 * Enabling [generateIoWorker] emits a small, single-shot browser Worker helper into
 * each selected Kotlin/JS target's generated source directory (`jsMain` for the
 * conventional `js` target).
 */
abstract class KiteSsotWebExtension {

    init {
        // Fail closed: the plugin cannot infer whether a Kotlin/JS target is
        // browser-backed merely from KotlinPlatformType.js.
        browserTargetNames.convention(emptyList())
        projectPaths.convention(emptyList())
    }

    /**
     * Generate an inline Web Worker offload helper (`kiteSsotOffload`) into a
     * plugin-owned generated source dir for each selected target. Default false.
     *
     * The consumer must declare `kotlinx-coroutines-core`; the plugin does not add
     * that dependency. The generated API is browser-only and fails with a clear
     * exception when browser `Worker`, `Blob`, and object-URL APIs are unavailable.
     * It is unsuitable for Node.js-only targets. wasmJs is unsupported and cannot
     * be selected.
     *
     * The API executes trusted raw JavaScript source. Never derive the job source
     * from user-controlled data. Deployments must permit Blob workers in Content
     * Security Policy, normally with `worker-src blob:`.
     */
    abstract val generateIoWorker: Property<Boolean>

    /**
     * Exact Kotlin/JS target names that are configured for a browser runtime and may
     * receive the generated helper. Default empty, so generation fails closed instead
     * of attaching browser APIs to an unknown or Node.js-only target.
     *
     * For the conventional `js { browser() }` target:
     *
     * ```
     * browserTargetNames.add("js")
     * ```
     *
     * Custom targets use their declared target name, for example `js("web")` uses
     * `"web"`. A listed name that does not identify a Kotlin/JS target is a
     * configuration error.
     */
    abstract val browserTargetNames: ListProperty<String>

    /**
     * Exact Gradle project paths whose selected browser targets may receive the
     * generated helper, for example `":shared"` or `":apps:web"`. Default empty.
     *
     * When empty, the plugin may use the uniquely resolved shared KMP project. It
     * must fail on ambiguity instead of generating the same top-level API into every
     * KMP module. Entries must be absolute Gradle paths and must identify projects
     * that apply Kotlin Multiplatform.
     */
    abstract val projectPaths: ListProperty<String>

    /** Package for the generated worker helper. Default `kitessot.generated`. */
    abstract val ioWorkerPackage: Property<String>
}
