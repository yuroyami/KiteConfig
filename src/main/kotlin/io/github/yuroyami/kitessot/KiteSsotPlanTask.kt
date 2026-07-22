package io.github.yuroyami.kitessot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/**
 * Read-only mutation-plan report. Central wiring supplies stable operation names,
 * configured source-tree mutation roots plus deterministic backup, ownership,
 * recovery, and coordination paths, the selected targets, and effective safety
 * policies. A directory entry can contain dynamically discovered Swift files;
 * [exactChanges] exposes available summaries without executing an installer.
 */
@DisableCachingByDefault(because = "Reports current filesystem state and is intentionally always run.")
abstract class KiteSsotPlanTask : DefaultTask() {

    init {
        group = "kitessot"
        description = "Report selected kitessot mutation paths and safety policies without changing files."
        outputs.upToDateWhen { false }
        operations.convention(emptyList())
        mutationPaths.convention(emptyList())
        selectedTargets.convention(emptyList())
        policies.convention(emptyMap())
        exactChanges.convention(emptyList())
        notes.convention(emptyList())
        projectRootDir.convention(project.layout.projectDirectory)
    }

    @get:Internal abstract val projectRootDir: DirectoryProperty
    @get:Internal abstract val operations: ListProperty<String>
    @get:Internal abstract val mutationPaths: ListProperty<String>
    @get:Internal abstract val selectedTargets: ListProperty<String>
    @get:Internal abstract val policies: MapProperty<String, String>
    @get:Internal abstract val exactChanges: ListProperty<String>
    @get:Internal abstract val notes: ListProperty<String>

    @TaskAction
    fun report() {
        val root = projectRootDir.asFile.get().toPath().toAbsolutePath().normalize()
        fun values(values: List<String>, empty: String) = values.ifEmpty { listOf(empty) }
        fun <T> resolved(label: String, fallback: T, read: () -> T): T = runCatching(read).getOrElse {
            logger.warn(
                "[kiteSsot] Plan could not resolve ${diagnosticSafeText(label)}: " +
                    diagnosticExceptionSummary(it),
            )
            fallback
        }
        val resolvedOperations = resolved("operations", emptyList()) { operations.getOrElse(emptyList()) }
        val resolvedTargets = resolved("selectedTargets", emptyList()) { selectedTargets.getOrElse(emptyList()) }
        val resolvedPaths = resolved("mutationPaths", emptyList()) { mutationPaths.getOrElse(emptyList()) }
        val resolvedPolicies = resolved("policies", emptyMap()) { policies.getOrElse(emptyMap()) }
        val resolvedChanges = resolved("exactChanges", emptyList()) { exactChanges.getOrElse(emptyList()) }
        val resolvedNotes = resolved("notes", emptyList()) { notes.getOrElse(emptyList()) }

        logger.lifecycle(
            buildString {
                appendLine("[kiteSsot] Mutation plan (read-only):")
                appendLine("  Operations:")
                values(resolvedOperations, "[none selected]").forEach {
                    appendLine("    - ${diagnosticSafeText(it)}")
                }
                appendLine("  Selected targets:")
                values(resolvedTargets, "[none]").forEach { appendLine("    - ${diagnosticSafeText(it)}") }
                appendLine("  Mutation paths:")
                values(resolvedPaths, "[none]").forEach { configured ->
                    appendLine("    - ${diagnosticSafeText(describePath(root, configured))}")
                }
                appendLine("  Policies:")
                if (resolvedPolicies.isEmpty()) appendLine("    - [none]")
                resolvedPolicies.toSortedMap().forEach { (name, value) ->
                    appendLine("    - ${diagnosticSafeText(name)} = ${diagnosticSafeText(value)}")
                }
                appendLine("  Planned changes:")
                values(resolvedChanges, "[not calculated]").forEach {
                    appendLine("    - ${diagnosticSafeText(it)}")
                }
                if (resolvedNotes.isNotEmpty()) {
                    appendLine("  Notes:")
                    resolvedNotes.forEach { appendLine("    - ${diagnosticSafeText(it)}") }
                }
                append("  No files were changed.")
            },
        )
    }

    private fun describePath(root: java.nio.file.Path, configured: String): String = runCatching {
        val configuredPath = java.nio.file.Path.of(configured)
        val raw = (if (configuredPath.isAbsolute) configuredPath else root.resolve(configuredPath))
            .toAbsolutePath()
            .normalize()
        val location = if (raw.startsWith(root)) root.relativize(raw).toString().ifEmpty { "." } else "$raw [OUTSIDE PROJECT]"
        val state = when {
            Files.isSymbolicLink(raw) -> "symlink"
            Files.isDirectory(raw, NOFOLLOW_LINKS) -> "directory"
            Files.isRegularFile(raw, NOFOLLOW_LINKS) -> "file"
            Files.exists(raw, NOFOLLOW_LINKS) -> "other"
            else -> "missing"
        }
        "$location [$state]"
    }.getOrElse { "$configured [inspection error: ${diagnosticExceptionSummary(it)}]" }
}
