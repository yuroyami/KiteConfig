package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Generate the inline Web Worker offload helper ([generateIoWorkerSource]) into
 * a plugin-owned generated source dir that is wired onto the module's `jsMain`
 * source set.
 *
 * Writes into `build/generated/kmpssot/jsMain/kotlin` — a distinct, plugin-owned
 * dir, never the user's hand-authored source tree — keeping the plugin's
 * platform-tree-only contract intact. Regenerated each build (cheap, idempotent
 * via [writeTextSafely]).
 */
abstract class GenerateIoWorkerTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Generate the inline Web Worker (kmpSsotOffload) helper into the JS source set."
    }

    @get:Input
    abstract val workerPackage: Property<String>

    /** Declared output so `srcDir(task.flatMap { outputDir })` carries the dependency. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * An @Input, not @Internal: a dry run writes nothing, so flipping it back to
     * false must invalidate up-to-date state or the real generation is skipped.
     */
    @get:Input
    abstract val dryRun: Property<Boolean>

    @TaskAction
    fun generate() {
        val pkg = workerPackage.get()
        val dir = outputDir.get().asFile.resolve(pkg.replace('.', '/'))
        val file = dir.resolve("KmpSsotIoWorker.kt")
        // Plugin-owned generated file — no backup (mirrors generated launcher icons).
        writeTextSafely(
            file = file,
            content = generateIoWorkerSource(pkg),
            backup = false,
            dryRun = dryRun.get(),
            logger = logger,
            label = "JS IO worker helper",
        )
    }
}
