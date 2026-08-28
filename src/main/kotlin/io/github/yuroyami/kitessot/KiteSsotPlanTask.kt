package io.github.yuroyami.kitessot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/**
 * Read-only report of the source-tree mutations the current configuration
 * authorizes.
 *
 * Central wiring supplies the stable operation names, the selected targets, and
 * the effective safety policies. It also supplies the configured mutation roots,
 * together with the deterministic backup, ownership, recovery, and coordination
 * paths derived from them.
 *
 * A directory entry can hold dynamically discovered Swift files. [exactChanges]
 * exposes the available summaries without running an installer.
 *
 * ## The four read-only tasks
 *
 * | Task | Answers | Fails the build |
 * |---|---|---|
 * | `kiteVerify` | which values did KiteSSOT resolve? | no |
 * | `kiteDoctor` | what is wrong with my setup? | no |
 * | `kiteCheck` | the same, for CI | yes, on ERROR findings |
 * | `kitePlan` | what would the mutation tasks write? | no |
 *
 * None of the four writes to your source tree, and none of them needs `dryRun`.
 * Colour is added when a real terminal is attached; `NO_COLOR`, `TERM=dumb`, and
 * `--console=plain` each turn it off, and `-Pkitessot.color=true|false` forces it.
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

    /** Presentation only, so it never affects up-to-date checks. */
    @get:Internal abstract val colorEnabled: Property<Boolean>
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

        val console = KiteSsotConsole(colorEnabled.getOrElse(false))
        fun section(title: String) = console.paint("  $title", KiteSsotStyle.SECTION)
        fun bullet(text: String, style: KiteSsotStyle = KiteSsotStyle.MUTED) =
            "    " + console.paint("- ", KiteSsotStyle.MUTED) + console.paint(text, style)

        logger.lifecycle(
            buildString {
                appendLine(console.paint("[kiteSsot] Mutation plan (read-only)", KiteSsotStyle.HEADING))
                appendLine(section("Operations"))
                values(resolvedOperations, "[none selected]").forEach {
                    appendLine(bullet(diagnosticSafeText(it), KiteSsotStyle.WARN))
                }
                appendLine(section("Selected targets"))
                values(resolvedTargets, "[none]").forEach { appendLine(bullet(diagnosticSafeText(it))) }
                appendLine(section("Mutation paths"))
                values(resolvedPaths, "[none]").forEach { configured ->
                    appendLine(bullet(diagnosticSafeText(describePath(root, configured)), KiteSsotStyle.PATH))
                }
                appendLine(section("Policies"))
                if (resolvedPolicies.isEmpty()) appendLine(bullet("[none]"))
                alignedRows(
                    resolvedPolicies.toSortedMap().map {
                        diagnosticSafeText(it.key) to diagnosticSafeText(it.value)
                    },
                    indent = "    ",
                    console = console,
                ).forEach(::appendLine)
                appendLine(section("Planned changes"))
                values(resolvedChanges, "[not calculated]").forEach {
                    appendLine(bullet(diagnosticSafeText(it), KiteSsotStyle.MUTED))
                }
                if (resolvedNotes.isNotEmpty()) {
                    appendLine(section("Notes"))
                    resolvedNotes.forEach { appendLine(bullet(diagnosticSafeText(it))) }
                }
                append(console.paint("  No files were changed.", KiteSsotStyle.PASS))
            },
        )
    }

    private fun describePath(root: java.nio.file.Path, configured: String): String = runCatching {
        val configuredPath = java.nio.file.Path.of(configured)
        val raw = (if (configuredPath.isAbsolute) configuredPath else root.resolve(configuredPath))
            .toAbsolutePath()
            .normalize()
        // Project-relative plan paths render with '/' on every OS so the report
        // is stable across platforms; Path.toString() would use '\' on Windows.
        val location = if (raw.startsWith(root)) {
            root.relativize(raw).joinToString("/").ifEmpty { "." }
        } else {
            "$raw [OUTSIDE PROJECT]"
        }
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
