package io.github.yuroyami.kiteconfig

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.net.URI

/** Stable severity used by human- and machine-readable kiteconfig diagnostic reports. */
internal enum class KiteConfigDiagnosticSeverity {
    PASS,
    SKIPPED,
    WARNING,
    ERROR,
}

/**
 * One internal diagnostic finding. [id] is a stable report contract intended for
 * CI suppressions and IDE integrations; it must not be derived from prose.
 */
internal data class KiteConfigDiagnostic(
    val id: String,
    val severity: KiteConfigDiagnosticSeverity,
    val title: String,
    val detail: String,
    val remediation: String? = null,
    val location: String? = null,
    val expected: String? = null,
    val actual: String? = null,
)

/** Immutable input to the read-only diagnostic engine. */
internal data class KiteConfigDiagnosticContext(
    val propagateAppName: Boolean = true,
    val appName: String? = null,
    val propagateBundleId: Boolean = true,
    val iosBundleId: String? = null,
    val propagateVersion: Boolean = true,
    val versionName: String? = null,
    val hasVersionCodeOverride: Boolean = false,
    val resolvedVersionCode: Int? = null,
    val propagateLocaleList: Boolean = true,
    val locales: List<String> = emptyList(),
    val filterAndroidResources: Boolean = false,
    val syncIos: Boolean = false,
    val sanitizeIosProject: Boolean = false,
    val propagateLogo: Boolean = false,
    val cleanupLegacyLogoArtifacts: Boolean = false,
    val iosMarketingVersion: String? = null,
    val iosBuildNumber: String? = null,
    val usesNonExemptEncryption: Boolean? = null,
    val proMotion120Hz: Boolean? = null,
    val plistConflictPolicy: PlistConflictPolicy = PlistConflictPolicy.FAIL,
    val iosTargetNames: List<String> = emptyList(),
    val androidApplicationProjects: List<String> = emptyList(),
    val detectedAndroidApplicationProjects: List<String> = emptyList(),
    val manifestFile: File? = null,
    val androidManifestFiles: List<File> = emptyList(),
    val infoPlistFile: File? = null,
    val pbxprojFile: File? = null,
    val appiconsetDir: File? = null,
    val androidResDir: File? = null,
    val androidResDirs: List<File> = emptyList(),
    val projectRootDir: File? = null,
    val androidEmitMonochrome: Boolean = false,
    val androidLogoInputFingerprint: String? = null,
    val iosLogoInputFingerprint: String? = null,
    val iosDeploymentTarget: String? = null,
    val agpOnClasspath: Boolean = false,
    val agpRequired: Boolean = false,
    val activeAgpVersion: String? = null,
    val kgpOnClasspath: Boolean = false,
    val kgpRequired: Boolean = false,
    val activeKgpVersion: String? = null,
    val propagateDesktop: Boolean = false,
    val desktopIconsExplicit: Boolean? = null,
    val composeOnClasspath: Boolean = false,
    val composeRequired: Boolean = false,
    val activeComposeVersion: String? = null,
    val desktopApplicationProjects: List<String> = emptyList(),
    val detectedDesktopApplicationProjects: List<String> = emptyList(),
    val appId: String? = null,
    val desktopBundleId: String? = null,
    val desktopLinuxPackageName: String? = null,
    val desktopDeriveUpgradeUuid: Boolean = false,
    val splashAndroid: Boolean = false,
    val splashDesktop: Boolean = false,
    val splashIosArmed: Boolean = false,
    val splashIos: Boolean = false,
    val splashThemeSet: Boolean = false,
    val splashManifestPlaceholderPresent: Boolean? = null,
    val versionGuardsIgnored: Boolean = false,
    val autoRewrites: Boolean = false,
)

/** Pure orchestration around guarded filesystem reads and fail-closed parsers. */
internal object KiteConfigDiagnosticEngine {

    fun evaluate(context: KiteConfigDiagnosticContext): List<KiteConfigDiagnostic> = buildList {
        diagnoseAndroidApplicationSelection(context)
        diagnoseIosTargetSelection(context)
        diagnoseIosAppName(context)
        diagnoseAndroidManifest(context)
        diagnoseAndroidLauncherIconReferences(context)
        diagnoseInfoPlist(context)
        diagnosePbxproj(context)
        diagnoseAndroidIcons(context)
        diagnoseLocales(context)
        diagnoseVersion(context)
        diagnoseKgp(context)
        diagnosePluginCompatibility(context)
        diagnoseDesktop(context)
        diagnoseSplash(context)
    }

