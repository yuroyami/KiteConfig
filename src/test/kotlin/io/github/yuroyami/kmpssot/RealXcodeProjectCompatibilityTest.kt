package io.github.yuroyami.kmpssot

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
