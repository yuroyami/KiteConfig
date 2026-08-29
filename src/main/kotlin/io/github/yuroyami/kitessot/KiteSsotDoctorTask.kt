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
        colorEnabled.convention(false)
        agpOnClasspath.convention(false)
        agpRequired.convention(false)
        kgpRequired.convention(true)
        propagateDesktop.convention(false)
        composeOnClasspath.convention(false)
        composeRequired.convention(false)
        desktopApplicationProjects.convention(emptyList())
        detectedDesktopApplicationProjects.convention(emptyList())
        desktopDeriveUpgradeUuid.convention(false)
    }

    @get:Internal abstract val propagateAppName: Property<Boolean>
    @get:Internal abstract val appName: Property<String>
    @get:Internal abstract val propagateBundleId: Property<Boolean>
    @get:Internal abstract val iosBundleId: Property<String>
    @get:Internal abstract val propagateVersion: Property<Boolean>
    @get:Internal abstract val versionName: Property<String>
    /** Presentation only, so it never affects up-to-date checks. */
    @get:Internal abstract val colorEnabled: Property<Boolean>
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

    @get:Internal abstract val propagateDesktop: Property<Boolean>
    @get:Internal abstract val desktopIconsExplicit: Property<Boolean>
    @get:Internal abstract val composeOnClasspath: Property<Boolean>
    @get:Internal abstract val composeRequired: Property<Boolean>
    @get:Internal abstract val activeComposeVersion: Property<String>
    @get:Internal abstract val desktopApplicationProjects: ListProperty<String>
    @get:Internal abstract val detectedDesktopApplicationProjects: ListProperty<String>
    @get:Internal abstract val appId: Property<String>
    @get:Internal abstract val desktopBundleId: Property<String>
    @get:Internal abstract val desktopLinuxPackageName: Property<String>
    @get:Internal abstract val desktopDeriveUpgradeUuid: Property<Boolean>
    @get:Internal abstract val splashAndroid: Property<Boolean>
    @get:Internal abstract val splashDesktop: Property<Boolean>
    @get:Internal abstract val splashIosArmed: Property<Boolean>
    @get:Internal abstract val splashIos: Property<Boolean>
    @get:Internal abstract val splashThemeSet: Property<Boolean>
    @get:Internal abstract val splashManifestPlaceholderPresent: Property<Boolean>

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
            propagateAppName = resolve("KMPS901", "propagate { appName }", false) { propagateAppName.getOrElse(false) },
            appName = resolve<String?>("KMPS902", "appName", null) { appName.orNull },
            propagateBundleId = resolve("KMPS921", "propagate { bundleId }", false) { propagateBundleId.getOrElse(false) },
            iosBundleId = resolve<String?>("KMPS922", "iosBundleId", null) { iosBundleId.orNull },
            propagateVersion = resolve("KMPS903", "propagate { version }", false) { propagateVersion.getOrElse(false) },
            versionName = resolve<String?>("KMPS904", "version", null) { versionName.orNull },
            hasVersionCodeOverride = resolve("KMPS905", "android { versionCode }", false) { hasVersionCodeOverride.getOrElse(false) },
            resolvedVersionCode = resolve<Int?>("KMPS952", "versionCode", null) { resolvedVersionCode.orNull },
            propagateLocaleList = resolve("KMPS906", "propagate { locales }", false) { propagateLocaleList.getOrElse(false) },
            locales = resolve("KMPS907", "locales", emptyList()) { locales.getOrElse(emptyList()) },
            filterAndroidResources = resolve("KMPS938", "android { filterResourcesToLocales }", false) {
                filterAndroidResources.getOrElse(false)
            },
            syncIos = resolve("KMPS908", "ios { sync }", false) { syncIos.getOrElse(false) },
            sanitizeIosProject = resolve("KMPS918", "ios { sync { sanitizePlist } }", false) { sanitizeIosProject.getOrElse(false) },
            propagateLogo = resolve("KMPS917", "logo { }", false) { propagateLogo.getOrElse(false) },
            cleanupLegacyLogoArtifacts = resolve("KMPS939", "logo { takeOverLegacyIcons }", false) {
                cleanupLegacyLogoArtifacts.getOrElse(false)
            },
            iosMarketingVersion = resolve<String?>("KMPS919", "ios { marketingVersion }", null) { iosMarketingVersion.orNull },
            iosBuildNumber = resolve<String?>("KMPS920", "ios { buildNumber }", null) { iosBuildNumber.orNull },
            iosDeploymentTarget = resolve<String?>("KMPS940", "iosDeploymentTarget", null) {
                iosDeploymentTarget.orNull
            },
            usesNonExemptEncryption = resolve<Boolean?>("KMPS923", "usesNonExemptEncryption", null) { usesNonExemptEncryption.orNull },
            proMotion120Hz = resolve<Boolean?>("KMPS924", "proMotion120Hz", null) { proMotion120Hz.orNull },
            plistConflictPolicy = resolve("KMPS925", "plistConflictPolicy", PlistConflictPolicy.FAIL) {
                plistConflictPolicy.getOrElse(PlistConflictPolicy.FAIL)
            },
            iosTargetNames = resolve("KMPS909", "iosTargetNames", emptyList()) { iosTargetNames.getOrElse(emptyList()) },
            androidApplicationProjects = resolve("KMPS932", "modules { androidApps }", emptyList()) {
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
            propagateDesktop = resolve("KMPS941", "desktop { }", false) { propagateDesktop.getOrElse(false) },
            desktopIconsExplicit = resolve<Boolean?>("KMPS942", "desktop { icons }", null) { desktopIconsExplicit.orNull },
            composeOnClasspath = resolve("KMPS943", "composeOnClasspath", false) { composeOnClasspath.getOrElse(false) },
            composeRequired = resolve("KMPS944", "composeRequired", false) { composeRequired.getOrElse(false) },
            activeComposeVersion = resolve<String?>("KMPS945", "activeComposeVersion", null) { activeComposeVersion.orNull },
            desktopApplicationProjects = resolve("KMPS946", "modules { desktopApps }", emptyList()) {
                desktopApplicationProjects.getOrElse(emptyList())
            },
            detectedDesktopApplicationProjects = resolve(
                "KMPS947",
                "detectedDesktopApplicationProjects",
                emptyList(),
            ) { detectedDesktopApplicationProjects.getOrElse(emptyList()) },
            appId = resolve<String?>("KMPS948", "appId", null) { appId.orNull },
            desktopBundleId = resolve<String?>("KMPS949", "desktopBundleId", null) { desktopBundleId.orNull },
            desktopLinuxPackageName = resolve<String?>("KMPS950", "desktop { linuxPackageName }", null) {
                desktopLinuxPackageName.orNull
            },
            desktopDeriveUpgradeUuid = resolve("KMPS951", "desktop { deriveUpgradeUuid }", false) {
                desktopDeriveUpgradeUuid.getOrElse(false)
            },
            splashAndroid = resolve("KMPS960", "splash { }", false) { splashAndroid.getOrElse(false) },
            splashDesktop = resolve("KMPS961", "splash { }", false) { splashDesktop.getOrElse(false) },
            splashIosArmed = resolve("KMPS962", "splash { rewrite }", false) { splashIosArmed.getOrElse(false) },
            splashIos = resolve("KMPS963", "splash { rewrite }", false) { splashIos.getOrElse(false) },
            splashThemeSet = resolve("KMPS964", "splash { android { theme } }", false) { splashThemeSet.getOrElse(false) },
            splashManifestPlaceholderPresent = resolve<Boolean?>("KMPS965", "splash manifest placeholder", null) {
                splashManifestPlaceholderPresent.orNull
            },
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
@DisableCachingByDefault(because = "Reads mutable source-tree state and always reports current diagnostics.")
abstract class KiteSsotDoctorTask : KiteSsotDiagnosticTaskBase() {

    init {
        description = "Diagnose kitessot end-to-end without modifying files or failing on findings."
    }

    @TaskAction
    fun diagnose() {
        val findings = diagnosticFindings()
        logger.lifecycle(
            renderDiagnosticConsole(
                "Doctor report",
                findings,
                KiteSsotConsole(colorEnabled.getOrElse(false)),
            ),
        )
    }
}

internal fun renderDiagnosticConsole(
    heading: String,
    findings: List<KiteSsotDiagnostic>,
    console: KiteSsotConsole = KiteSsotConsole(colored = false),
): String = buildString {
    appendLine(console.paint("[kiteSsot] $heading", KiteSsotStyle.HEADING))
    findings.forEach { finding ->
        val (status, style) = when (finding.severity) {
            KiteSsotDiagnosticSeverity.PASS -> "PASS" to KiteSsotStyle.PASS
            KiteSsotDiagnosticSeverity.SKIPPED -> "SKIP" to KiteSsotStyle.SKIP
            KiteSsotDiagnosticSeverity.WARNING -> "WARN" to KiteSsotStyle.WARN
            KiteSsotDiagnosticSeverity.ERROR -> "FAIL" to KiteSsotStyle.FAIL
        }
        // One span for the whole line: a reset between the tag and the id would
        // break both grep and a consumer asserting on "[FAIL] KMPS021".
        val line = buildString {
            append("  [").append(status).append("] ")
            append(diagnosticSafeText(finding.id)).append(' ')
            append(diagnosticSafeText(finding.title)).append(": ")
            append(diagnosticSafeText(finding.detail))
        }
        appendLine(console.paint(line, style))
        finding.remediation?.let {
            appendLine(console.paint("         Fix: " + diagnosticSafeText(it), KiteSsotStyle.MUTED))
        }
    }
    val errors = findings.count { it.severity == KiteSsotDiagnosticSeverity.ERROR }
    val warnings = findings.count { it.severity == KiteSsotDiagnosticSeverity.WARNING }
    val summary = "  Summary: $errors error(s), $warnings warning(s), ${findings.size} total finding(s)."
    val summaryStyle = when {
        errors > 0 -> KiteSsotStyle.FAIL
        warnings > 0 -> KiteSsotStyle.WARN
        else -> KiteSsotStyle.PASS
    }
    append(console.paint(summary, summaryStyle))
}
