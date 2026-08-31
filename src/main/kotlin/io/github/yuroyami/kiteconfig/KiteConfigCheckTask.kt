package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Machine-readable format emitted by [KiteConfigCheckTask]. */
enum class KiteConfigDiagnosticReportFormat {
    /** Deterministic plugin-native JSON finding list. */
    JSON,

    /** SARIF 2.1.0 for compatible CI code-scanning systems. */
    SARIF,
}

/**
 * Strict counterpart to [KiteConfigDoctorTask]. It runs the identical aggregate,
 * read-only checks, always writes a deterministic report, and fails after report
 * creation when ERROR findings (or optionally warnings) exist.
 *
 * ## The four read-only tasks
 *
 * | Task | Answers | Fails the build |
 * |---|---|---|
 * | `kiteVerify` | which values did KiteConfig resolve? | no |
 * | `kiteDoctor` | what is wrong with my setup? | no |
 * | `kiteCheck` | the same, for CI | yes, on ERROR findings |
 * | `kitePlan` | what would the mutation tasks write? | no |
 *
 * None of the four writes to your source tree, and none of them needs `dryRun`.
 * Colour is added when a real terminal is attached; `NO_COLOR`, `TERM=dumb`, and
 * `--console=plain` each turn it off, and `-Pkiteconfig.color=true|false` forces it.
 */
@DisableCachingByDefault(because = "Reads mutable source-tree state; diagnostics must reflect the current checkout.")
abstract class KiteConfigCheckTask : KiteConfigDiagnosticTaskBase() {

    init {
        description = "Check kiteconfig configuration, write a JSON/SARIF report, and fail on errors."
        failOnWarnings.convention(false)
        reportFormat.convention(KiteConfigDiagnosticReportFormat.JSON)
        reportFile.convention(
            project.layout.buildDirectory.file(
                reportFormat.map { format ->
                    "reports/kiteconfig/diagnostics.${if (format == KiteConfigDiagnosticReportFormat.SARIF) "sarif" else "json"}"
                },
            ),
        )
    }

    /** Also fail after writing the report when warnings exist. Default false. */
    @get:Input abstract val failOnWarnings: Property<Boolean>

    /** Machine-readable output format. Default [KiteConfigDiagnosticReportFormat.JSON]. */
    @get:Input abstract val reportFormat: Property<KiteConfigDiagnosticReportFormat>

    /**
     * Report destination. By default this follows [reportFormat] under
     * `build/reports/kiteconfig/diagnostics.json` or `diagnostics.sarif`.
     */
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun check() {
        val findings = diagnosticFindings()
        val format = reportFormat.get()
        val report = when (format) {
            KiteConfigDiagnosticReportFormat.JSON -> KiteConfigDiagnosticReports.json(findings)
            KiteConfigDiagnosticReportFormat.SARIF ->
                KiteConfigDiagnosticReports.sarif(findings, projectRootDir.asFile.orNull)
        }
        writeAtomically(reportFile.asFile.get().toPath(), report)
        logger.lifecycle(
            renderDiagnosticConsole(
                "Check report",
                findings,
                KiteConfigConsole(colorEnabled.getOrElse(false)),
            ),
        )
        logger.lifecycle(
            "[kiteConfig] ${format.name} diagnostics: " +
                diagnosticSafeText(reportFile.asFile.get().path),
        )

        val errors = findings.count { it.severity == KiteConfigDiagnosticSeverity.ERROR }
        val warnings = findings.count { it.severity == KiteConfigDiagnosticSeverity.WARNING }
        if (errors > 0 || (failOnWarnings.get() && warnings > 0)) {
            val warningClause = if (failOnWarnings.get()) ", $warnings warning(s)" else ""
            throw GradleException(
                "[kiteConfig] diagnostics failed with $errors error(s)$warningClause; see " +
                    diagnosticSafeText(reportFile.asFile.get().path),
            )
        }
    }

    private fun writeAtomically(path: java.nio.file.Path, content: String) {
        path.parent?.let(Files::createDirectories)
        val temp = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8)
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
