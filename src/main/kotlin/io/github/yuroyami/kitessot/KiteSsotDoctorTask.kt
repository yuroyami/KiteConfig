package io.github.yuroyami.kitessot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Shared, read-only input wiring and resilient evaluation engine for the human
 * doctor and strict machine-readable check tasks. Filesystem, provider, and
 * parser failures become stable ERROR findings instead of escaping evaluation;
 * each concrete task decides whether those findings gate the build.
 */
@DisableCachingByDefault(
    because = "Reads mutable source-tree state; concrete diagnostic tasks must report the current checkout.",
)
abstract class KiteSsotDiagnosticTaskBase : DefaultTask() {

    init {
        group = "kitessot"
        outputs.upToDateWhen { false }
        iosTargetNames.convention(emptyList())
        plistConflictPolicy.convention(PlistConflictPolicy.FAIL)
        androidEmitMonochrome.convention(false)
        androidManifestPaths.convention(emptyList())
        androidResPaths.convention(emptyList())
        androidApplicationProjects.convention(emptyList())
        detectedAndroidApplicationProjects.convention(emptyList())
        agpOnClasspath.convention(false)
        agpRequired.convention(false)
        kgpRequired.convention(true)
    }

    @get:Internal abstract val propagateAppName: Property<Boolean>
    @get:Internal abstract val appName: Property<String>
    @get:Internal abstract val propagateBundleId: Property<Boolean>
    @get:Internal abstract val iosBundleId: Property<String>
    @get:Internal abstract val propagateVersion: Property<Boolean>
    @get:Internal abstract val versionName: Property<String>
    @get:Internal abstract val hasVersionCodeOverride: Property<Boolean>
    @get:Internal abstract val resolvedVersionCode: Property<Int>
    @get:Internal abstract val propagateLocaleList: Property<Boolean>
    @get:Internal abstract val locales: ListProperty<String>
    @get:Internal abstract val filterAndroidResources: Property<Boolean>
    @get:Internal abstract val syncIos: Property<Boolean>
    @get:Internal abstract val sanitizeIosProject: Property<Boolean>
    @get:Internal abstract val propagateLogo: Property<Boolean>
    @get:Internal abstract val cleanupLegacyLogoArtifacts: Property<Boolean>
    @get:Internal abstract val iosMarketingVersion: Property<String>
    @get:Internal abstract val iosBuildNumber: Property<String>
    @get:Internal abstract val usesNonExemptEncryption: Property<Boolean>
    @get:Internal abstract val proMotion120Hz: Property<Boolean>
    @get:Internal abstract val plistConflictPolicy: Property<PlistConflictPolicy>
    @get:Internal abstract val iosTargetNames: ListProperty<String>
    @get:Internal abstract val androidApplicationProjects: ListProperty<String>
    @get:Internal abstract val detectedAndroidApplicationProjects: ListProperty<String>

    @get:Internal abstract val manifestFile: RegularFileProperty
    @get:Internal abstract val androidManifestPaths: ListProperty<String>
    @get:Internal abstract val infoPlistFile: RegularFileProperty
    @get:Internal abstract val pbxprojFile: RegularFileProperty
    @get:Internal abstract val appiconsetDir: DirectoryProperty
    @get:Internal abstract val androidResDir: DirectoryProperty
    @get:Internal abstract val androidResPaths: ListProperty<String>
    @get:Internal abstract val projectRootDir: DirectoryProperty
    @get:Internal abstract val androidEmitMonochrome: Property<Boolean>
    @get:Internal abstract val androidLogoInputFingerprint: Property<String>
    @get:Internal abstract val iosLogoInputFingerprint: Property<String>
    @get:Internal abstract val iosDeploymentTarget: Property<String>

    @get:Internal abstract val agpOnClasspath: Property<Boolean>
    @get:Internal abstract val agpRequired: Property<Boolean>
    @get:Internal abstract val activeAgpVersion: Property<String>
    @get:Internal abstract val kgpOnClasspath: Property<Boolean>
    @get:Internal abstract val kgpRequired: Property<Boolean>
    @get:Internal abstract val activeKgpVersion: Property<String>

