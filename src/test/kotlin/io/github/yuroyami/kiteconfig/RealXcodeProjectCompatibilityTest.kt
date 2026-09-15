package io.github.yuroyami.kiteconfig

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Compatibility coverage using a project emitted by the Ruby xcodeproj library. */
class RealXcodeProjectCompatibilityTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `Xcode-generated application project remains parseable and rewrite is idempotent`() {
        val original = checkNotNull(
            javaClass.getResourceAsStream("/fixtures/RealApp.xcodeproj/project.pbxproj"),
        ) { "real Xcode fixture is missing" }.bufferedReader().use { it.readText() }

        val first = rewritePbxproj(
            original = original,
            marketingVersion = "2.3.4",
            buildNumber = "42",
            appName = "Renamed App",
            bundleId = "dev.example.renamed",
            locales = listOf("en", "fr", "pt-BR"),
            targetNames = setOf("Fixture App"),
        )

        assertTrue(first.errors.isEmpty(), first.errors.joinToString())
        assertEquals(listOf("Fixture App"), first.selectedTargets)
        assertFalse(first.text == original, "fixture was not rewritten")
        assertTrue(first.text.contains("MARKETING_VERSION = 2.3.4;"), first.text)
        assertTrue(first.text.contains("CURRENT_PROJECT_VERSION = 42;"), first.text)
        assertTrue(first.text.contains("PRODUCT_BUNDLE_IDENTIFIER = dev.example.renamed;"), first.text)
        assertTrue(first.text.contains("PRODUCT_NAME = \"Renamed App\";"), first.text)
        assertTrue(first.text.contains("pt-BR"), first.text)

        val second = rewritePbxproj(
            original = first.text,
            marketingVersion = "2.3.4",
            buildNumber = "42",
            appName = "Renamed App",
            bundleId = "dev.example.renamed",
            locales = listOf("en", "fr", "pt-BR"),
            targetNames = setOf("Fixture App"),
        )
        assertTrue(second.errors.isEmpty(), second.errors.joinToString())
        assertEquals(first.text, second.text, "second rewrite changed an already-canonical project")
        // Nothing is left to change or reformat, so the doctor has nothing to report.
        assertEquals(emptySet<String>(), second.changedSettings)
        assertEquals(emptySet<String>(), second.reformattedSettings)

        validateWithInstalledXcode(first.text)
    }

    /** The value the reader now depends on, taken from a project Xcode itself wrote. */
    @Test
    fun `the deployment target is read out of a real Xcode project`() {
        val original = checkNotNull(
            javaClass.getResourceAsStream("/fixtures/RealApp.xcodeproj/project.pbxproj"),
        ) { "real Xcode fixture is missing" }.bufferedReader().use { it.readText() }

        assertEquals("14.0", iosDeploymentTargetFrom(original))
        assertEquals("14.0", iosDeploymentTargetFrom(original, setOf("Fixture App")))
    }

    @Test
    fun `a Debug override on the real project is applied once and stays put`() {
        val original = checkNotNull(
            javaClass.getResourceAsStream("/fixtures/RealApp.xcodeproj/project.pbxproj"),
        ) { "real Xcode fixture is missing" }.bufferedReader().use { it.readText() }
        val overrides = mapOf(
            "Debug" to PbxConfigurationOverride(
                appName = "Renamed Dev",
                bundleId = "dev.example.renamed.debug",
                appIconName = "AppIcon-Debug",
            ),
        )

        fun rewrite(text: String) = rewritePbxproj(
            original = text,
            marketingVersion = "2.3.4",
            buildNumber = "42",
            appName = "Renamed App",
            bundleId = "dev.example.renamed",
            locales = null,
            targetNames = setOf("Fixture App"),
            appIconName = "AppIcon",
            configurationOverrides = overrides,
        )

        val first = rewrite(original)
        assertTrue(first.errors.isEmpty(), first.errors.joinToString())
        assertEquals(1, Regex("PRODUCT_BUNDLE_IDENTIFIER = dev\\.example\\.renamed\\.debug;").findAll(first.text).count(), first.text)
        assertEquals(1, Regex("PRODUCT_BUNDLE_IDENTIFIER = dev\\.example\\.renamed;").findAll(first.text).count(), first.text)
        assertEquals(1, Regex("PRODUCT_NAME = \"Renamed Dev\";").findAll(first.text).count(), first.text)
        assertEquals(1, Regex("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon-Debug;").findAll(first.text).count(), first.text)
        assertEquals(1, Regex("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;").findAll(first.text).count(), first.text)

        val second = rewrite(first.text)
        assertTrue(second.errors.isEmpty(), second.errors.joinToString())
        assertEquals(first.text, second.text)
        assertEquals(emptySet<String>(), second.changedSettings)

        validateWithInstalledXcode(first.text)
    }

    private fun validateWithInstalledXcode(pbxproj: String) {
        val xcodebuild = File("/usr/bin/xcodebuild")
        assumeTrue(xcodebuild.canExecute(), "xcodebuild is unavailable on this host")
        val project = tempDir.resolve("Rewritten.xcodeproj")
        project.mkdirs()
        project.resolve("project.pbxproj").writeText(pbxproj)

        val process = ProcessBuilder(
            xcodebuild.path,
            "-list",
            "-project",
            project.path,
        ).redirectErrorStream(true).apply {
            environment()["HOME"] = tempDir.path
            environment()["CFFIXED_USER_HOME"] = tempDir.path
        }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()

        assertEquals(0, exit, output)
        assertTrue(output.contains("Fixture App"), output)
    }
}
