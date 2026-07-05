package io.github.yuroyami.kmpssot

import org.gradle.api.provider.Property

/**
 * Web-target (js / wasmJs) options. Nested under `kmpSsot { web { ... } }`.
 *
 * Closes the "no `Dispatchers.IO` on the web target" gap at the toolchain level:
 * KMP cannot block the single JS main thread, so projects hand-roll a Web Worker
 * every time. Enabling [generateIoWorker] generates that boilerplate into the
 * `jsMain` source set instead.
 */
abstract class KmpSsotWebExtension {

    /**
     * Generate an inline Web Worker offload helper (`kmpSsotOffload`) into a
     * plugin-owned generated `jsMain` source dir. Default false.
     *
     * Generated code depends only on `kotlinx-coroutines-core`. **JS target
     * only** in this version — when a `wasmJs` target is present but no `js`
     * target, the plugin logs that wasmJs generation is not yet supported and
     * skips (no partial output).
     */
    abstract val generateIoWorker: Property<Boolean>

    /** Package for the generated worker helper. Default `kmpssot.generated`. */
    abstract val ioWorkerPackage: Property<String>
}