    /** KTCNFG090-092: splash delivery status and its two Android prerequisites. */
    private fun MutableList<KiteConfigDiagnostic>.diagnoseSplash(context: KiteConfigDiagnosticContext) {
        when {
            !context.splashAndroid && !context.splashDesktop && !context.splashIosArmed -> add(
                diagnostic("KTCNFG090", KiteConfigDiagnosticSeverity.SKIPPED, "Splash screen", "splash { } is not configured."),
            )
            else -> add(
                diagnostic(
                    "KTCNFG090",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Splash screen",
                    buildString {
                        append("android=").append(if (context.splashAndroid) "on" else "off")
                        append(", desktop=").append(if (context.splashDesktop) "on" else "off")
                        append(", ios=").append(
                            when {
                                context.splashIos -> "on"
                                context.splashIosArmed -> "armed, waiting for ios { rewrite { } }"
                                else -> "off"
                            },
                        )
                    },
                ),
            )
        }
        if (context.splashAndroid && !context.splashThemeSet) {
            add(
                diagnostic(
                    "KTCNFG091",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Splash Android theme",
                    "splash { } flows to Android, but splash { android { theme } } is not set.",
                    "Set it to your app theme name, for example android { theme = \"AppTheme\" }.",
                ),
            )
        }
        if (context.splashAndroid && context.splashManifestPlaceholderPresent == false) {
            add(
                diagnostic(
                    "KTCNFG092",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Splash manifest placeholder",
                    "The Android manifest does not reference the generated splash theme.",
                    "Add android:theme=\"\${kiteSplashTheme}\" to the application or launcher activity element once.",
                ),
            )
        }
        if (context.autoRewrites) {
            add(
                diagnostic(
                    "KTCNFG094",
                    KiteConfigDiagnosticSeverity.WARNING,
                    "Auto rewrites",
                    "rewrite { auto = true } is on: ordinary builds edit committed source files.",
                    "Expect dirty git trees on clean checkouts. Turn auto off to go back to explicit kiteRewrite* runs.",
                ),
            )
        }
        if (context.splashIosArmed && !context.splashIos) {
            add(
                diagnostic(
                    "KTCNFG093",
                    KiteConfigDiagnosticSeverity.WARNING,
                    "Splash iOS delivery",
                    "splash { rewrite { } } is armed, but the Xcode rewrite is not.",
                    "Add ios { rewrite { } } so kiteRewriteXcode can deliver the launch screen.",
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseDesktop(context: KiteConfigDiagnosticContext) {
        diagnoseDesktopIdentity(context)
        diagnoseDesktopIcons(context)
        diagnoseComposeCompatibility(context)
        diagnoseDesktopApplicationSelection(context)
        diagnoseDesktopBundleId(context)
        diagnoseDesktopPackageVersion(context)
        diagnoseDesktopLinuxPackageName(context)
        diagnoseWindowsUpgradeCode(context)
    }

    /** What gets written to Compose Desktop, gated on `desktop { }` being opened at all. */
    private fun MutableList<KiteConfigDiagnostic>.diagnoseDesktopIdentity(context: KiteConfigDiagnosticContext) {
        if (!context.propagateDesktop) {
            add(diagnostic("KTCNFG080", KiteConfigDiagnosticSeverity.SKIPPED, "Desktop identity propagation", "desktop { } is not enabled."))
            return
        }
        val propagated = buildList {
            if (context.propagateAppName && context.appName != null) add("packageName from appName")
            if (context.propagateVersion && context.versionName != null) add("packageVersion and macOS build number from version")
            if (context.propagateBundleId && context.desktopBundleId != null) add("macOS.bundleID from appId")
        }
        if (propagated.isEmpty()) {
            add(
                diagnostic(
                    "KTCNFG080",
                    KiteConfigDiagnosticSeverity.WARNING,
                    "Desktop identity propagation",
                    "desktop { } is enabled, but appName, version, and appId are all unset, so no identity value " +
                        "is written to Compose Desktop.",
                    "Set appName, version, and appId at the kiteConfig { } root, or remove desktop { }.",
                ),
            )
        } else {
            add(
                diagnostic(
                    "KTCNFG080",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Desktop identity propagation",
                    "desktop { } writes: ${propagated.joinToString("; ")}.",
                ),
            )
        }
    }

    /**
     * The one check that carries real weight. `desktop { icons = true }` with no
     * usable `logo { }` is a hard configuration failure that runs after the
     * resilient-diagnostic early return, so a real build fails but kiteDoctor
     * would otherwise stay silent about it. This mirrors that exact condition,
     * regardless of whether desktop { } itself is enabled.
     */
    private fun MutableList<KiteConfigDiagnostic>.diagnoseDesktopIcons(context: KiteConfigDiagnosticContext) {
        when {
            context.desktopIconsExplicit == false -> add(
                diagnostic("KTCNFG081", KiteConfigDiagnosticSeverity.SKIPPED, "Desktop app icons", "logo { } is absent or skips desktop; icon generation is off."),
            )
            !context.propagateDesktop -> add(
                diagnostic("KTCNFG081", KiteConfigDiagnosticSeverity.SKIPPED, "Desktop app icons", "desktop { } is not enabled."),
            )

            else -> add(
                diagnostic(
                    "KTCNFG081",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Desktop app icons",
                    "Desktop icon generation is enabled from the configured logo { } art.",
                ),
            )
        }
    }

    /** Mirrors KTCNFG061/KTCNFG062: a soft diagnostic for the same classpath and version gate. */
    private fun MutableList<KiteConfigDiagnostic>.diagnoseComposeCompatibility(context: KiteConfigDiagnosticContext) {
        when {
            !context.composeRequired -> add(
                diagnostic(
                    "KTCNFG082",
                    KiteConfigDiagnosticSeverity.SKIPPED,
                    "Compose Gradle plugin compatibility",
                    "No detected Compose Desktop project needs an enabled typed Compose integration.",
                ),
            )
            !context.composeOnClasspath -> add(
                diagnostic(
                    "KTCNFG082",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Compose Gradle plugin compatibility",
                    "Compose types are not visible to kiteconfig, so the active version cannot be verified.",
                    "Declare org.jetbrains.compose with apply false in the root plugins block.",
                ),
            )
            context.activeComposeVersion == null -> add(
                diagnostic(
                    "KTCNFG082",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Compose Gradle plugin compatibility",
                    "Could not determine the active Compose Gradle plugin version.",
                    "Use a Compose release in the supported range 1.11.0 through 1.12.x.",
                    expected = "1.11.0..1.12.x",
                ),
            )
            !isSupportedComposeVersion(context.activeComposeVersion) -> add(
                diagnostic(
                    "KTCNFG082",
                    if (context.versionGuardsIgnored) KiteConfigDiagnosticSeverity.WARNING else KiteConfigDiagnosticSeverity.ERROR,
                    "Compose Gradle plugin compatibility",
                    "Active Compose Gradle plugin ${context.activeComposeVersion} is unsupported." +
                        if (context.versionGuardsIgnored) " ignoreVersionGuards keeps it active." else "",
                    "Use a Compose release in the supported range 1.11.0 through 1.12.x.",
                    expected = "1.11.0..1.12.x",
                    actual = context.activeComposeVersion,
                ),
            )
            else -> add(
                diagnostic(
                    "KTCNFG082",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Compose Gradle plugin compatibility",
                    "Active Compose Gradle plugin ${context.activeComposeVersion} is supported.",
                ),
            )
        }
    }

    /** Mirrors KTCNFG070 (Android application selection) for `modules { desktopApps }`. */
    private fun MutableList<KiteConfigDiagnostic>.diagnoseDesktopApplicationSelection(context: KiteConfigDiagnosticContext) {
        if (!context.propagateDesktop) {
            add(diagnostic("KTCNFG083", KiteConfigDiagnosticSeverity.SKIPPED, "Desktop application selection", "desktop { } is not enabled."))
            return
        }
        val selected = context.desktopApplicationProjects
        if (selected.isEmpty()) {
            val detected = context.detectedDesktopApplicationProjects.distinct().sorted()
            if (detected.size > 1) {
                add(
                    diagnostic(
                        "KTCNFG083",
                        KiteConfigDiagnosticSeverity.ERROR,
                        "Desktop application selection",
                        "Multiple Compose Desktop application projects are eligible for active identity values: " +
                            "${detected.joinToString()}; no explicit modules { desktopApps } selector is configured.",
                        "Select every intended application explicitly with unique absolute Gradle project paths.",
                    ),
                )
            } else {
                add(
                    diagnostic(
                        "KTCNFG083",
                        KiteConfigDiagnosticSeverity.SKIPPED,
                        "Desktop application selection",
                        "No explicit modules { desktopApps } selector is configured; " +
                            if (detected.size == 1) {
                                "the sole detected application is unambiguous."
                            } else {
                                "no Compose Desktop application was detected."
                            },
                    ),
                )
            }
            return
        }
        val invalid = selected.filter { selector ->
            runCatching { validateGradleProjectPath(selector, "modules { desktopApps }") }.isFailure
        }
        val duplicates = selected.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        val unknown = selected.asSequence()
            .filterNot(invalid::contains)
            .filterNot(context.detectedDesktopApplicationProjects.toSet()::contains)
            .distinct()
            .sorted()
            .toList()
        val problems = buildList {
            if (invalid.isNotEmpty()) {
                add("invalid absolute Gradle project path(s): ${invalid.distinct().joinToString { renderSelector(it) }}")
            }
            if (duplicates.isNotEmpty()) {
                add("duplicate selector(s): ${duplicates.joinToString { renderSelector(it) }}")
            }
            if (unknown.isNotEmpty()) {
                add(
                    "selector(s) do not identify a detected Compose Desktop application: " +
                        unknown.joinToString { renderSelector(it) },
                )
            }
        }
        if (problems.isEmpty()) {
            add(
                diagnostic(
                    "KTCNFG083",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Desktop application selection",
                    "Every explicit Compose Desktop application selector resolves exactly: ${selected.joinToString()}.",
                ),
            )
        } else {
            add(
                diagnostic(
                    "KTCNFG083",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Desktop application selection",
                    problems.joinToString("; "),
                    "Use unique absolute Gradle paths for projects that apply org.jetbrains.compose and configure " +
                        "a desktop application (for example :desktopApp).",
                ),
            )
        }
    }

    /** The bundle ID gets the same treatment as version: checked before Compose sees it. */
    private fun MutableList<KiteConfigDiagnostic>.diagnoseDesktopBundleId(context: KiteConfigDiagnosticContext) {
        val raw = context.desktopBundleId
        if (!context.propagateDesktop || !context.propagateBundleId || raw == null) {
            add(
                diagnostic(
                    "KTCNFG084",
                    KiteConfigDiagnosticSeverity.SKIPPED,
                    "Desktop bundle identifier",
                    "desktop { } is not enabled, bundle-id propagation is disabled, or appId is unset.",
                ),
            )
            return
        }
        runCatching { validateAppleBundleId(raw) }.fold(
            onSuccess = { value ->
                add(
                    diagnostic(
                        "KTCNFG084",
                        KiteConfigDiagnosticSeverity.PASS,
                        "Desktop bundle identifier",
                        "Resolved desktop bundle identifier \"$value\" is valid reverse-DNS.",
                    ),
                )
            },
            onFailure = { failure ->
                add(
                    diagnostic(
                        "KTCNFG084",
                        KiteConfigDiagnosticSeverity.ERROR,
                        "Desktop bundle identifier",
                        diagnosticExceptionSummary(failure),
                        "Use reverse-DNS segments containing letters, digits, or hyphens, for appId and " +
                            "desktop { idSuffix } together.",
                        actual = diagnosticSafeText(raw, 255),
                    ),
                )
            },
        )
    }

    /**
     * Windows is the only real failure mode, and only when Msi or Exe is an
     * enabled target format, which this read-only diagnostic cannot see. A numeric
     * version that would exceed the cap is reported as a warning rather than an
     * error, so kiteCheck does not fail CI over a format the project may
     * never enable.
     */
    private fun MutableList<KiteConfigDiagnostic>.diagnoseDesktopPackageVersion(context: KiteConfigDiagnosticContext) {
        val version = context.versionName
        if (!context.propagateDesktop || !context.propagateVersion || version == null) {
            add(
                diagnostic(
                    "KTCNFG085",
                    KiteConfigDiagnosticSeverity.SKIPPED,
                    "Desktop package version",
                    "desktop { } is not enabled, version propagation is disabled, or version is unset.",
                ),
            )
            return
        }
        // No pre-filter here: a present-but-unparseable component (non-numeric or
        // Int-overflowing) must reach validateDesktopPackageVersion and warn, the
        // same as an over-cap component. It already fails closed on those and only
        // defaults an ABSENT component to 0; short-circuiting to PASS here for any
        // input the filter did not recognize contradicted that and hid a real build
        // failure behind a passing doctor check.
        runCatching { validateDesktopPackageVersion(version, setOf("Msi", "Exe")) }.fold(
            onSuccess = {
                add(
                    diagnostic(
                        "KTCNFG085",
                        KiteConfigDiagnosticSeverity.PASS,
                        "Desktop package version",
                        "\"$version\" satisfies the Windows MSI/EXE numeric limits (255, 255, 65535).",
                    ),
                )
            },
            onFailure = { failure ->
                add(
                    diagnostic(
                        "KTCNFG085",
                        KiteConfigDiagnosticSeverity.WARNING,
                        "Desktop package version",
                        diagnosticExceptionSummary(failure),
                        "This only fails the real build when Msi or Exe is an enabled desktop target format, " +
                            "which kiteDoctor cannot see; lower the offending version component or confirm " +
                            "those formats stay disabled.",
                    ),
                )
            },
        )
    }

    /** Debian names must be lowercase and start with an alphanumeric; an explicit override always wins. */
    private fun MutableList<KiteConfigDiagnostic>.diagnoseDesktopLinuxPackageName(context: KiteConfigDiagnosticContext) {
        val appName = context.appName
        if (!context.propagateDesktop || !context.propagateAppName || appName == null) {
            add(
                diagnostic(
                    "KTCNFG086",
                    KiteConfigDiagnosticSeverity.SKIPPED,
                    "Desktop Linux package name",
                    "desktop { } is not enabled, app-name propagation is disabled, or appName is unset.",
                ),
            )
            return
        }
        val explicit = context.desktopLinuxPackageName
        if (explicit != null) {
            add(
                diagnostic(
                    "KTCNFG086",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Desktop Linux package name",
                    "desktop { linuxPackageName } is set explicitly to \"$explicit\"; no derivation is needed.",
                ),
            )
            return
        }
        runCatching { deriveLinuxPackageName(appName) }.fold(
            onSuccess = { slug ->
                add(
                    diagnostic(
                        "KTCNFG086",
                        KiteConfigDiagnosticSeverity.PASS,
                        "Desktop Linux package name",
                        "appName derives to the Debian-legal package name \"$slug\" when Deb or Rpm is enabled.",
                    ),
                )
            },
            onFailure = { failure ->
                add(
                    diagnostic(
                        "KTCNFG086",
                        KiteConfigDiagnosticSeverity.ERROR,
                        "Desktop Linux package name",
                        diagnosticExceptionSummary(failure),
                        "Set desktop { linuxPackageName } explicitly.",
                    ),
                )
            },
        )
    }

    /** kiteDoctor always prints the resolved UUID, whether derived or not, so it can be checked before release. */
    private fun MutableList<KiteConfigDiagnostic>.diagnoseWindowsUpgradeCode(context: KiteConfigDiagnosticContext) {
        val appId = context.appId
        when {
            !context.propagateDesktop -> add(
                diagnostic("KTCNFG087", KiteConfigDiagnosticSeverity.SKIPPED, "Windows upgrade code", "desktop { } is not enabled."),
            )
            !context.desktopDeriveUpgradeUuid -> add(
                diagnostic(
                    "KTCNFG087",
                    KiteConfigDiagnosticSeverity.SKIPPED,
                    "Windows upgrade code",
                    "desktop { deriveUpgradeUuid } is disabled (the default); jpackage derives an upgrade code " +
                        "from the app name unless windows.upgradeUuid is set explicitly, and that code changes " +
                        "if the app is renamed.",
                ),
            )
            !context.propagateBundleId || appId == null -> add(
                diagnostic(
                    "KTCNFG087",
                    KiteConfigDiagnosticSeverity.SKIPPED,
                    "Windows upgrade code",
                    "desktop { deriveUpgradeUuid } is enabled, but bundle-id propagation is disabled or appId is unset.",
                ),
            )
            else -> add(
                diagnostic(
                    "KTCNFG087",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Windows upgrade code",
                    "appId derives to Windows upgradeUuid ${deriveUpgradeUuid(appId)}. " +
                        "An upgradeUuid already set on the module always wins.",
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseAndroidApplicationSelection(
        context: KiteConfigDiagnosticContext,
    ) {
        val selected = context.androidApplicationProjects
        if (selected.isEmpty()) {
            val detected = context.detectedAndroidApplicationProjects.distinct().sorted()
            val needsSelection =
                (context.propagateAppName && context.appName != null) ||
                    (context.propagateBundleId && context.iosBundleId != null) ||
                    (context.propagateVersion &&
                        (context.versionName != null || context.hasVersionCodeOverride)) ||
                    context.filterAndroidResources || context.propagateLogo ||
                    context.cleanupLegacyLogoArtifacts
            if (needsSelection && detected.size > 1) {
                add(
                    diagnostic(
                        "KTCNFG070",
                        KiteConfigDiagnosticSeverity.ERROR,
                        "Android application selection",
                        "Multiple Android application projects are eligible for active app-scoped values: " +
                            "${detected.joinToString()}; no explicit modules { androidApps } selector is configured.",
                        "Select every intended application explicitly with unique absolute Gradle project paths.",
                    ),
                )
            } else {
                add(
                    diagnostic(
                        "KTCNFG070",
                        KiteConfigDiagnosticSeverity.SKIPPED,
                        "Android application selection",
                        "No explicit modules { androidApps } selector is configured; " +
                            if (detected.size == 1) {
                                "the sole detected application is unambiguous."
                            } else {
                                "no active app-scoped value requires disambiguation."
                            },
                    ),
                )
            }
            return
        }

        val invalid = selected.filter { selector ->
            runCatching { validateGradleProjectPath(selector, "modules { androidApps }") }.isFailure
        }
        val duplicates = selected.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        val unknown = selected.asSequence()
            .filterNot(invalid::contains)
            .filterNot(context.detectedAndroidApplicationProjects.toSet()::contains)
            .distinct()
            .sorted()
            .toList()
        val problems = buildList {
            if (invalid.isNotEmpty()) {
                add("invalid absolute Gradle project path(s): ${invalid.distinct().joinToString { renderSelector(it) }}")
            }
            if (duplicates.isNotEmpty()) {
                add("duplicate selector(s): ${duplicates.joinToString { renderSelector(it) }}")
            }
            if (unknown.isNotEmpty()) {
                add(
                    "selector(s) do not identify a project applying com.android.application: " +
                        unknown.joinToString { renderSelector(it) },
                )
            }
            if (selected.distinct().size > 1 && context.propagateBundleId && context.iosBundleId != null) {
                add("one propagated bundle identifier cannot be assigned to multiple Android applications")
            }
            if (selected.distinct().size > 1 && (context.propagateLogo || context.cleanupLegacyLogoArtifacts)) {
                add("logo installation and legacy-logo cleanup require at most one effective Android application")
            }
        }
        if (problems.isEmpty()) {
            add(
                diagnostic(
                    "KTCNFG070",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Android application selection",
                    "Every explicit Android application selector resolves exactly: ${selected.joinToString()}.",
                ),
            )
        } else {
            add(
                diagnostic(
                    "KTCNFG070",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Android application selection",
                    problems.joinToString("; "),
                    "Use unique absolute Gradle paths for projects that apply com.android.application (for example :app).",
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseIosTargetSelection(context: KiteConfigDiagnosticContext) {
        if (!context.syncIos) {
            add(
                diagnostic(
                    "KTCNFG071",
                    KiteConfigDiagnosticSeverity.SKIPPED,
                    "iOS target selection",
                    "iOS source synchronization is disabled.",
                ),
            )
            return
        }
        val selected = context.iosTargetNames
        if (selected.isEmpty()) {
            add(
                diagnostic(
                    "KTCNFG071",
                    KiteConfigDiagnosticSeverity.PASS,
                    "iOS target selection",
                    "No explicit target is configured; pbxproj inspection will require exactly one application target.",
                ),
            )
            return
        }

        val invalid = selected.filter { it.isBlank() || it.any(Char::isISOControl) }
        val duplicates = selected.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        val problems = buildList {
            if (invalid.isNotEmpty()) {
                add("blank or control-bearing target name(s): ${invalid.distinct().joinToString { renderSelector(it) }}")
            }
            if (duplicates.isNotEmpty()) {
                add("duplicate target name(s): ${duplicates.joinToString { renderSelector(it) }}")
            }
            if (selected.distinct().size > 1 && context.propagateBundleId && context.iosBundleId != null) {
                add("one propagated bundle identifier cannot be assigned to multiple iOS application targets")
            }
        }
        if (problems.isEmpty()) {
            add(
                diagnostic(
                    "KTCNFG071",
                    KiteConfigDiagnosticSeverity.PASS,
                    "iOS target selection",
                    "Every explicit iOS target name is unique and non-blank: ${selected.joinToString()}.",
                ),
            )
        } else {
            add(
                diagnostic(
                    "KTCNFG071",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "iOS target selection",
                    problems.joinToString("; "),
                    "Use unique, non-blank Xcode application target names without control characters.",
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseIosAppName(context: KiteConfigDiagnosticContext) {
        val name = context.appName
        when {
            !context.syncIos || !context.propagateAppName || name == null -> add(
                diagnostic(
                    "KTCNFG012",
                    KiteConfigDiagnosticSeverity.SKIPPED,
                    "Apple bundle name",
                    "Apple app-name propagation is disabled or appName is unset.",
                ),
            )
            name.length >= 16 -> add(
                diagnostic(
                    "KTCNFG012",
                    KiteConfigDiagnosticSeverity.WARNING,
                    "Apple bundle name",
                    "appName has ${name.length} characters; Apple recommends CFBundleName contain fewer than 16.",
                    "Use a shorter cross-platform appName, or disable app-name propagation and configure Apple product/display names per target.",
                ),
            )
            else -> add(
                diagnostic(
                    "KTCNFG012",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Apple bundle name",
                    "appName fits Apple's recommended CFBundleName length.",
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseAndroidManifest(context: KiteConfigDiagnosticContext) {
        if (!context.propagateAppName || context.appName == null) {
            add(diagnostic("KTCNFG001", KiteConfigDiagnosticSeverity.SKIPPED, "Android manifest", "App-name propagation is disabled or appName is unset."))
            return
        }
        val manifests = context.androidManifestFiles.ifEmpty { listOfNotNull(context.manifestFile) }
        if (manifests.isEmpty()) return
        manifests.forEach { manifest -> diagnoseAndroidManifestFile(context, manifest) }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseAndroidManifestFile(
        context: KiteConfigDiagnosticContext,
        manifest: File,
    ) {
        val manifestExists = safeExists(context, manifest, "KTCNFG001", "Android manifest") ?: return
        if (!manifestExists) {
            add(
                diagnostic(
                    "KTCNFG001",
                    KiteConfigDiagnosticSeverity.WARNING,
                    "Android manifest",
                    "Manifest not found at ${context.shortPath(manifest)}; the appName placeholder cannot be verified.",
                    "Point the Android application selection at a project with src/main/AndroidManifest.xml.",
                ),
            )
            return
        }
        val text = safeRead(context, manifest, "KTCNFG002", "Android manifest") ?: return
        val label = runCatching { androidApplicationAttributes(text).label }.getOrElse { failure ->
            add(ioFailure("KTCNFG002", "Android manifest", manifest, failure))
            return
        }
        if (label == "\${appName}") {
            add(diagnostic("KTCNFG002", KiteConfigDiagnosticSeverity.PASS, "Android manifest", "android:label can resolve the \${appName} manifest placeholder."))
        } else {
            add(
                diagnostic(
                    "KTCNFG002",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Android manifest",
                    "The <application> label is '${label.ifEmpty { "<unset>" }}', not the \${appName} placeholder.",
                    "Set the application label to android:label=\"\${appName}\" or disable app-name propagation.",
                    location = manifest.path,
                    expected = "\${appName}",
                    actual = label.ifEmpty { null },
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseAndroidLauncherIconReferences(
        context: KiteConfigDiagnosticContext,
    ) {
        if (!context.propagateLogo) {
            add(
                diagnostic(
                    "KTCNFG003",
                    KiteConfigDiagnosticSeverity.SKIPPED,
                    "Android launcher icon references",
                    "Logo propagation is disabled.",
                ),
            )
            return
        }
        val manifests = context.androidManifestFiles.ifEmpty { listOfNotNull(context.manifestFile) }
        if (manifests.isEmpty()) {
            add(
                diagnostic(
                    "KTCNFG003",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Android launcher icon references",
                    "No selected Android application manifest is available, so installed launcher resources cannot be proven reachable.",
                    "Select one Android application and point its main manifest at @mipmap/ic_launcher.",
                ),
            )
            return
        }
        manifests.forEach { manifest -> diagnoseAndroidLauncherIconManifest(context, manifest) }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseAndroidLauncherIconManifest(
        context: KiteConfigDiagnosticContext,
        manifest: File,
    ) {
        val exists = safeExists(context, manifest, "KTCNFG003", "Android launcher icon references") ?: return
        if (!exists) {
            add(
                diagnostic(
                    "KTCNFG003",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Android launcher icon references",
                    "Manifest not found at ${context.shortPath(manifest)}; installed launcher resources cannot be consumed.",
                    "Create/select the application manifest and reference @mipmap/ic_launcher.",
                    location = manifest.path,
                ),
            )
            return
        }
        val text = safeRead(context, manifest, "KTCNFG003", "Android launcher icon references") ?: return
        val attributes = runCatching { androidApplicationAttributes(text) }.getOrElse { failure ->
            add(ioFailure("KTCNFG003", "Android launcher icon references", manifest, failure))
            return
        }
        val icon = attributes.icon
        val roundIcon = attributes.roundIcon
        val wrongIcon = icon != ANDROID_LAUNCHER_ICON_REFERENCE
        val wrongRoundIcon = roundIcon.isNotEmpty() && roundIcon != ANDROID_ROUND_ICON_REFERENCE
        when {
            wrongIcon || wrongRoundIcon -> add(
                diagnostic(
                    "KTCNFG003",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Android launcher icon references",
                    "The selected <application> does not consume the launcher resources installed by kiteconfig.",
                    "Set android:icon=\"$ANDROID_LAUNCHER_ICON_REFERENCE\" and, when roundIcon is present, " +
                        "android:roundIcon=\"$ANDROID_ROUND_ICON_REFERENCE\".",
                    location = manifest.path,
                    expected = "$ANDROID_LAUNCHER_ICON_REFERENCE; $ANDROID_ROUND_ICON_REFERENCE",
                    actual = diagnosticSafeText("${icon.ifEmpty { "<unset>" }}; ${roundIcon.ifEmpty { "<unset>" }}"),
                ),
            )
            roundIcon.isEmpty() -> add(
                diagnostic(
                    "KTCNFG003",
                    KiteConfigDiagnosticSeverity.WARNING,
                    "Android launcher icon references",
                    "android:icon consumes the generated launcher icon, but android:roundIcon is unset.",
                    "Set android:roundIcon=\"$ANDROID_ROUND_ICON_REFERENCE\" to consume the generated round asset on launchers that support it.",
                    location = manifest.path,
                ),
            )
            else -> add(
                diagnostic(
                    "KTCNFG003",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Android launcher icon references",
                    "The selected application manifest consumes both generated launcher icon resources.",
                    location = manifest.path,
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseInfoPlist(context: KiteConfigDiagnosticContext) {
        if (!context.syncIos || !context.sanitizeIosProject) {
            add(diagnostic("KTCNFG010", KiteConfigDiagnosticSeverity.SKIPPED, "iOS Info.plist", "Source-plist sanitization is disabled."))
            return
        }
        val plist = context.infoPlistFile
        if (plist == null) return
        val plistExists = safeExists(context, plist, "KTCNFG010", "iOS Info.plist") ?: return
        if (!plistExists) {
            add(
                diagnostic(
                    "KTCNFG010",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "iOS Info.plist",
                    "Configured source Info.plist does not exist at ${context.shortPath(plist)}.",
                    "Correct ios { infoPlist } or disable ios { sync { sanitizePlist } } for a generated plist.",
                    location = plist.path,
                ),
            )
            return
        }
        val text = safeRead(context, plist, "KTCNFG011", "iOS Info.plist") ?: return
        val stringEntries = buildList {
            if (context.propagateAppName && context.appName != null) {
                add(PlistStringEntry("CFBundleDisplayName", "\$(PRODUCT_NAME)"))
                add(PlistStringEntry("CFBundleName", "\$(PRODUCT_NAME)"))
            }
            if (context.propagateVersion && context.iosMarketingVersion != null) {
                add(PlistStringEntry("CFBundleShortVersionString", "\$(MARKETING_VERSION)"))
            }
            if (context.propagateVersion && context.iosBuildNumber != null) {
                add(PlistStringEntry("CFBundleVersion", "\$(CURRENT_PROJECT_VERSION)"))
            }
        }
        val boolEntries = buildList {
            context.usesNonExemptEncryption?.let {
                add(PlistBoolEntry("ITSAppUsesNonExemptEncryption", it))
            }
            context.proMotion120Hz?.let {
                add(PlistBoolEntry("CADisableMinimumFrameDurationOnPhone", it))
            }
        }
        if (stringEntries.isEmpty() && boolEntries.isEmpty()) {
            add(diagnostic("KTCNFG011", KiteConfigDiagnosticSeverity.SKIPPED, "iOS Info.plist", "No source-plist key is configured for verification."))
            return
        }
        val result = sanitizeInfoPlist(text, stringEntries, boolEntries, context.plistConflictPolicy)
        when {
            result.errors.isNotEmpty() -> add(
                diagnostic(
                    "KTCNFG011",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "iOS Info.plist",
                    result.errors.joinToString("; "),
                    "Correct the plist structure/value; for a valid existing-value conflict, explicitly select KEEP or REPLACE.",
                    location = plist.path,
                ),
            )

            result.text != null -> {
                val changes = buildList {
                    if (result.inserted.isNotEmpty()) {
                        add("insert ${result.inserted.joinToString()}")
                    }
                    if (result.overwritten.isNotEmpty()) {
                        add("overwrite ${result.overwritten.joinToString()} under conflictPolicy=REPLACE")
                    }
                    addAll(result.warnings)
                }
                add(
                    diagnostic(
                        "KTCNFG011",
                        KiteConfigDiagnosticSeverity.ERROR,
                        "iOS Info.plist",
                        "The source plist is not synchronized; migration would ${changes.joinToString("; ")}.",
                        "Review and run the explicit source-plist migration.",
                        location = plist.path,
                    ),
                )
            }

            result.warnings.isNotEmpty() -> add(
                diagnostic(
                    "KTCNFG011",
                    KiteConfigDiagnosticSeverity.WARNING,
                    "iOS Info.plist",
                    result.warnings.joinToString("; "),
                    "The configured KEEP policy intentionally preserves this drift; align the value or select REPLACE to resolve it.",
                    location = plist.path,
                ),
            )

            else -> add(
                diagnostic(
                    "KTCNFG011",
                    KiteConfigDiagnosticSeverity.PASS,
                    "iOS Info.plist",
                    "Every configured source-plist reference is structurally correct.",
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnosePbxproj(context: KiteConfigDiagnosticContext) {
        if (!context.syncIos) {
            add(diagnostic("KTCNFG020", KiteConfigDiagnosticSeverity.SKIPPED, "iOS project", "iOS source synchronization is disabled."))
            return
        }
        val appIconName = when {
            !context.propagateLogo -> null
            context.appiconsetDir == null -> {
                add(
                    diagnostic(
                        "KTCNFG024",
                        KiteConfigDiagnosticSeverity.ERROR,
                        "Xcode AppIcon selection",
                        "Logo propagation is enabled, but ios { appIconDirectory } is unset.",
                        "Configure a named .appiconset directory and select that catalog in every application configuration.",
                    ),
                )
                null
            }
            else -> runCatching { iosAppIconCatalogName(context.appiconsetDir.name) }.fold(
                onSuccess = { name ->
                    add(
                        diagnostic(
                            "KTCNFG024",
                            KiteConfigDiagnosticSeverity.PASS,
                            "Xcode AppIcon selection",
                            "The configured catalog maps to ASSETCATALOG_COMPILER_APPICON_NAME=$name; selected-target alignment is checked with KTCNFG021.",
                        ),
                    )
                    name
                },
                onFailure = { failure ->
                    add(
                        diagnostic(
                            "KTCNFG024",
                            KiteConfigDiagnosticSeverity.ERROR,
                            "Xcode AppIcon selection",
                            diagnosticExceptionSummary(failure),
                            "Point ios { appIconDirectory } at a named .appiconset directory.",
                        ),
                    )
                    null
                },
            )
        }
        val pbxproj = context.pbxprojFile
        if (pbxproj != null && validIosTargetNames(context.iosTargetNames)) {
            val pbxprojExists = safeExists(context, pbxproj, "KTCNFG020", "iOS pbxproj")
            if (pbxprojExists == false) {
                add(
                    diagnostic(
                        "KTCNFG020",
                        KiteConfigDiagnosticSeverity.ERROR,
                        "iOS pbxproj",
                        "Project file not found at ${context.shortPath(pbxproj)}.",
                        "Correct ios { pbxproj } or remove the ios { sync { } } block.",
                    ),
                )
            } else if (pbxprojExists == true) {
                val text = safeRead(context, pbxproj, "KTCNFG021", "iOS pbxproj")
                val result = text?.let { source ->
                    runCatching {
                        rewritePbxproj(
                            original = source,
                            marketingVersion = context.iosMarketingVersion.takeIf { context.propagateVersion },
                            buildNumber = context.iosBuildNumber.takeIf { context.propagateVersion },
                            appName = context.appName.takeIf { context.propagateAppName },
                            bundleId = context.iosBundleId.takeIf { context.propagateBundleId },
                            locales = context.locales.takeIf { context.propagateLocaleList && it.isNotEmpty() },
                            targetNames = context.iosTargetNames.toSet(),
                            appIconName = appIconName,
                        )
                    }.getOrElse { failure ->
                        add(ioFailure("KTCNFG021", "iOS pbxproj", pbxproj, failure))
                        null
                    }
                }
                if (result != null) {
                    when {
                        result.errors.isNotEmpty() -> add(
                            diagnostic(
                                "KTCNFG021",
                                KiteConfigDiagnosticSeverity.ERROR,
                                "iOS pbxproj target scope and values",
                                result.errors.joinToString("; "),
                                "Select every intended application target explicitly; malformed or ambiguous projects are never rewritten globally.",
                                location = pbxproj.path,
                            ),
                        )
                        result.text != text -> add(
                            diagnostic(
                                "KTCNFG021",
                                KiteConfigDiagnosticSeverity.ERROR,
                                "iOS pbxproj configuration drift",
                                "Configured setting(s) differ: ${result.changedSettings.sorted().joinToString()}.",
                                "Review the bounded dry-run diff, then run kiteRewriteXcode explicitly.",
                                location = pbxproj.path,
                            ),
                        )
                        else -> add(
                            diagnostic(
                                "KTCNFG021",
                                KiteConfigDiagnosticSeverity.PASS,
                                "iOS pbxproj target scope and values",
                                if (result.selectedTargets.isEmpty()) {
                                    "No target-scoped setting is configured; project-level metadata is aligned."
                                } else {
                                    "Selected application target(s) are structurally valid and aligned: " +
                                        result.selectedTargets.joinToString() + "."
                                },
                            ),
                        )
                    }
                }
            }
        }

        if (!context.propagateLogo) {
            add(diagnostic("KTCNFG022", KiteConfigDiagnosticSeverity.SKIPPED, "iOS app icon set", "Logo propagation is disabled."))
            return
        }
        val deploymentTarget = context.iosDeploymentTarget
        val deploymentFailure = runCatching {
            validateUniversalAppIconDeploymentTarget(
                requireNotNull(deploymentTarget) {
                    "ios.deploymentTarget is required for universal AppIcon propagation"
                },
            )
        }.exceptionOrNull()
        if (deploymentFailure == null) {
            add(
                diagnostic(
                    "KTCNFG023",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Universal AppIcon compatibility",
                    "iOS deployment target $deploymentTarget supports the Xcode 14+ single-size AppIcon catalog.",
                ),
            )
        } else {
            add(
                diagnostic(
                    "KTCNFG023",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Universal AppIcon compatibility",
                    diagnosticExceptionSummary(deploymentFailure),
                    "Set ios.deploymentTarget to 12.0 or newer and build the asset catalog with Xcode 14 or newer.",
                ),
            )
        }
        val icons = context.appiconsetDir
        if (icons == null) {
            add(diagnostic("KTCNFG022", KiteConfigDiagnosticSeverity.WARNING, "iOS app icon set", "The appiconset path is unset."))
        } else {
            val iconsExist = safeExists(context, icons, "KTCNFG022", "iOS app icon set") ?: return
            if (iconsExist && Files.isDirectory(icons.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                val projectRoot = context.projectRootDir
                if (projectRoot == null) {
                    add(diagnostic("KTCNFG022", KiteConfigDiagnosticSeverity.ERROR, "iOS app icon set", "Project root is unavailable; ownership cannot be verified."))
                    return
                }
                val problems = runCatching {
                    val identity = SyncIosLogoTask.catalogIdentity(projectRoot, icons)
                    val metadataBase = (icons.parentFile?.parentFile ?: icons.parentFile ?: icons)
                        .resolve(".kiteconfig/$identity")
                    OwnedOutputSafety.inspectInstalledFiles(
                        installationRoot = icons,
                        manifestFile = metadataBase.resolve("owned-files-v1"),
                        projectRoot = projectRoot,
                        owner = identity,
                        expectedRelativePaths = SyncIosLogoTask.OUTPUT_FILE_NAMES,
                        expectedInputFingerprint = context.iosLogoInputFingerprint,
                    )
                }.getOrElse { listOf(diagnosticExceptionSummary(it)) }
                if (problems.isEmpty()) {
                    add(diagnostic("KTCNFG022", KiteConfigDiagnosticSeverity.PASS, "iOS app icon set", "Every expected AppIcon output is present and checksum-owned."))
                } else {
                    add(
                        diagnostic(
                            "KTCNFG022",
                            KiteConfigDiagnosticSeverity.ERROR,
                            "iOS app icon set",
                            problems.joinToString("; "),
                            "Review and explicitly rerun kiteRewriteLogo; unowned files are never repaired automatically.",
                            location = icons.path,
                        ),
                    )
                }
            } else {
                add(
                diagnostic(
                    "KTCNFG022",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "iOS app icon set",
                    "No directory exists at ${context.shortPath(icons)}.",
                    "Configure ios { appIconDirectory } or run the explicitly requested logo installer.",
                ),
                )
            }
        }
    }

    /**
 * Render [file] the way a report should: short and relative to the project when
 * it lives there. An absolute path pushes the rest of the finding off screen.
 */
private fun KiteConfigDiagnosticContext.shortPath(file: java.io.File): String {
    val root = projectRootDir?.toPath() ?: return file.path
    return relativeDisplayPath(root, file.toPath())
}

private fun MutableList<KiteConfigDiagnostic>.diagnoseAndroidIcons(context: KiteConfigDiagnosticContext) {
        if (!context.propagateLogo) {
            add(diagnostic("KTCNFG030", KiteConfigDiagnosticSeverity.SKIPPED, "Android launcher icons", "Logo propagation is disabled."))
            return
        }
        val resourceDirectories = context.androidResDirs.ifEmpty { listOfNotNull(context.androidResDir) }
        if (resourceDirectories.isEmpty()) return
        resourceDirectories.forEach { res -> diagnoseAndroidIconDirectory(context, res) }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseAndroidIconDirectory(
        context: KiteConfigDiagnosticContext,
        res: File,
    ) {
        val resExists = safeExists(context, res, "KTCNFG030", "Android resources") ?: return
        if (!resExists) {
            add(diagnostic("KTCNFG030", KiteConfigDiagnosticSeverity.ERROR, "Android resources", "No Android resource directory exists at ${context.shortPath(res)}."))
            return
        }
        if (!Files.isDirectory(res.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            add(diagnostic("KTCNFG030", KiteConfigDiagnosticSeverity.ERROR, "Android resources", "${context.shortPath(res)} is not a directory."))
            return
        }
        val collisions = runCatching { SyncAndroidLogoTask.collidingTemplateIcons(res) }.getOrElse { failure ->
            add(ioFailure("KTCNFG031", "Android launcher icons", res, failure))
            return
        }
        val projectRoot = context.projectRootDir
        if (projectRoot == null) {
            add(diagnostic("KTCNFG031", KiteConfigDiagnosticSeverity.ERROR, "Android launcher icons", "Project root is unavailable; ownership cannot be verified."))
            return
        }
        val effectiveExpected = if (context.androidEmitMonochrome) {
            SyncAndroidLogoTask.OUTPUT_RELATIVE_PATHS
        } else {
            SyncAndroidLogoTask.OUTPUT_RELATIVE_PATHS.filterNot { it.startsWith("mipmap-anydpi-v33/") }
        }
        val ownershipProblems = OwnedOutputSafety.inspectInstalledFiles(
            installationRoot = res,
            manifestFile = res.parentFile.resolve(".kiteconfig/android-logo-owned-files-v1"),
            projectRoot = projectRoot,
            owner = "android-logo",
            expectedRelativePaths = effectiveExpected,
            expectedInputFingerprint = context.androidLogoInputFingerprint,
        )
        if (ownershipProblems.isNotEmpty()) {
            add(
                diagnostic(
                    "KTCNFG031",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Android launcher icons",
                    ownershipProblems.joinToString("; "),
                    "Review and explicitly rerun kiteRewriteLogo; modified or unowned files are never overwritten automatically.",
                    location = res.path,
                ),
            )
        } else if (collisions.isEmpty()) {
            add(diagnostic("KTCNFG031", KiteConfigDiagnosticSeverity.PASS, "Android launcher icons", "Every expected launcher output is present and checksum-owned; no collisions were found."))
        } else {
            add(
                diagnostic(
                    "KTCNFG031",
                    KiteConfigDiagnosticSeverity.WARNING,
                    "Android launcher icons",
                    "${collisions.size} colliding template icon(s): ${collisions.joinToString { it.relativeToOrSelf(res).path }}.",
                    "Review, then run the explicit legacy-logo migration task; it backs up every removed file.",
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseLocales(context: KiteConfigDiagnosticContext) {
        if (!context.propagateLocaleList && !context.filterAndroidResources) {
            add(diagnostic("KTCNFG040", KiteConfigDiagnosticSeverity.SKIPPED, "Locales", "Locale propagation is disabled."))
            return
        }
        val canonical = runCatching { canonicalizeLocales(context.locales) }.getOrElse { failure ->
            add(
                diagnostic(
                    "KTCNFG040",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Locales",
                    diagnosticExceptionSummary(failure),
                    "Use canonical BCP-47 language tags such as en, en-US, or zh-Hant-TW.",
                ),
            )
            return
        }
        when {
            context.filterAndroidResources && canonical.isEmpty() -> add(
                diagnostic(
                    "KTCNFG040",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Locales",
                    "Android resource filtering is enabled, but the canonical locale allow-list is empty.",
                    "Configure locales explicitly or add a supported values-<locale> resource directory.",
                ),
            )
            context.locales.isEmpty() -> add(diagnostic("KTCNFG040", KiteConfigDiagnosticSeverity.PASS, "Locales", "No explicit locale allow-list is configured."))
            canonical == context.locales -> add(diagnostic("KTCNFG040", KiteConfigDiagnosticSeverity.PASS, "Locales", "All locale tags are canonical and unique."))
            else -> add(
                diagnostic(
                    "KTCNFG040",
                    KiteConfigDiagnosticSeverity.WARNING,
                    "Locales",
                    "Configured ${context.locales.joinToString()} canonicalizes to ${canonical.joinToString()}.",
                    "Store canonical BCP-47 tags in the kiteConfig { } DSL.",
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseVersion(context: KiteConfigDiagnosticContext) {
        if (!context.propagateVersion || context.hasVersionCodeOverride) {
            add(diagnostic("KTCNFG050", KiteConfigDiagnosticSeverity.SKIPPED, "Android versionCode", "Version propagation is disabled or an explicit override is configured."))
            return
        }
        val version = context.versionName
        if (version == null) {
            add(diagnostic("KTCNFG050", KiteConfigDiagnosticSeverity.SKIPPED, "Android versionCode", "version is unset; identity values are optional until configured."))
            return
        }
        val resolved = context.resolvedVersionCode
        if (resolved != null) {
            add(
                diagnostic(
                    "KTCNFG050",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Android versionCode",
                    "$version derives monotonically to $resolved.",
                ),
            )
        } else {
            add(
                diagnostic(
                    "KTCNFG050",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Android versionCode",
                    "No Android versionCode could be derived from version \"$version\".",
                    "Use three numeric segments (x.y.z), supply version(\"x\") { formula { } }, " +
                        "or set android { versionCode }.",
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnoseKgp(context: KiteConfigDiagnosticContext) {
        when {
            !context.kgpRequired -> add(diagnostic("KTCNFG060", KiteConfigDiagnosticSeverity.SKIPPED, "Kotlin Gradle plugin", "No enabled feature requires typed KGP access."))
            context.kgpOnClasspath -> add(diagnostic("KTCNFG060", KiteConfigDiagnosticSeverity.PASS, "Kotlin Gradle plugin", "KGP types are visible to kiteconfig."))
            else -> add(
                diagnostic(
                    "KTCNFG060",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Kotlin Gradle plugin",
                    "KGP types are not visible to kiteconfig's classloader, but an enabled feature requires them.",
                    "Declare kotlin(\"multiplatform\") apply false in the root plugins block.",
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.diagnosePluginCompatibility(context: KiteConfigDiagnosticContext) {
        when {
            !context.agpRequired -> add(
                diagnostic(
                    "KTCNFG061",
                    KiteConfigDiagnosticSeverity.SKIPPED,
                    "Android Gradle plugin compatibility",
                    "No detected Android project needs an enabled typed AGP integration.",
                ),
            )

            !context.agpOnClasspath -> add(
                diagnostic(
                    "KTCNFG061",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Android Gradle plugin compatibility",
                    "AGP types are not visible to kiteconfig, so the active version cannot be verified.",
                    "Declare the Android plugin version with apply false in the root plugins block.",
                ),
            )

            context.activeAgpVersion == null -> add(
                diagnostic(
                    "KTCNFG061",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Android Gradle plugin compatibility",
                    "Could not determine the active Android Gradle plugin version.",
                    "Use an AGP release in the supported range 8.5.2 through 9.3.x.",
                    expected = "8.5.2..9.3.x",
                ),
            )

            !isSupportedAgpVersion(context.activeAgpVersion) -> add(
                diagnostic(
                    "KTCNFG061",
                    if (context.versionGuardsIgnored) KiteConfigDiagnosticSeverity.WARNING else KiteConfigDiagnosticSeverity.ERROR,
                    "Android Gradle plugin compatibility",
                    "Active Android Gradle plugin ${context.activeAgpVersion} is unsupported." +
                        if (context.versionGuardsIgnored) " ignoreVersionGuards keeps it active." else "",
                    "Use an AGP release in the supported range 8.5.2 through 9.3.x.",
                    expected = "8.5.2..9.3.x",
                    actual = context.activeAgpVersion,
                ),
            )

            else -> add(
                diagnostic(
                    "KTCNFG061",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Android Gradle plugin compatibility",
                    "Active Android Gradle plugin ${context.activeAgpVersion} is supported.",
                ),
            )
        }

        when {
            !context.kgpRequired -> add(
                diagnostic(
                    "KTCNFG062",
                    KiteConfigDiagnosticSeverity.SKIPPED,
                    "Kotlin Gradle plugin compatibility",
                    "No enabled feature requires typed KGP integration.",
                ),
            )

            !context.kgpOnClasspath -> add(
                diagnostic(
                    "KTCNFG062",
                    KiteConfigDiagnosticSeverity.SKIPPED,
                    "Kotlin Gradle plugin compatibility",
                    "The active KGP version cannot be inspected until the KTCNFG060 classloader error is fixed.",
                ),
            )

            context.activeKgpVersion == null -> add(
                diagnostic(
                    "KTCNFG062",
                    KiteConfigDiagnosticSeverity.ERROR,
                    "Kotlin Gradle plugin compatibility",
                    "Could not determine the active Kotlin Gradle plugin version.",
                    "Use a KGP 2.4.x release.",
                    expected = "2.4.x",
                ),
            )

            !isSupportedKgpVersion(context.activeKgpVersion) -> add(
                diagnostic(
                    "KTCNFG062",
                    if (context.versionGuardsIgnored) KiteConfigDiagnosticSeverity.WARNING else KiteConfigDiagnosticSeverity.ERROR,
                    "Kotlin Gradle plugin compatibility",
                    "Active Kotlin Gradle plugin ${context.activeKgpVersion} is unsupported." +
                        if (context.versionGuardsIgnored) " ignoreVersionGuards keeps it active." else "",
                    "Use a KGP 2.4.x release.",
                    expected = "2.4.x",
                    actual = context.activeKgpVersion,
                ),
            )

            else -> add(
                diagnostic(
                    "KTCNFG062",
                    KiteConfigDiagnosticSeverity.PASS,
                    "Kotlin Gradle plugin compatibility",
                    "Active Kotlin Gradle plugin ${context.activeKgpVersion} is supported.",
                ),
            )
        }
    }

    private fun MutableList<KiteConfigDiagnostic>.safeExists(
        context: KiteConfigDiagnosticContext,
        file: File,
        id: String,
        title: String,
    ): Boolean? =
        runCatching {
            val projectRoot = requireNotNull(context.projectRootDir) {
                "root project directory is unavailable; path containment cannot be verified"
            }
            OwnedOutputSafety.projectPathExists(file, projectRoot, title)
        }.fold(
            onSuccess = { it },
            onFailure = { failure -> add(ioFailure(id, title, file, failure)); null },
        )

    private fun MutableList<KiteConfigDiagnostic>.safeRead(
        context: KiteConfigDiagnosticContext,
        file: File,
        id: String,
        title: String,
    ): String? =
        runCatching {
            val projectRoot = requireNotNull(context.projectRootDir) {
                "root project directory is unavailable; path containment cannot be verified"
            }
            val bytes = OwnedOutputSafety.readProjectFileBounded(
                file,
                projectRoot,
                MAX_DIAGNOSTIC_TEXT_BYTES,
                title,
            )
            String(bytes, StandardCharsets.UTF_8).also { text ->
                require(text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
                    "configured file is not valid UTF-8"
                }
            }
        }.getOrElse { failure ->
            add(ioFailure(id, title, file, failure))
            null
        }
}

private fun validIosTargetNames(names: List<String>): Boolean =
    names.distinct().size == names.size && names.none { it.isBlank() || it.any(Char::isISOControl) }

private fun renderSelector(value: String): String = buildString {
    append('"')
    val abbreviated = if (value.length > 256) value.take(256) + "…" else value
    abbreviated.forEach { character ->
        when {
            character == '"' -> append("\\\"")
            character == '\\' -> append("\\\\")
            character.isISOControl() -> append("\\u").append(character.code.toString(16).padStart(4, '0'))
            else -> append(character)
        }
    }
    append('"')
}

internal object KiteConfigDiagnosticReports {
    fun json(findings: List<KiteConfigDiagnostic>): String = buildString {
        append("{\n  \"schemaVersion\": 1,\n  \"summary\": {")
        KiteConfigDiagnosticSeverity.entries.forEachIndexed { index, severity ->
            if (index > 0) append(',')
            append("\n    \"").append(severity.name).append("\": ")
                .append(findings.count { it.severity == severity })
        }
        append("\n  },\n  \"findings\": [")
        findings.forEachIndexed { index, finding ->
            if (index > 0) append(',')
            append("\n    {\"id\": ").append(jsonString(finding.id))
            append(", \"severity\": ").append(jsonString(finding.severity.name))
            append(", \"title\": ").append(jsonString(finding.title))
            append(", \"detail\": ").append(jsonString(finding.detail))
            finding.remediation?.let { append(", \"remediation\": ").append(jsonString(it)) }
            finding.location?.let { append(", \"location\": ").append(jsonString(it)) }
            finding.expected?.let { append(", \"expected\": ").append(jsonString(it)) }
            finding.actual?.let { append(", \"actual\": ").append(jsonString(it)) }
            append('}')
        }
        append("\n  ]\n}\n")
    }

    fun sarif(findings: List<KiteConfigDiagnostic>, projectRoot: File? = null): String = buildString {
        val rules = findings.distinctBy { it.id }.sortedBy { it.id }
        append("{\n  \"version\": \"2.1.0\",\n  \"\$schema\": ")
        append(jsonString("https://json.schemastore.org/sarif-2.1.0.json"))
        append(",\n  \"runs\": [{\n    \"tool\": {\"driver\": {\"name\": \"kiteconfig\", \"rules\": [")
        rules.forEachIndexed { index, rule ->
            if (index > 0) append(',')
            append("\n      {\"id\": ").append(jsonString(rule.id))
            append(", \"shortDescription\": {\"text\": ").append(jsonString(rule.title)).append('}')
            append('}')
        }
        append("\n    ]}},\n    \"results\": [")
        findings.filter { it.severity == KiteConfigDiagnosticSeverity.WARNING || it.severity == KiteConfigDiagnosticSeverity.ERROR }
            .forEachIndexed { index, finding ->
                if (index > 0) append(',')
                val level = if (finding.severity == KiteConfigDiagnosticSeverity.ERROR) "error" else "warning"
                val message = finding.remediation?.let { "${finding.detail} Fix: $it" } ?: finding.detail
                append("\n      {\"ruleId\": ").append(jsonString(finding.id))
                append(", \"level\": ").append(jsonString(level))
                append(", \"message\": {\"text\": ").append(jsonString(message)).append('}')
                finding.location?.let { location ->
                    append(", \"locations\": [{\"physicalLocation\": {\"artifactLocation\": {\"uri\": ")
                    append(jsonString(sarifArtifactUri(location, projectRoot))).append("}}}]")
                }
                append('}')
            }
        append("\n    ]\n  }]\n}\n")
    }
}

private fun sarifArtifactUri(location: String, projectRoot: File?): String {
    val slashNormalized = location.replace('\\', '/')
    return runCatching {
        if (Regex("^[A-Za-z]:/").containsMatchIn(slashNormalized)) {
            return@runCatching URI("file", "", "/$slashNormalized", null).toASCIIString()
        }
        val configured = java.nio.file.Path.of(location)
        val root = projectRoot?.toPath()?.toAbsolutePath()?.normalize()
        val resolved = when {
            configured.isAbsolute -> configured.toAbsolutePath().normalize()
            root != null -> root.resolve(configured).normalize()
            else -> null
        }
        when {
            resolved != null && root != null && resolved.startsWith(root) ->
                URI(null, null, root.relativize(resolved).joinToString("/") { it.toString() }, null)
                    .toASCIIString()

            resolved != null && resolved.isAbsolute -> resolved.toUri().toASCIIString()
            else -> URI(null, null, slashNormalized, null).toASCIIString()
        }
    }.getOrElse {
        URI(null, null, diagnosticSafeText(slashNormalized), null).toASCIIString()
    }
}

private fun diagnostic(
    id: String,
    severity: KiteConfigDiagnosticSeverity,
    title: String,
    detail: String,
    remediation: String? = null,
    location: String? = null,
    expected: String? = null,
    actual: String? = null,
) = KiteConfigDiagnostic(id, severity, title, detail, remediation, location, expected, actual)

private fun ioFailure(id: String, title: String, file: File, failure: Throwable) = diagnostic(
    id,
    KiteConfigDiagnosticSeverity.ERROR,
    title,
    "Could not inspect ${file.path}: ${diagnosticExceptionSummary(failure)}",
    "Restore read access and verify that the configured path points to the expected file type.",
    location = file.path,
)

private const val ANDROID_XML_NAMESPACE = "http://schemas.android.com/apk/res/android"
private const val ANDROID_LAUNCHER_ICON_REFERENCE = "@mipmap/ic_launcher"
private const val ANDROID_ROUND_ICON_REFERENCE = "@mipmap/ic_launcher_round"
private const val MAX_DIAGNOSTIC_TEXT_BYTES = 64L * 1024L * 1024L

private data class AndroidApplicationAttributes(
    val label: String,
    val icon: String,
    val roundIcon: String,
)

private fun androidApplicationAttributes(text: String): AndroidApplicationAttributes {
    val document = parseXmlSecurely(text, "Android manifest")
    val applications = document.getElementsByTagName("application")
    require(applications.length == 1) { "Android manifest must contain exactly one <application> element" }
    val application = applications.item(0) as Element
    fun attribute(name: String): String = application.getAttributeNS(ANDROID_XML_NAMESPACE, name)
        .ifBlank { application.getAttribute("android:$name") }
    return AndroidApplicationAttributes(
        label = attribute("label"),
        icon = attribute("icon"),
        roundIcon = attribute("roundIcon"),
    )
}

private fun parseXmlSecurely(text: String, label: String) = DocumentBuilderFactory.newInstance().apply {
    require(!text.contains("<!DOCTYPE")) { "$label must not contain a DOCTYPE declaration" }
    isNamespaceAware = true
    isValidating = false
    isXIncludeAware = false
    isExpandEntityReferences = false
    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
}.newDocumentBuilder().apply {
    setEntityResolver { _, _ -> InputSource(StringReader("")) }
    setErrorHandler(object : DefaultHandler() {
        override fun warning(exception: SAXParseException) = throw exception
        override fun error(exception: SAXParseException) = throw exception
        override fun fatalError(exception: SAXParseException) = throw exception
    })
}.parse(InputSource(StringReader(text))).also {
    require(it.documentElement != null) { "$label has no document element" }
}

/** Deepest actionable cause, bounded and escaped for reports or console output. */
internal fun diagnosticExceptionSummary(failure: Throwable): String {
    val root = generateSequence(failure) { it.cause }.last()
    return buildString {
        append(root::class.simpleName ?: "failure")
        root.message?.takeIf(String::isNotBlank)?.let { append(": ").append(diagnosticSafeText(it)) }
    }
}

/**
 * Render a file location for reports and error messages. Locations under the
 * project root are shown project-relative with '/' separators on every OS so
 * plugin output stays stable across platforms; anything outside the root (or a
 * path the filesystem cannot relativize, e.g. another Windows drive) falls back
 * to the native path.
 */
internal fun displayProjectPath(root: java.io.File, file: java.io.File): String = runCatching {
    val rootPath = root.toPath().toAbsolutePath().normalize()
    val target = file.toPath().toAbsolutePath().normalize()
    if (target.startsWith(rootPath)) {
        rootPath.relativize(target).joinToString("/").ifEmpty { "." }
    } else {
        target.toString()
    }
}.getOrElse { file.path }

/** Bound and escape uncontrolled source/provider text before console or report use. */
internal fun diagnosticSafeText(value: String, maximumChars: Int = 2_048): String {
    val abbreviated = if (value.length > maximumChars) value.take(maximumChars) + "…" else value
    return buildString(abbreviated.length) {
        abbreviated.forEach { character ->
            when (character) {
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (
                    character.isISOControl() || character.code in 0xD800..0xDFFF ||
                    character == '\u2028' || character == '\u2029'
                ) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
}

private fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (
                character.code < 0x20 || character.code in 0xD800..0xDFFF ||
                character == '\u2028' || character == '\u2029'
            ) {
                append("\\u").append(character.code.toString(16).padStart(4, '0').lowercase(Locale.ROOT))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
