package io.github.yuroyami.kmpssot

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

/** Stable severity used by human- and machine-readable kmp-ssot diagnostic reports. */
internal enum class KmpSsotDiagnosticSeverity {
    PASS,
    SKIPPED,
    WARNING,
    ERROR,
}

/**
 * One internal diagnostic finding. [id] is a stable report contract intended for
 * CI suppressions and IDE integrations; it must not be derived from prose.
 */
internal data class KmpSsotDiagnostic(
    val id: String,
    val severity: KmpSsotDiagnosticSeverity,
    val title: String,
    val detail: String,
    val remediation: String? = null,
    val location: String? = null,
    val expected: String? = null,
    val actual: String? = null,
)

/** Immutable input to the read-only diagnostic engine. */
internal data class KmpSsotDiagnosticContext(
    val propagateAppName: Boolean = true,
    val appName: String? = null,
    val propagateBundleId: Boolean = true,
    val iosBundleId: String? = null,
    val propagateVersion: Boolean = true,
    val versionName: String? = null,
    val hasVersionCodeOverride: Boolean = false,
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
)

/** Pure orchestration around guarded filesystem reads and fail-closed parsers. */
internal object KmpSsotDiagnosticEngine {

    fun evaluate(context: KmpSsotDiagnosticContext): List<KmpSsotDiagnostic> = buildList {
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
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnoseAndroidApplicationSelection(
        context: KmpSsotDiagnosticContext,
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
                        "KMPS070",
                        KmpSsotDiagnosticSeverity.ERROR,
                        "Android application selection",
                        "Multiple Android application projects are eligible for active app-scoped values: " +
                            "${detected.joinToString()}; no explicit androidApplicationProjects selector is configured.",
                        "Select every intended application explicitly with unique absolute Gradle project paths.",
                    ),
                )
            } else {
                add(
                    diagnostic(
                        "KMPS070",
                        KmpSsotDiagnosticSeverity.SKIPPED,
                        "Android application selection",
                        "No explicit androidApplicationProjects selector is configured; " +
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
            runCatching { validateGradleProjectPath(selector, "androidApplicationProjects") }.isFailure
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
                    "KMPS070",
                    KmpSsotDiagnosticSeverity.PASS,
                    "Android application selection",
                    "Every explicit Android application selector resolves exactly: ${selected.joinToString()}.",
                ),
            )
        } else {
            add(
                diagnostic(
                    "KMPS070",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Android application selection",
                    problems.joinToString("; "),
                    "Use unique absolute Gradle paths for projects that apply com.android.application (for example :app).",
                ),
            )
        }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnoseIosTargetSelection(context: KmpSsotDiagnosticContext) {
        if (!context.syncIos) {
            add(
                diagnostic(
                    "KMPS071",
                    KmpSsotDiagnosticSeverity.SKIPPED,
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
                    "KMPS071",
                    KmpSsotDiagnosticSeverity.PASS,
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
                    "KMPS071",
                    KmpSsotDiagnosticSeverity.PASS,
                    "iOS target selection",
                    "Every explicit iOS target name is unique and non-blank: ${selected.joinToString()}.",
                ),
            )
        } else {
            add(
                diagnostic(
                    "KMPS071",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "iOS target selection",
                    problems.joinToString("; "),
                    "Use unique, non-blank Xcode application target names without control characters.",
                ),
            )
        }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnoseIosAppName(context: KmpSsotDiagnosticContext) {
        val name = context.appName
        when {
            !context.syncIos || !context.propagateAppName || name == null -> add(
                diagnostic(
                    "KMPS012",
                    KmpSsotDiagnosticSeverity.SKIPPED,
                    "Apple bundle name",
                    "Apple app-name propagation is disabled or appName is unset.",
                ),
            )
            name.length >= 16 -> add(
                diagnostic(
                    "KMPS012",
                    KmpSsotDiagnosticSeverity.WARNING,
                    "Apple bundle name",
                    "appName has ${name.length} characters; Apple recommends CFBundleName contain fewer than 16.",
                    "Use a shorter cross-platform appName, or disable app-name propagation and configure Apple product/display names per target.",
                ),
            )
            else -> add(
                diagnostic(
                    "KMPS012",
                    KmpSsotDiagnosticSeverity.PASS,
                    "Apple bundle name",
                    "appName fits Apple's recommended CFBundleName length.",
                ),
            )
        }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnoseAndroidManifest(context: KmpSsotDiagnosticContext) {
        if (!context.propagateAppName || context.appName == null) {
            add(diagnostic("KMPS001", KmpSsotDiagnosticSeverity.SKIPPED, "Android manifest", "App-name propagation is disabled or appName is unset."))
            return
        }
        val manifests = context.androidManifestFiles.ifEmpty { listOfNotNull(context.manifestFile) }
        if (manifests.isEmpty()) return
        manifests.forEach { manifest -> diagnoseAndroidManifestFile(context, manifest) }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnoseAndroidManifestFile(
        context: KmpSsotDiagnosticContext,
        manifest: File,
    ) {
        val manifestExists = safeExists(context, manifest, "KMPS001", "Android manifest") ?: return
        if (!manifestExists) {
            add(
                diagnostic(
                    "KMPS001",
                    KmpSsotDiagnosticSeverity.WARNING,
                    "Android manifest",
                    "Manifest not found at ${manifest.path}; the appName placeholder cannot be verified.",
                    "Point the Android application selection at a project with src/main/AndroidManifest.xml.",
                ),
            )
            return
        }
        val text = safeRead(context, manifest, "KMPS002", "Android manifest") ?: return
        val label = runCatching { androidApplicationAttributes(text).label }.getOrElse { failure ->
            add(ioFailure("KMPS002", "Android manifest", manifest, failure))
            return
        }
        if (label == "\${appName}") {
            add(diagnostic("KMPS002", KmpSsotDiagnosticSeverity.PASS, "Android manifest", "android:label can resolve the \${appName} manifest placeholder."))
        } else {
            add(
                diagnostic(
                    "KMPS002",
                    KmpSsotDiagnosticSeverity.ERROR,
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

    private fun MutableList<KmpSsotDiagnostic>.diagnoseAndroidLauncherIconReferences(
        context: KmpSsotDiagnosticContext,
    ) {
        if (!context.propagateLogo) {
            add(
                diagnostic(
                    "KMPS003",
                    KmpSsotDiagnosticSeverity.SKIPPED,
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
                    "KMPS003",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Android launcher icon references",
                    "No selected Android application manifest is available, so installed launcher resources cannot be proven reachable.",
                    "Select one Android application and point its main manifest at @mipmap/ic_launcher.",
                ),
            )
            return
        }
        manifests.forEach { manifest -> diagnoseAndroidLauncherIconManifest(context, manifest) }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnoseAndroidLauncherIconManifest(
        context: KmpSsotDiagnosticContext,
        manifest: File,
    ) {
        val exists = safeExists(context, manifest, "KMPS003", "Android launcher icon references") ?: return
        if (!exists) {
            add(
                diagnostic(
                    "KMPS003",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Android launcher icon references",
                    "Manifest not found at ${manifest.path}; installed launcher resources cannot be consumed.",
                    "Create/select the application manifest and reference @mipmap/ic_launcher.",
                    location = manifest.path,
                ),
            )
            return
        }
        val text = safeRead(context, manifest, "KMPS003", "Android launcher icon references") ?: return
        val attributes = runCatching { androidApplicationAttributes(text) }.getOrElse { failure ->
            add(ioFailure("KMPS003", "Android launcher icon references", manifest, failure))
            return
        }
        val icon = attributes.icon
        val roundIcon = attributes.roundIcon
        val wrongIcon = icon != ANDROID_LAUNCHER_ICON_REFERENCE
        val wrongRoundIcon = roundIcon.isNotEmpty() && roundIcon != ANDROID_ROUND_ICON_REFERENCE
        when {
            wrongIcon || wrongRoundIcon -> add(
                diagnostic(
                    "KMPS003",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Android launcher icon references",
                    "The selected <application> does not consume the launcher resources installed by kmp-ssot.",
                    "Set android:icon=\"$ANDROID_LAUNCHER_ICON_REFERENCE\" and, when roundIcon is present, " +
                        "android:roundIcon=\"$ANDROID_ROUND_ICON_REFERENCE\".",
                    location = manifest.path,
                    expected = "$ANDROID_LAUNCHER_ICON_REFERENCE; $ANDROID_ROUND_ICON_REFERENCE",
                    actual = diagnosticSafeText("${icon.ifEmpty { "<unset>" }}; ${roundIcon.ifEmpty { "<unset>" }}"),
                ),
            )
            roundIcon.isEmpty() -> add(
                diagnostic(
                    "KMPS003",
                    KmpSsotDiagnosticSeverity.WARNING,
                    "Android launcher icon references",
                    "android:icon consumes the generated launcher icon, but android:roundIcon is unset.",
                    "Set android:roundIcon=\"$ANDROID_ROUND_ICON_REFERENCE\" to consume the generated round asset on launchers that support it.",
                    location = manifest.path,
                ),
            )
            else -> add(
                diagnostic(
                    "KMPS003",
                    KmpSsotDiagnosticSeverity.PASS,
                    "Android launcher icon references",
                    "The selected application manifest consumes both generated launcher icon resources.",
                    location = manifest.path,
                ),
            )
        }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnoseInfoPlist(context: KmpSsotDiagnosticContext) {
        if (!context.syncIos || !context.sanitizeIosProject) {
            add(diagnostic("KMPS010", KmpSsotDiagnosticSeverity.SKIPPED, "iOS Info.plist", "Source-plist sanitization is disabled."))
            return
        }
        val plist = context.infoPlistFile
        if (plist == null) return
        val plistExists = safeExists(context, plist, "KMPS010", "iOS Info.plist") ?: return
        if (!plistExists) {
            add(
                diagnostic(
                    "KMPS010",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "iOS Info.plist",
                    "Configured source Info.plist does not exist at ${plist.path}.",
                    "Correct iosInfoPlistFile or disable sanitizeIosProject for a generated plist.",
                    location = plist.path,
                ),
            )
            return
        }
        val text = safeRead(context, plist, "KMPS011", "iOS Info.plist") ?: return
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
            add(diagnostic("KMPS011", KmpSsotDiagnosticSeverity.SKIPPED, "iOS Info.plist", "No source-plist key is configured for verification."))
            return
        }
        val result = sanitizeInfoPlist(text, stringEntries, boolEntries, context.plistConflictPolicy)
        when {
            result.errors.isNotEmpty() -> add(
                diagnostic(
                    "KMPS011",
                    KmpSsotDiagnosticSeverity.ERROR,
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
                        "KMPS011",
                        KmpSsotDiagnosticSeverity.ERROR,
                        "iOS Info.plist",
                        "The source plist is not synchronized; migration would ${changes.joinToString("; ")}.",
                        "Review and run the explicit source-plist migration.",
                        location = plist.path,
                    ),
                )
            }

            result.warnings.isNotEmpty() -> add(
                diagnostic(
                    "KMPS011",
                    KmpSsotDiagnosticSeverity.WARNING,
                    "iOS Info.plist",
                    result.warnings.joinToString("; "),
                    "The configured KEEP policy intentionally preserves this drift; align the value or select REPLACE to resolve it.",
                    location = plist.path,
                ),
            )

            else -> add(
                diagnostic(
                    "KMPS011",
                    KmpSsotDiagnosticSeverity.PASS,
                    "iOS Info.plist",
                    "Every configured source-plist reference is structurally correct.",
                ),
            )
        }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnosePbxproj(context: KmpSsotDiagnosticContext) {
        if (!context.syncIos) {
            add(diagnostic("KMPS020", KmpSsotDiagnosticSeverity.SKIPPED, "iOS project", "iOS source synchronization is disabled."))
            return
        }
        val appIconName = when {
            !context.propagateLogo -> null
            context.appiconsetDir == null -> {
                add(
                    diagnostic(
                        "KMPS024",
                        KmpSsotDiagnosticSeverity.ERROR,
                        "Xcode AppIcon selection",
                        "Logo propagation is enabled, but iosAppIconDirectory is unset.",
                        "Configure a named .appiconset directory and select that catalog in every application configuration.",
                    ),
                )
                null
            }
            else -> runCatching { iosAppIconCatalogName(context.appiconsetDir.name) }.fold(
                onSuccess = { name ->
                    add(
                        diagnostic(
                            "KMPS024",
                            KmpSsotDiagnosticSeverity.PASS,
                            "Xcode AppIcon selection",
                            "The configured catalog maps to ASSETCATALOG_COMPILER_APPICON_NAME=$name; selected-target alignment is checked with KMPS021.",
                        ),
                    )
                    name
                },
                onFailure = { failure ->
                    add(
                        diagnostic(
                            "KMPS024",
                            KmpSsotDiagnosticSeverity.ERROR,
                            "Xcode AppIcon selection",
                            diagnosticExceptionSummary(failure),
                            "Point iosAppIconDirectory at a named .appiconset directory.",
                        ),
                    )
                    null
                },
            )
        }
        val pbxproj = context.pbxprojFile
        if (pbxproj != null && validIosTargetNames(context.iosTargetNames)) {
            val pbxprojExists = safeExists(context, pbxproj, "KMPS020", "iOS pbxproj")
            if (pbxprojExists == false) {
                add(
                    diagnostic(
                        "KMPS020",
                        KmpSsotDiagnosticSeverity.ERROR,
                        "iOS pbxproj",
                        "Project file not found at ${pbxproj.path}.",
                        "Correct iosProjectPath or disable syncIos.",
                    ),
                )
            } else if (pbxprojExists == true) {
                val text = safeRead(context, pbxproj, "KMPS021", "iOS pbxproj")
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
                        add(ioFailure("KMPS021", "iOS pbxproj", pbxproj, failure))
                        null
                    }
                }
                if (result != null) {
                    when {
                        result.errors.isNotEmpty() -> add(
                            diagnostic(
                                "KMPS021",
                                KmpSsotDiagnosticSeverity.ERROR,
                                "iOS pbxproj target scope and values",
                                result.errors.joinToString("; "),
                                "Select every intended application target explicitly; malformed or ambiguous projects are never rewritten globally.",
                                location = pbxproj.path,
                            ),
                        )
                        result.text != text -> add(
                            diagnostic(
                                "KMPS021",
                                KmpSsotDiagnosticSeverity.ERROR,
                                "iOS pbxproj configuration drift",
                                "Configured setting(s) differ: ${result.changedSettings.sorted().joinToString()}.",
                                "Review the bounded dry-run diff, then run kmpSsotSyncIosConfig explicitly.",
                                location = pbxproj.path,
                            ),
                        )
                        else -> add(
                            diagnostic(
                                "KMPS021",
                                KmpSsotDiagnosticSeverity.PASS,
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
            add(diagnostic("KMPS022", KmpSsotDiagnosticSeverity.SKIPPED, "iOS app icon set", "Logo propagation is disabled."))
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
                    "KMPS023",
                    KmpSsotDiagnosticSeverity.PASS,
                    "Universal AppIcon compatibility",
                    "iOS deployment target $deploymentTarget supports the Xcode 14+ single-size AppIcon catalog.",
                ),
            )
        } else {
            add(
                diagnostic(
                    "KMPS023",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Universal AppIcon compatibility",
                    diagnosticExceptionSummary(deploymentFailure),
                    "Set ios.deploymentTarget to 12.0 or newer and build the asset catalog with Xcode 14 or newer.",
                ),
            )
        }
        val icons = context.appiconsetDir
        if (icons == null) {
            add(diagnostic("KMPS022", KmpSsotDiagnosticSeverity.WARNING, "iOS app icon set", "The appiconset path is unset."))
        } else {
            val iconsExist = safeExists(context, icons, "KMPS022", "iOS app icon set") ?: return
            if (iconsExist && Files.isDirectory(icons.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                val projectRoot = context.projectRootDir
                if (projectRoot == null) {
                    add(diagnostic("KMPS022", KmpSsotDiagnosticSeverity.ERROR, "iOS app icon set", "Project root is unavailable; ownership cannot be verified."))
                    return
                }
                val problems = runCatching {
                    val identity = SyncIosLogoTask.catalogIdentity(projectRoot, icons)
                    val metadataBase = (icons.parentFile?.parentFile ?: icons.parentFile ?: icons)
                        .resolve(".kmpssot/$identity")
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
                    add(diagnostic("KMPS022", KmpSsotDiagnosticSeverity.PASS, "iOS app icon set", "Every expected AppIcon output is present and checksum-owned."))
                } else {
                    add(
                        diagnostic(
                            "KMPS022",
                            KmpSsotDiagnosticSeverity.ERROR,
                            "iOS app icon set",
                            problems.joinToString("; "),
                            "Review and explicitly rerun kmpSsotSyncIosLogo; unowned files are never repaired automatically.",
                            location = icons.path,
                        ),
                    )
                }
            } else {
                add(
                diagnostic(
                    "KMPS022",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "iOS app icon set",
                    "No directory exists at ${icons.path}.",
                    "Configure iosAppiconsetPath or run the explicitly requested logo installer.",
                ),
                )
            }
        }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnoseAndroidIcons(context: KmpSsotDiagnosticContext) {
        if (!context.propagateLogo) {
            add(diagnostic("KMPS030", KmpSsotDiagnosticSeverity.SKIPPED, "Android launcher icons", "Logo propagation is disabled."))
            return
        }
        val resourceDirectories = context.androidResDirs.ifEmpty { listOfNotNull(context.androidResDir) }
        if (resourceDirectories.isEmpty()) return
        resourceDirectories.forEach { res -> diagnoseAndroidIconDirectory(context, res) }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnoseAndroidIconDirectory(
        context: KmpSsotDiagnosticContext,
        res: File,
    ) {
        val resExists = safeExists(context, res, "KMPS030", "Android resources") ?: return
        if (!resExists) {
            add(diagnostic("KMPS030", KmpSsotDiagnosticSeverity.ERROR, "Android resources", "No Android resource directory exists at ${res.path}."))
            return
        }
        if (!Files.isDirectory(res.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            add(diagnostic("KMPS030", KmpSsotDiagnosticSeverity.ERROR, "Android resources", "${res.path} is not a directory."))
            return
        }
        val collisions = runCatching { SyncAndroidLogoTask.collidingTemplateIcons(res) }.getOrElse { failure ->
            add(ioFailure("KMPS031", "Android launcher icons", res, failure))
            return
        }
        val projectRoot = context.projectRootDir
        if (projectRoot == null) {
            add(diagnostic("KMPS031", KmpSsotDiagnosticSeverity.ERROR, "Android launcher icons", "Project root is unavailable; ownership cannot be verified."))
            return
        }
        val effectiveExpected = if (context.androidEmitMonochrome) {
            SyncAndroidLogoTask.OUTPUT_RELATIVE_PATHS
        } else {
            SyncAndroidLogoTask.OUTPUT_RELATIVE_PATHS.filterNot { it.startsWith("mipmap-anydpi-v33/") }
        }
        val ownershipProblems = OwnedOutputSafety.inspectInstalledFiles(
            installationRoot = res,
            manifestFile = res.parentFile.resolve(".kmpssot/android-logo-owned-files-v1"),
            projectRoot = projectRoot,
            owner = "android-logo",
            expectedRelativePaths = effectiveExpected,
            expectedInputFingerprint = context.androidLogoInputFingerprint,
        )
        if (ownershipProblems.isNotEmpty()) {
            add(
                diagnostic(
                    "KMPS031",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Android launcher icons",
                    ownershipProblems.joinToString("; "),
                    "Review and explicitly rerun kmpSsotSyncAndroidLogo; modified or unowned files are never overwritten automatically.",
                    location = res.path,
                ),
            )
        } else if (collisions.isEmpty()) {
            add(diagnostic("KMPS031", KmpSsotDiagnosticSeverity.PASS, "Android launcher icons", "Every expected launcher output is present and checksum-owned; no collisions were found."))
        } else {
            add(
                diagnostic(
                    "KMPS031",
                    KmpSsotDiagnosticSeverity.WARNING,
                    "Android launcher icons",
                    "${collisions.size} colliding template icon(s): ${collisions.joinToString { it.relativeToOrSelf(res).path }}.",
                    "Review, then run the explicit legacy-logo migration task; it backs up every removed file.",
                ),
            )
        }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnoseLocales(context: KmpSsotDiagnosticContext) {
        if (!context.propagateLocaleList && !context.filterAndroidResources) {
            add(diagnostic("KMPS040", KmpSsotDiagnosticSeverity.SKIPPED, "Locales", "Locale propagation is disabled."))
            return
        }
        val canonical = runCatching { canonicalizeLocales(context.locales) }.getOrElse { failure ->
            add(
                diagnostic(
                    "KMPS040",
                    KmpSsotDiagnosticSeverity.ERROR,
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
                    "KMPS040",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Locales",
                    "Android resource filtering is enabled, but the canonical locale allow-list is empty.",
                    "Configure locales explicitly or add a supported values-<locale> resource directory.",
                ),
            )
            context.locales.isEmpty() -> add(diagnostic("KMPS040", KmpSsotDiagnosticSeverity.PASS, "Locales", "No explicit locale allow-list is configured."))
            canonical == context.locales -> add(diagnostic("KMPS040", KmpSsotDiagnosticSeverity.PASS, "Locales", "All locale tags are canonical and unique."))
            else -> add(
                diagnostic(
                    "KMPS040",
                    KmpSsotDiagnosticSeverity.WARNING,
                    "Locales",
                    "Configured ${context.locales.joinToString()} canonicalizes to ${canonical.joinToString()}.",
                    "Store canonical BCP-47 tags in the SSOT DSL.",
                ),
            )
        }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnoseVersion(context: KmpSsotDiagnosticContext) {
        if (!context.propagateVersion || context.hasVersionCodeOverride) {
            add(diagnostic("KMPS050", KmpSsotDiagnosticSeverity.SKIPPED, "Android versionCode", "Version propagation is disabled or an explicit override is configured."))
            return
        }
        val version = context.versionName
        if (version == null) {
            add(diagnostic("KMPS050", KmpSsotDiagnosticSeverity.SKIPPED, "Android versionCode", "versionName is unset; identity values are optional until configured."))
            return
        }
        runCatching { deriveVersionCode(version) }
            .onSuccess { add(diagnostic("KMPS050", KmpSsotDiagnosticSeverity.PASS, "Android versionCode", "$version derives monotonically to $it.")) }
            .onFailure { failure ->
                add(
                    diagnostic(
                        "KMPS050",
                        KmpSsotDiagnosticSeverity.ERROR,
                        "Android versionCode",
                        diagnosticExceptionSummary(failure),
                        "Use exactly three 0..999 components without leading zeroes, or set versionCodeOverride.",
                    ),
                )
            }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnoseKgp(context: KmpSsotDiagnosticContext) {
        when {
            !context.kgpRequired -> add(diagnostic("KMPS060", KmpSsotDiagnosticSeverity.SKIPPED, "Kotlin Gradle plugin", "No enabled feature requires typed KGP access."))
            context.kgpOnClasspath -> add(diagnostic("KMPS060", KmpSsotDiagnosticSeverity.PASS, "Kotlin Gradle plugin", "KGP types are visible to kmp-ssot."))
            else -> add(
                diagnostic(
                    "KMPS060",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Kotlin Gradle plugin",
                    "KGP types are not visible to kmp-ssot's classloader, but an enabled feature requires them.",
                    "Declare kotlin(\"multiplatform\") apply false in the root plugins block.",
                ),
            )
        }
    }

    private fun MutableList<KmpSsotDiagnostic>.diagnosePluginCompatibility(context: KmpSsotDiagnosticContext) {
        when {
            !context.agpRequired -> add(
                diagnostic(
                    "KMPS061",
                    KmpSsotDiagnosticSeverity.SKIPPED,
                    "Android Gradle plugin compatibility",
                    "No detected Android project needs an enabled typed AGP integration.",
                ),
            )

            !context.agpOnClasspath -> add(
                diagnostic(
                    "KMPS061",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Android Gradle plugin compatibility",
                    "AGP types are not visible to kmp-ssot, so the active version cannot be verified.",
                    "Declare the Android plugin version with apply false in the root plugins block.",
                ),
            )

            context.activeAgpVersion == null -> add(
                diagnostic(
                    "KMPS061",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Android Gradle plugin compatibility",
                    "Could not determine the active Android Gradle plugin version.",
                    "Use an AGP release in the supported range 8.5.2 through 9.1.x.",
                    expected = "8.5.2..9.1.x",
                ),
            )

            !isSupportedAgpVersion(context.activeAgpVersion) -> add(
                diagnostic(
                    "KMPS061",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Android Gradle plugin compatibility",
                    "Active Android Gradle plugin ${context.activeAgpVersion} is unsupported.",
                    "Use an AGP release in the supported range 8.5.2 through 9.1.x.",
                    expected = "8.5.2..9.1.x",
                    actual = context.activeAgpVersion,
                ),
            )

            else -> add(
                diagnostic(
                    "KMPS061",
                    KmpSsotDiagnosticSeverity.PASS,
                    "Android Gradle plugin compatibility",
                    "Active Android Gradle plugin ${context.activeAgpVersion} is supported.",
                ),
            )
        }

        when {
            !context.kgpRequired -> add(
                diagnostic(
                    "KMPS062",
                    KmpSsotDiagnosticSeverity.SKIPPED,
                    "Kotlin Gradle plugin compatibility",
                    "No enabled feature requires typed KGP integration.",
                ),
            )

            !context.kgpOnClasspath -> add(
                diagnostic(
                    "KMPS062",
                    KmpSsotDiagnosticSeverity.SKIPPED,
                    "Kotlin Gradle plugin compatibility",
                    "The active KGP version cannot be inspected until the KMPS060 classloader error is fixed.",
                ),
            )

            context.activeKgpVersion == null -> add(
                diagnostic(
                    "KMPS062",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Kotlin Gradle plugin compatibility",
                    "Could not determine the active Kotlin Gradle plugin version.",
                    "Use a KGP 2.4.x release.",
                    expected = "2.4.x",
                ),
            )

            !isSupportedKgpVersion(context.activeKgpVersion) -> add(
                diagnostic(
                    "KMPS062",
                    KmpSsotDiagnosticSeverity.ERROR,
                    "Kotlin Gradle plugin compatibility",
                    "Active Kotlin Gradle plugin ${context.activeKgpVersion} is unsupported.",
                    "Use a KGP 2.4.x release.",
                    expected = "2.4.x",
                    actual = context.activeKgpVersion,
                ),
            )

            else -> add(
                diagnostic(
                    "KMPS062",
                    KmpSsotDiagnosticSeverity.PASS,
                    "Kotlin Gradle plugin compatibility",
                    "Active Kotlin Gradle plugin ${context.activeKgpVersion} is supported.",
                ),
            )
        }
    }

    private fun MutableList<KmpSsotDiagnostic>.safeExists(
        context: KmpSsotDiagnosticContext,
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

    private fun MutableList<KmpSsotDiagnostic>.safeRead(
        context: KmpSsotDiagnosticContext,
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

internal object KmpSsotDiagnosticReports {
    fun json(findings: List<KmpSsotDiagnostic>): String = buildString {
        append("{\n  \"schemaVersion\": 1,\n  \"summary\": {")
        KmpSsotDiagnosticSeverity.entries.forEachIndexed { index, severity ->
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

    fun sarif(findings: List<KmpSsotDiagnostic>, projectRoot: File? = null): String = buildString {
        val rules = findings.distinctBy { it.id }.sortedBy { it.id }
        append("{\n  \"version\": \"2.1.0\",\n  \"\$schema\": ")
        append(jsonString("https://json.schemastore.org/sarif-2.1.0.json"))
        append(",\n  \"runs\": [{\n    \"tool\": {\"driver\": {\"name\": \"kmp-ssot\", \"rules\": [")
        rules.forEachIndexed { index, rule ->
            if (index > 0) append(',')
            append("\n      {\"id\": ").append(jsonString(rule.id))
            append(", \"shortDescription\": {\"text\": ").append(jsonString(rule.title)).append('}')
            append('}')
        }
        append("\n    ]}},\n    \"results\": [")
        findings.filter { it.severity == KmpSsotDiagnosticSeverity.WARNING || it.severity == KmpSsotDiagnosticSeverity.ERROR }
            .forEachIndexed { index, finding ->
                if (index > 0) append(',')
                val level = if (finding.severity == KmpSsotDiagnosticSeverity.ERROR) "error" else "warning"
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
    severity: KmpSsotDiagnosticSeverity,
    title: String,
    detail: String,
    remediation: String? = null,
    location: String? = null,
    expected: String? = null,
    actual: String? = null,
) = KmpSsotDiagnostic(id, severity, title, detail, remediation, location, expected, actual)

private fun ioFailure(id: String, title: String, file: File, failure: Throwable) = diagnostic(
    id,
    KmpSsotDiagnosticSeverity.ERROR,
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
