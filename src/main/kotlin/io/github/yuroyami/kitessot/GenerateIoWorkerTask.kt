package io.github.yuroyami.kitessot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets

/**
 * Generate the inline Web Worker offload helper ([generateIoWorkerSource]) into
 * a plugin-owned generated source dir that is wired onto the selected Kotlin/JS
 * target's source set (`jsMain` for the conventional `js` target).
 *
 * Writes into `build/generated/kitessot/<target>Main/kotlin`, a distinct
 * plugin-owned dir. It never writes into the user's hand-authored source tree, which
 * keeps the plugin's platform-tree-only contract intact. A checksum ownership manifest
 * removes only outputs from the previous generation, rejecting symlinks and
 * unknown content.
 *
 * Cacheable: pure codegen with a declared `@Input` package and an
 * `@OutputDirectory`, so Gradle skips it when nothing changed and restores the
 * output from the build cache otherwise.
 *
 * Output lands in the plugin-owned build directory and never in your
 * hand-written source tree, so this task needs no ownership or backup rails.
 * It is a normal cacheable Gradle task with declared inputs and outputs.
 */
@CacheableTask
abstract class GenerateIoWorkerTask : DefaultTask() {

    init {
        group = "kitessot"
        description = "Generate the inline Web Worker (kiteSsotOffload) helper into the JS source set."
        generatedRoot.convention(project.layout.buildDirectory.dir("generated/kitessot"))
    }

    @get:Input
    abstract val workerPackage: Property<String>

    /** Declared output so `srcDir(task.flatMap { outputDir })` carries the dependency. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Safety boundary for plugin-owned generated sources. */
    @get:Internal
    abstract val generatedRoot: DirectoryProperty

    /**
     * Build-owned code generation must remain correct in preview mode. Source-tree
     * migration tasks honor dry-run; this generated compilation input does not.
     */
    @get:Internal
    abstract val dryRun: Property<Boolean>

    @TaskAction
    fun generate() {
        val pkg = workerPackage.get()
        val root = outputDir.get().asFile
        val source = generateIoWorkerSource(pkg)
        if (dryRun.getOrElse(false)) {
            logger.info("[kiteSsot] dryRun does not suppress build-owned Web Worker generation.")
        }
        val relative = "${pkg.replace('.', '/')}/KiteSsotIoWorker.kt"
        OwnedOutputSafety.replaceGeneratedTree(
            outputRoot = root,
            allowedRoot = generatedRoot.get().asFile,
            owner = "io-worker",
            files = mapOf(relative to source.toByteArray(StandardCharsets.UTF_8)),
        )
        logger.info("[kiteSsot] Generated Web Worker helper: ${root.resolve(relative)}")
    }
}
