package io.github.yuroyami.kmpssot

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

/** Machine-readable format emitted by [KmpSsotCheckTask]. */
enum class KmpSsotDiagnosticReportFormat {
    /** Deterministic plugin-native JSON finding list. */
    JSON,

    /** SARIF 2.1.0 for compatible CI code-scanning systems. */
    SARIF,
}

/**
 * Strict counterpart to [KmpSsotDoctorTask]. It runs the identical aggregate,
 * read-only checks, always writes a deterministic report, and fails after report
 * creation when ERROR findings (or optionally warnings) exist.
 */
@DisableCachingByDefault(because = "Reads mutable source-tree state; diagnostics must reflect the current checkout.")
abstract class KmpSsotCheckTask : KmpSsotDiagnosticTaskBase() {

    init {
        description = "Check kmp-ssot configuration, write a JSON/SARIF report, and fail on errors."
        failOnWarnings.convention(false)
        reportFormat.convention(KmpSsotDiagnosticReportFormat.JSON)
        reportFile.convention(
            project.layout.buildDirectory.file(
                reportFormat.map { format ->
                    "reports/kmpssot/diagnostics.${if (format == KmpSsotDiagnosticReportFormat.SARIF) "sarif" else "json"}"
                },
            ),
        )
    }

    /** Also fail after writing the report when warnings exist. Default false. */
    @get:Input abstract val failOnWarnings: Property<Boolean>

    /** Machine-readable output format. Default [KmpSsotDiagnosticReportFormat.JSON]. */
    @get:Input abstract val reportFormat: Property<KmpSsotDiagnosticReportFormat>

    /**
     * Report destination. By default this follows [reportFormat] under
     * `build/reports/kmpssot/diagnostics.json` or `diagnostics.sarif`.
     */
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun check() {
        val findings = diagnosticFindings()
        val format = reportFormat.get()
        val report = when (format) {
            KmpSsotDiagnosticReportFormat.JSON -> KmpSsotDiagnosticReports.json(findings)
            KmpSsotDiagnosticReportFormat.SARIF ->
                KmpSsotDiagnosticReports.sarif(findings, projectRootDir.asFile.orNull)
        }
        writeAtomically(reportFile.asFile.get().toPath(), report)
        logger.lifecycle(renderDiagnosticConsole("Check report", findings))
        logger.lifecycle(
            "[kmpSsot] ${format.name} diagnostics: " +
                diagnosticSafeText(reportFile.asFile.get().path),
        )

        val errors = findings.count { it.severity == KmpSsotDiagnosticSeverity.ERROR }
        val warnings = findings.count { it.severity == KmpSsotDiagnosticSeverity.WARNING }
        if (errors > 0 || (failOnWarnings.get() && warnings > 0)) {
            val warningClause = if (failOnWarnings.get()) ", $warnings warning(s)" else ""
            throw GradleException(
                "[kmpSsot] diagnostics failed with $errors error(s)$warningClause; see " +
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
