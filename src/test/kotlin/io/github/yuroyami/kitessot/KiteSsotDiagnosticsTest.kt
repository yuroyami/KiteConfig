package io.github.yuroyami.kitessot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KiteSsotDiagnosticsTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `malformed pbxproj is a stable fail-closed finding`() {
        val pbxproj = tempDir.resolve("project.pbxproj").apply { writeText("not an OpenStep project") }

        val findings = KiteSsotDiagnosticEngine.evaluate(
            quietContext(syncIos = true, pbxprojFile = pbxproj),
        )

        val scope = findings.single { it.id == "KMPS021" }
        assertEquals(KiteSsotDiagnosticSeverity.ERROR, scope.severity)
        assertTrue(scope.detail.contains("root must be exactly one dictionary"), scope.detail)
        assertTrue(scope.remediation!!.contains("never rewritten globally"), scope.remediation)
    }

    @Test
    fun `filesystem read failure becomes a finding instead of escaping`() {
        val findings = KiteSsotDiagnosticEngine.evaluate(
            quietContext(
                propagateAppName = true,
                appName = "Demo",
                manifestFile = tempDir, // a directory cannot be read as UTF-8 text
            ),
        )

        val manifest = findings.single { it.id == "KMPS002" }
        assertEquals(KiteSsotDiagnosticSeverity.ERROR, manifest.severity)
        assertTrue(manifest.detail.contains(tempDir.path), manifest.detail)
    }

    @Test
    fun `diagnostics reject configured files outside the root project`() {
        val project = tempDir.resolve("project").apply { mkdirs() }
        val outside = tempDir.resolve("outside.xml").apply {
            writeText("<manifest><application /></manifest>")
        }

        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(
                propagateAppName = true,
                appName = "Demo",
                manifestFile = outside,
                projectRootDir = project,
            ),
        ).single { it.id == "KMPS001" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("outside the root project directory"), finding.detail)
    }

    @Test
    fun `manifest diagnostic parses the exact application label structurally`() {
        val manifest = tempDir.resolve("AndroidManifest.xml").apply {
            writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <!-- android:label="${'$'}{appName}" in a comment must not pass -->
                    <application android:label="Demo" />
                </manifest>
                """.trimIndent(),
            )
        }

        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(propagateAppName = true, appName = "Demo", manifestFile = manifest),
        ).single { it.id == "KMPS002" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertEquals("\${appName}", finding.expected)
        assertEquals("Demo", finding.actual)
        assertEquals(manifest.path, finding.location)
    }

    @Test
    fun `Android logo diagnostics require the installed launcher resource references`() {
        val manifest = tempDir.resolve("AndroidManifest.xml")
        fun finding(icon: String, roundIcon: String?): KiteSsotDiagnostic {
            manifest.writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:icon="$icon"${roundIcon?.let { " android:roundIcon=\"$it\"" }.orEmpty()} />
                </manifest>
                """.trimIndent(),
            )
            return KiteSsotDiagnosticEngine.evaluate(
                quietContext(propagateLogo = true, manifestFile = manifest),
            ).single { it.id == "KMPS003" }
        }

        val stale = finding("@mipmap/legacy_icon", "@mipmap/legacy_round")
        assertEquals(KiteSsotDiagnosticSeverity.ERROR, stale.severity)
        assertTrue(stale.remediation!!.contains("@mipmap/ic_launcher"), stale.remediation)

        val missingRound = finding("@mipmap/ic_launcher", null)
        assertEquals(KiteSsotDiagnosticSeverity.WARNING, missingRound.severity)

        val aligned = finding("@mipmap/ic_launcher", "@mipmap/ic_launcher_round")
        assertEquals(KiteSsotDiagnosticSeverity.PASS, aligned.severity)
    }

    @Test
    fun `plist diagnostic rejects duplicate keys instead of accepting matching text`() {
        val plist = tempDir.resolve("Info.plist").apply {
            writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <plist version="1.0"><dict>
                    <key>CFBundleDisplayName</key><string>${'$'}(PRODUCT_NAME)</string>
                    <key>CFBundleDisplayName</key><string>${'$'}(PRODUCT_NAME)</string>
                    <key>CFBundleName</key><string>${'$'}(PRODUCT_NAME)</string>
                </dict></plist>
                """.trimIndent(),
            )
        }

        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(
                propagateAppName = true,
                appName = "Demo",
                syncIos = true,
                sanitizeIosProject = true,
                infoPlistFile = plist,
            ),
        ).single { it.id == "KMPS011" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("duplicate root key"), finding.detail)
    }

    @Test
    fun `plist diagnostic rejects internal doctype entities`() {
        val plist = tempDir.resolve("Info.plist").apply {
            writeText(
                """
                <?xml version="1.0"?>
                <!DOCTYPE plist [<!ENTITY x "boom">]>
                <plist version="1.0"><dict>
                    <key>CFBundleDisplayName</key><string>&x;</string>
                </dict></plist>
                """.trimIndent(),
            )
        }

        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(
                propagateAppName = true,
                appName = "Demo",
                syncIos = true,
                sanitizeIosProject = true,
                infoPlistFile = plist,
            ),
        ).single { it.id == "KMPS011" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("internal DTD"), finding.detail)
    }

    @Test
    fun `plist diagnostic verifies configured boolean flags`() {
        val plist = tempDir.resolve("Info.plist").apply {
            writeText(
                plist(
                    "\t<key>ITSAppUsesNonExemptEncryption</key>\n\t<true/>",
                ),
            )
        }

        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(
                syncIos = true,
                sanitizeIosProject = true,
                infoPlistFile = plist,
                usesNonExemptEncryption = false,
            ),
        ).single { it.id == "KMPS011" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("ITSAppUsesNonExemptEncryption"), finding.detail)
        assertTrue(finding.detail.contains("<false/>"), finding.detail)
    }

    @Test
    fun `plist diagnostic reports KEEP conflicts as intentionally preserved warnings`() {
        val plist = tempDir.resolve("Info.plist").apply {
            writeText(
                plist(
                    "\t<key>CFBundleDisplayName</key>\n\t<string>Legacy name</string>\n" +
                        "\t<key>CFBundleName</key>\n\t<string>Legacy name</string>",
                ),
            )
        }

        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(
                propagateAppName = true,
                appName = "Demo",
                syncIos = true,
                sanitizeIosProject = true,
                infoPlistFile = plist,
                plistConflictPolicy = PlistConflictPolicy.KEEP,
            ),
        ).single { it.id == "KMPS011" }

        assertEquals(KiteSsotDiagnosticSeverity.WARNING, finding.severity)
        assertTrue(finding.detail.contains("preserved by conflictPolicy=KEEP"), finding.detail)
    }

    @Test
    fun `plist diagnostic reports pending REPLACE conflicts as actionable drift`() {
        val plist = tempDir.resolve("Info.plist").apply {
            writeText(
                plist(
                    "\t<key>CFBundleDisplayName</key>\n\t<string>Legacy name</string>\n" +
                        "\t<key>CFBundleName</key>\n\t<string>Legacy name</string>",
                ),
            )
        }

        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(
                propagateAppName = true,
                appName = "Demo",
                syncIos = true,
                sanitizeIosProject = true,
                infoPlistFile = plist,
                plistConflictPolicy = PlistConflictPolicy.REPLACE,
            ),
        ).single { it.id == "KMPS011" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("conflictPolicy=REPLACE"), finding.detail)
        assertTrue(finding.detail.contains("CFBundleDisplayName"), finding.detail)
    }

    @Test
    fun `pbx diagnostic reports configured value drift and passes after rewrite`() {
        val original = checkNotNull(
            javaClass.getResourceAsStream("/fixtures/RealApp.xcodeproj/project.pbxproj"),
        ).bufferedReader().use { it.readText() }
        val pbxproj = tempDir.resolve("project.pbxproj").apply { writeText(original) }
        val context = quietContext(
            propagateAppName = true,
            appName = "Diagnostic Name",
            syncIos = true,
            pbxprojFile = pbxproj,
        )

        val drift = KiteSsotDiagnosticEngine.evaluate(context).single { it.id == "KMPS021" }
        assertEquals(KiteSsotDiagnosticSeverity.ERROR, drift.severity)
        assertTrue(drift.detail.contains("PRODUCT_NAME"), drift.detail)

        val rewritten = rewritePbxproj(
            original,
            marketingVersion = null,
            buildNumber = null,
            appName = "Diagnostic Name",
            bundleId = null,
            locales = null,
        )
        assertTrue(rewritten.errors.isEmpty(), rewritten.errors.joinToString())
        pbxproj.writeText(rewritten.text)

        val aligned = KiteSsotDiagnosticEngine.evaluate(context).single { it.id == "KMPS021" }
        assertEquals(KiteSsotDiagnosticSeverity.PASS, aligned.severity)
    }

    @Test
    fun `Android icon diagnostics reject a tampered manifest-owned output`() {
        val project = tempDir.resolve("android-project").apply { mkdirs() }
        val res = project.resolve("app/src/main/res")
        val expectedPaths = SyncAndroidLogoTask.OUTPUT_RELATIVE_PATHS
            .filterNot { it.startsWith("mipmap-anydpi-v33/") }
        val files = expectedPaths.mapIndexed { index, path -> path to byteArrayOf(index.toByte(), 7) }.toMap()
        val manifest = res.parentFile.resolve(".kitessot/android-logo-owned-files-v1")
        OwnedOutputSafety.replaceInstalledFiles(
            installationRoot = res,
            manifestFile = manifest,
            projectRoot = project,
            owner = "android-logo",
            files = files,
        )
        res.resolve(expectedPaths.first()).writeBytes(byteArrayOf(99))

        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(
                propagateLogo = true,
                androidResDir = res,
                projectRootDir = project,
            ),
        ).single { it.id == "KMPS031" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("checksum differs"), finding.detail)
    }

    @Test
    fun `iOS icon diagnostics reject a missing manifest-owned output`() {
        val project = tempDir.resolve("ios-project").apply { mkdirs() }
        val icons = project.resolve("ios/App/Assets.xcassets/AppIcon.appiconset")
        val identity = SyncIosLogoTask.catalogIdentity(project, icons)
        val manifest = project.resolve("ios/App/.kitessot/$identity/owned-files-v1")
        OwnedOutputSafety.replaceInstalledFiles(
            installationRoot = icons,
            manifestFile = manifest,
            projectRoot = project,
            owner = identity,
            files = SyncIosLogoTask.OUTPUT_FILE_NAMES.associateWith { it.toByteArray() },
        )
        icons.resolve("Contents.json").delete()

        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(
                syncIos = true,
                propagateLogo = true,
                appiconsetDir = icons,
                projectRootDir = project,
            ),
        ).single { it.id == "KMPS022" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("missing"), finding.detail)
    }

    @Test
    fun `Android selector diagnostic retains invalid duplicate and unknown entries`() {
        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext().copy(
                androidApplicationProjects = listOf("phone", ":missing", ":phone", ":phone"),
                detectedAndroidApplicationProjects = listOf(":phone", ":tablet"),
            ),
        ).single { it.id == "KMPS070" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("invalid absolute Gradle project path"), finding.detail)
        assertTrue(finding.detail.contains("\":missing\""), finding.detail)
        assertTrue(finding.detail.contains("duplicate selector(s): \":phone\""), finding.detail)
    }

    @Test
    fun `Android selector diagnostic rejects implicit ambiguity only for active app scope`() {
        val active = KiteSsotDiagnosticEngine.evaluate(
            quietContext(propagateAppName = true, appName = "Demo").copy(
                detectedAndroidApplicationProjects = listOf(":phone", ":tablet"),
            ),
        ).single { it.id == "KMPS070" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, active.severity)
        assertTrue(active.detail.contains(":phone, :tablet"), active.detail)
        assertTrue(active.detail.contains("no explicit modules { androidApps }"), active.detail)

        val inactive = KiteSsotDiagnosticEngine.evaluate(
            quietContext().copy(detectedAndroidApplicationProjects = listOf(":phone", ":tablet")),
        ).single { it.id == "KMPS070" }
        assertEquals(KiteSsotDiagnosticSeverity.SKIPPED, inactive.severity)
    }

    @Test
    fun `iOS selector diagnostic preserves and reports duplicate target names`() {
        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(syncIos = true).copy(iosTargetNames = listOf("Phone", "Phone")),
        ).single { it.id == "KMPS071" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("duplicate target name(s): \"Phone\""), finding.detail)
    }

    @Test
    fun `Android selector diagnostic rejects multi-app single-sink features`() {
        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(propagateLogo = true).copy(
                androidApplicationProjects = listOf(":phone", ":tablet"),
                detectedAndroidApplicationProjects = listOf(":phone", ":tablet"),
            ),
        ).single { it.id == "KMPS070" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("at most one effective Android application"), finding.detail)
    }

    @Test
    fun `iOS selector diagnostic rejects one bundle id across multiple targets`() {
        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(syncIos = true).copy(
                iosTargetNames = listOf("Phone", "Tablet"),
                iosBundleId = "com.example.demo",
                propagateBundleId = true,
            ),
        ).single { it.id == "KMPS071" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("one propagated bundle identifier"), finding.detail)
    }

    @Test
    fun `long cross-platform app name warns for Apple bundle-name UX`() {
        val finding = KiteSsotDiagnosticEngine.evaluate(
            quietContext(syncIos = true, propagateAppName = true, appName = "Sixteen CharName"),
        ).single { it.id == "KMPS012" }

        assertEquals(KiteSsotDiagnosticSeverity.WARNING, finding.severity)
        assertTrue(finding.detail.contains("fewer than 16"), finding.detail)
    }

    @Test
    fun `universal AppIcon diagnostic requires a compatible deployment target`() {
        val missing = KiteSsotDiagnosticEngine.evaluate(
            quietContext(syncIos = true, propagateLogo = true),
        ).single { it.id == "KMPS023" }
        assertEquals(KiteSsotDiagnosticSeverity.ERROR, missing.severity)

        val compatible = KiteSsotDiagnosticEngine.evaluate(
            quietContext(syncIos = true, propagateLogo = true).copy(iosDeploymentTarget = "12.0"),
        ).single { it.id == "KMPS023" }
        assertEquals(KiteSsotDiagnosticSeverity.PASS, compatible.severity)
    }

    @Test
    fun `active unsupported AGP and KGP versions are errors only when relevant`() {
        val relevant = KiteSsotDiagnosticEngine.evaluate(
            quietContext().copy(
                agpRequired = true,
                agpOnClasspath = true,
                activeAgpVersion = "9.4.0",
                kgpRequired = true,
                kgpOnClasspath = true,
                activeKgpVersion = "2.5.0",
            ),
        )

        val agp = relevant.single { it.id == "KMPS061" }
        assertEquals(KiteSsotDiagnosticSeverity.ERROR, agp.severity)
        assertEquals("9.4.0", agp.actual)
        val kgp = relevant.single { it.id == "KMPS062" }
        assertEquals(KiteSsotDiagnosticSeverity.ERROR, kgp.severity)
        assertEquals("2.5.0", kgp.actual)

        val irrelevant = KiteSsotDiagnosticEngine.evaluate(
            quietContext().copy(
                agpOnClasspath = true,
                activeAgpVersion = "9.4.0",
                kgpOnClasspath = true,
                activeKgpVersion = "2.5.0",
            ),
        )
        assertEquals(
            KiteSsotDiagnosticSeverity.SKIPPED,
            irrelevant.single { it.id == "KMPS061" }.severity,
        )
        assertEquals(
            KiteSsotDiagnosticSeverity.SKIPPED,
            irrelevant.single { it.id == "KMPS062" }.severity,
        )
    }

    @Test
    fun `json report is deterministic and escapes source text`() {
        val finding = KiteSsotDiagnostic(
            id = "KMPS777",
            severity = KiteSsotDiagnosticSeverity.ERROR,
            title = "A \"quoted\" title",
            detail = "line one\nline two\\tail\u2028\u2029\uD800",
            remediation = "tab\there",
        )

        val first = KiteSsotDiagnosticReports.json(listOf(finding))
        val second = KiteSsotDiagnosticReports.json(listOf(finding))

        assertEquals(first, second)
        assertTrue(first.contains("A \\\"quoted\\\" title"), first)
        assertTrue(first.contains("line one\\nline two\\\\tail"), first)
        assertTrue(first.contains("tab\\there"), first)
        assertTrue(first.contains("\\u2028\\u2029\\ud800"), first)
    }

    @Test
    fun `console diagnostics bound and escape uncontrolled finding text`() {
        val raw = "line\n\u001B[31m" + "x".repeat(10_000)
        val console = renderDiagnosticConsole(
            "probe",
            listOf(KiteSsotDiagnostic("KMPS777", KiteSsotDiagnosticSeverity.ERROR, raw, raw, raw)),
        )

        assertTrue(console.length < 7_000, console.length.toString())
        assertTrue(console.contains("line\\n\\u001b[31m"), console)
        assertTrue(!console.contains('\u001B'), console)
    }

    @Test
    fun `sarif contains stable rule ids and only actionable results`() {
        val report = KiteSsotDiagnosticReports.sarif(
            listOf(
                KiteSsotDiagnostic("KMPS001", KiteSsotDiagnosticSeverity.PASS, "Pass", "ok"),
                KiteSsotDiagnostic("KMPS002", KiteSsotDiagnosticSeverity.WARNING, "Warn", "careful"),
                KiteSsotDiagnostic("KMPS003", KiteSsotDiagnosticSeverity.ERROR, "Error", "broken"),
            ),
        )

        assertTrue(report.contains("\"version\": \"2.1.0\""), report)
        assertTrue(report.contains("\"ruleId\": \"KMPS002\""), report)
        assertTrue(report.contains("\"ruleId\": \"KMPS003\""), report)
        assertTrue(!report.contains("\"ruleId\": \"KMPS001\""), report)
    }

    @Test
    fun `sarif locations are project relative portable and URI encoded`() {
        val project = tempDir.resolve("checkout with space").apply { mkdirs() }
        val source = project.resolve("src/My File.kt")
        val relative = KiteSsotDiagnosticReports.sarif(
            listOf(
                KiteSsotDiagnostic(
                    "KMPS777",
                    KiteSsotDiagnosticSeverity.ERROR,
                    "Path",
                    "broken",
                    location = source.path,
                ),
            ),
            project,
        )
        assertTrue(relative.contains("src/My%20File.kt"), relative)
        assertTrue(!relative.contains(project.absolutePath), relative)

        val windows = KiteSsotDiagnosticReports.sarif(
            listOf(
                KiteSsotDiagnostic(
                    "KMPS778",
                    KiteSsotDiagnosticSeverity.ERROR,
                    "Windows path",
                    "broken",
                    location = "C:\\work tree\\src\\My File.kt",
                ),
            ),
        )
        assertTrue(windows.contains("file:///C:/work%20tree/src/My%20File.kt"), windows)
        assertTrue(!windows.contains("\\\\work"), windows)

        val hostile = KiteSsotDiagnosticReports.sarif(
            listOf(
                KiteSsotDiagnostic(
                    "KMPS779",
                    KiteSsotDiagnosticSeverity.ERROR,
                    "Hostile path",
                    "broken",
                    location = "bad\u0000\uD800",
                ),
            ),
        )
        assertTrue(hostile.contains("\"ruleId\": \"KMPS779\""), hostile)
        assertTrue(!hostile.contains('\u0000'), hostile)
    }

    @Test
    fun `enabled Android resource filtering rejects an empty locale allow-list`() {
        val finding = KiteSsotDiagnosticEngine.evaluate(
            KiteSsotDiagnosticContext(
                propagateAppName = false,
                propagateBundleId = false,
                propagateVersion = false,
                propagateLocaleList = false,
                filterAndroidResources = true,
            ),
        ).single { it.id == "KMPS040" }

        assertEquals(KiteSsotDiagnosticSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("allow-list is empty"), finding.detail)
    }

    private fun quietContext(
        propagateAppName: Boolean = false,
        appName: String? = null,
        manifestFile: File? = null,
        syncIos: Boolean = false,
        sanitizeIosProject: Boolean = false,
        infoPlistFile: File? = null,
        usesNonExemptEncryption: Boolean? = null,
        plistConflictPolicy: PlistConflictPolicy = PlistConflictPolicy.FAIL,
        pbxprojFile: File? = null,
        propagateLogo: Boolean = false,
        appiconsetDir: File? = null,
        androidResDir: File? = null,
        projectRootDir: File? = tempDir,
    ) = KiteSsotDiagnosticContext(
        propagateAppName = propagateAppName,
        appName = appName,
        propagateVersion = false,
        propagateLocaleList = false,
        manifestFile = manifestFile,
        syncIos = syncIos,
        sanitizeIosProject = sanitizeIosProject,
        infoPlistFile = infoPlistFile,
        usesNonExemptEncryption = usesNonExemptEncryption,
        plistConflictPolicy = plistConflictPolicy,
        pbxprojFile = pbxprojFile,
        propagateLogo = propagateLogo,
        appiconsetDir = appiconsetDir,
        androidResDir = androidResDir,
        projectRootDir = projectRootDir,
        kgpRequired = false,
    )

    private fun plist(body: String) = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        |<plist version="1.0">
        |<dict>
        |$body
        |</dict>
        |</plist>
    """.trimMargin()
}