    internal fun diagnosticFindings(): List<KiteSsotDiagnostic> {
        val resolutionFindings = mutableListOf<KiteSsotDiagnostic>()

        fun <T> resolve(id: String, name: String, fallback: T, value: () -> T): T =
            runCatching(value).getOrElse { failure ->
                resolutionFindings += KiteSsotDiagnostic(
                    id,
                    KiteSsotDiagnosticSeverity.ERROR,
                    "Configuration provider '$name'",
                    "Could not resolve $name: ${diagnosticExceptionSummary(failure)}",
                    "Fix the provider or DSL value so it can be resolved during task execution.",
                )
                fallback
            }

        val context = KiteSsotDiagnosticContext(
            propagateAppName = resolve("KMPS901", "propagateAppName", false) { propagateAppName.getOrElse(false) },
            appName = resolve<String?>("KMPS902", "appName", null) { appName.orNull },
            propagateBundleId = resolve("KMPS921", "propagateBundleId", false) { propagateBundleId.getOrElse(false) },
            iosBundleId = resolve<String?>("KMPS922", "iosBundleId", null) { iosBundleId.orNull },
            propagateVersion = resolve("KMPS903", "propagateVersion", false) { propagateVersion.getOrElse(false) },
            versionName = resolve<String?>("KMPS904", "versionName", null) { versionName.orNull },
            hasVersionCodeOverride = resolve("KMPS905", "versionCodeOverride", false) { hasVersionCodeOverride.getOrElse(false) },
            resolvedVersionCode = resolve<Int?>("KMPS905", "versionCode", null) { resolvedVersionCode.orNull },
            propagateLocaleList = resolve("KMPS906", "propagateLocaleList", false) { propagateLocaleList.getOrElse(false) },
            locales = resolve("KMPS907", "locales", emptyList()) { locales.getOrElse(emptyList()) },
            filterAndroidResources = resolve("KMPS938", "filterAndroidResources", false) {
                filterAndroidResources.getOrElse(false)
            },
            syncIos = resolve("KMPS908", "syncIos", false) { syncIos.getOrElse(false) },
            sanitizeIosProject = resolve("KMPS918", "sanitizeIosProject", false) { sanitizeIosProject.getOrElse(false) },
            propagateLogo = resolve("KMPS917", "propagateLogo", false) { propagateLogo.getOrElse(false) },
            cleanupLegacyLogoArtifacts = resolve("KMPS939", "cleanupLegacyLogoArtifacts", false) {
                cleanupLegacyLogoArtifacts.getOrElse(false)
            },
            iosMarketingVersion = resolve<String?>("KMPS919", "iosMarketingVersion", null) { iosMarketingVersion.orNull },
            iosBuildNumber = resolve<String?>("KMPS920", "iosBuildNumber", null) { iosBuildNumber.orNull },
            iosDeploymentTarget = resolve<String?>("KMPS940", "iosDeploymentTarget", null) {
                iosDeploymentTarget.orNull
            },
            usesNonExemptEncryption = resolve<Boolean?>("KMPS923", "usesNonExemptEncryption", null) { usesNonExemptEncryption.orNull },
            proMotion120Hz = resolve<Boolean?>("KMPS924", "proMotion120Hz", null) { proMotion120Hz.orNull },
            plistConflictPolicy = resolve("KMPS925", "plistConflictPolicy", PlistConflictPolicy.FAIL) {
                plistConflictPolicy.getOrElse(PlistConflictPolicy.FAIL)
            },
            iosTargetNames = resolve("KMPS909", "iosTargetNames", emptyList()) { iosTargetNames.getOrElse(emptyList()) },
            androidApplicationProjects = resolve("KMPS932", "androidApplicationProjects", emptyList()) {
                androidApplicationProjects.getOrElse(emptyList())
            },
            detectedAndroidApplicationProjects = resolve(
                "KMPS933",
                "detectedAndroidApplicationProjects",
                emptyList(),
            ) { detectedAndroidApplicationProjects.getOrElse(emptyList()) },
            manifestFile = resolve<java.io.File?>("KMPS910", "manifestFile", null) { manifestFile.asFile.orNull },
            androidManifestFiles = resolve("KMPS928", "androidManifestPaths", emptyList()) {
                androidManifestPaths.getOrElse(emptyList()).map { java.io.File(it) }
            },
            infoPlistFile = resolve<java.io.File?>("KMPS911", "infoPlistFile", null) { infoPlistFile.asFile.orNull },
            pbxprojFile = resolve<java.io.File?>("KMPS912", "pbxprojFile", null) { pbxprojFile.asFile.orNull },
            appiconsetDir = resolve<java.io.File?>("KMPS913", "appiconsetDir", null) { appiconsetDir.asFile.orNull },
            androidResDir = resolve<java.io.File?>("KMPS914", "androidResDir", null) { androidResDir.asFile.orNull },
            androidResDirs = resolve("KMPS929", "androidResPaths", emptyList()) {
                androidResPaths.getOrElse(emptyList()).map { java.io.File(it) }
            },
            projectRootDir = resolve<java.io.File?>("KMPS926", "projectRootDir", null) { projectRootDir.asFile.orNull },
            androidEmitMonochrome = resolve("KMPS927", "androidEmitMonochrome", false) {
                androidEmitMonochrome.getOrElse(false)
            },
            androidLogoInputFingerprint = resolve<String?>("KMPS930", "androidLogoInputFingerprint", null) {
                androidLogoInputFingerprint.orNull
            },
            iosLogoInputFingerprint = resolve<String?>("KMPS931", "iosLogoInputFingerprint", null) {
                iosLogoInputFingerprint.orNull
            },
            agpOnClasspath = resolve("KMPS934", "agpOnClasspath", false) { agpOnClasspath.getOrElse(false) },
            agpRequired = resolve("KMPS935", "agpRequired", false) { agpRequired.getOrElse(false) },
            activeAgpVersion = resolve<String?>("KMPS936", "activeAgpVersion", null) { activeAgpVersion.orNull },
            kgpOnClasspath = resolve("KMPS915", "kgpOnClasspath", false) { kgpOnClasspath.getOrElse(false) },
            kgpRequired = resolve("KMPS916", "kgpRequired", true) { kgpRequired.getOrElse(true) },
            activeKgpVersion = resolve<String?>("KMPS937", "activeKgpVersion", null) { activeKgpVersion.orNull },
        )

        val evaluated = runCatching { KiteSsotDiagnosticEngine.evaluate(context) }.getOrElse { failure ->
            listOf(
                KiteSsotDiagnostic(
                    "KMPS999",
                    KiteSsotDiagnosticSeverity.ERROR,
                    "Diagnostic engine",
                    "Unexpected diagnostic failure: ${diagnosticExceptionSummary(failure)}",
                    "Report this as a kitessot bug with --stacktrace output.",
                ),
            )
        }
        return resolutionFindings + evaluated
    }
}

