package io.github.yuroyami.kitessot

import org.gradle.api.Action
import org.gradle.api.plugins.ExtensionAware

/**
 * Browser Kotlin/JS options, inside `kiteSsot { web { ... } }`.
 *
 * ```kotlin
 * kiteSsot {
 *     web {
 *         ioWorker {
 *             targets("js")
 *         }
 *     }
 * }
 * ```
 *
 * Web here means the browser and nothing else. Node.js-only targets and wasmJs
 * are out of scope, and none of this is `Dispatchers.IO`.
 *
 * The block itself holds no settings. It is the address where browser features
 * live, and [ioWorker] is the only tenant today.
 */
abstract class KiteSsotWebExtension {

    /**
     * The generated Web Worker helper. Configure it with
     * `kiteSsot { web { ioWorker { ... } } }`.
     */
    val ioWorker: KiteSsotIoWorkerExtension
        get() = (this as ExtensionAware).extensions.getByType(KiteSsotIoWorkerExtension::class.java)

    /** Configure the nested worker helper model. Opening the block turns it on. */
    fun ioWorker(action: Action<in KiteSsotIoWorkerExtension>) {
        ioWorker.configured.set(true)
        action.execute(ioWorker)
    }
}
