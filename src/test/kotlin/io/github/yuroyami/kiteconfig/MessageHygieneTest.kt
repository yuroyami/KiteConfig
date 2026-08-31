package io.github.yuroyami.kiteconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The 3.0 DSL renamed most of the 2.x surface, so a runtime message that still
 * says `appLogoPngForeground` sends the reader hunting for a property that no
 * longer exists. This scans every string literal in main for the old names.
 */
class MessageHygieneTest {

    /** Every 2.x property the 3.0 DSL renamed or absorbed. */
    private val retiredNames = listOf(
        "androidAppDirectory", "androidApplicationIdSuffix", "androidApplicationProjects",
        "appLogoAndroidSafeZoneRatio", "appLogoBackgroundColor", "appLogoPngBackground",
        "appLogoPngForeground", "backupBeforeRewrite", "bundleIdBase",
        "cleanupLegacyLogoArtifacts", "composeResourcesDirectory", "extraOptIns",
        "filterAndroidResources", "interopProjectPaths", "iosAppDirectory",
        "iosAppIconDirectory", "iosBuildNumber", "iosBundleSuffix", "iosInfoPlistFile",
        "iosMarketingVersion", "iosPbxprojFile", "iosPodfileFile",
        "iosPreviousSharedModuleName", "iosSharedModuleName", "javaVersion",
        "propagateAndroidSdk", "propagateAppName", "propagateBundleId",
        "propagateInteropOptIns", "propagateLocaleList", "propagateLogo",
        "propagateSharedModule", "propagateVersion", "sanitizeIosProject",
        "sharedProjectPath", "syncIos", "versionCodeOverride", "versionName",
    )

    /**
     * Where an old name may still live inside a string:
     * the extension declares the deprecation bridges, and the generated
     * BuildConfig keeps its `versionName` field because consumer code reads it.
     */
    private val allowed = mapOf(
        "KiteConfigExtension.kt" to retiredNames.toSet(),
        "BuildConfigGen.kt" to setOf("versionName"),
        // The drift warning names AGP's own `versionName` DSL property as the module
        // declared it, not the retired 2.x kiteConfig property.
        "ClassicAndroidWiring.kt" to setOf("versionName"),
    )

    @Test
    fun `no runtime string still names a retired 2x property`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "expected to run from the project root, got ${File(".").absolutePath}")

        val offences = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val pardoned = allowed[file.name].orEmpty()
                file.readLines().withIndex().flatMap { (index, line) ->
                    retiredNames.filter { name ->
                        name !in pardoned &&
                            line.namesInsideAString(name) &&
                            !line.qualifiesWithModulesBlock(name)
                    }.map { name -> "${file.name}:${index + 1} says \"$name\"" }
                }
            }
            .toList()

        assertEquals(emptyList<String>(), offences, offences.joinToString("\n"))
    }

    /**
     * `androidAppDirectory` is a retired root property but a current `modules { }`
     * member, so the qualified spelling is correct 3.0 advice, not a stale name.
     */
    private fun String.qualifiesWithModulesBlock(name: String): Boolean =
        name == "androidAppDirectory" && contains("modules {")

    /** True when [name] sits after an odd number of quotes, i.e. inside a literal. */
    private fun String.namesInsideAString(name: String): Boolean {
        var from = 0
        while (true) {
            val at = indexOf(name, from)
            if (at < 0) return false
            if (substring(0, at).count { it == '"' } % 2 == 1) return true
            from = at + 1
        }
    }
}