/**
 * Resilient human-readable setup diagnostic. Every supported check is attempted
 * and reported with a stable ID. This task never fails merely because findings
 * contain errors; use [KiteSsotCheckTask] for CI enforcement.
 */
@DisableCachingByDefault(because = "Reads mutable source-tree state and always reports current diagnostics.")
abstract class KiteSsotDoctorTask : KiteSsotDiagnosticTaskBase() {

    init {
        description = "Diagnose kitessot end-to-end without modifying files or failing on findings."
    }

    @TaskAction
    fun diagnose() {
        val findings = diagnosticFindings()
        logger.lifecycle(renderDiagnosticConsole("Doctor report", findings))
    }
}

internal fun renderDiagnosticConsole(heading: String, findings: List<KiteSsotDiagnostic>): String = buildString {
    appendLine("[kiteSsot] $heading:")
    findings.forEach { finding ->
        val status = when (finding.severity) {
            KiteSsotDiagnosticSeverity.PASS -> "PASS"
            KiteSsotDiagnosticSeverity.SKIPPED -> "SKIP"
            KiteSsotDiagnosticSeverity.WARNING -> "WARN"
            KiteSsotDiagnosticSeverity.ERROR -> "FAIL"
        }
        append("  [$status] ${diagnosticSafeText(finding.id)} ${diagnosticSafeText(finding.title)}: ")
        append(diagnosticSafeText(finding.detail))
        finding.remediation?.let { append(" Fix: ").append(diagnosticSafeText(it)) }
        appendLine()
    }
    val errors = findings.count { it.severity == KiteSsotDiagnosticSeverity.ERROR }
    val warnings = findings.count { it.severity == KiteSsotDiagnosticSeverity.WARNING }
    append("  Summary: $errors error(s), $warnings warning(s), ${findings.size} total finding(s).")
}
