package io.github.yuroyami.kiteconfig.toolchain

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XcodeSyncTest {

    private fun project(): Path {
        val root = Files.createTempDirectory("kite-xcode")
        val fixture = checkNotNull(javaClass.getResource("/RealApp.pbxproj")).readText()
        root.resolve("iosApp/iosApp.xcodeproj").createDirectories().resolve("project.pbxproj").writeText(fixture)
        return root
    }

    private fun Path.pbxproj(): Path = resolve("iosApp/iosApp.xcodeproj/project.pbxproj")

    private fun sync(root: Path, targets: List<String> = listOf("Fixture App")) = syncXcodeProject(
        version = "4.1.1", rebuild = 1, buildNumber = null, appId = "com.acme.app", iosBundleId = null,
        locales = listOf("en", "pt-BR"), xcodeProject = "iosApp/iosApp.xcodeproj", targets = targets,
        projectRootDir = root.toString(),
    )

    private fun check(root: Path) = checkXcodeProject(
        version = "4.1.1", rebuild = 1, buildNumber = null, appId = "com.acme.app", iosBundleId = null,
        locales = listOf("en", "pt-BR"), xcodeProject = "iosApp/iosApp.xcodeproj", targets = listOf("Fixture App"),
        projectRootDir = root.toString(),
    )

    @Test
    fun `a sync writes the version, the build number and the bundle id`() {
        val root = project()
        sync(root)
        val text = root.pbxproj().readText()
        assertTrue("MARKETING_VERSION = 4.1.1;" in text, text)
        assertTrue("CURRENT_PROJECT_VERSION = 1004001011;" in text, text)
        assertTrue("PRODUCT_BUNDLE_IDENTIFIER = com.acme.app;" in text, text)
        assertTrue("\"pt-BR\"" in text || "pt-BR," in text, text)
    }

    @Test
    fun `a second sync changes no byte`() {
        val root = project()
        sync(root)
        val first = root.pbxproj().readText()
        sync(root)
        assertEquals(first, root.pbxproj().readText())
    }

    @Test
    fun `the check fails on an unsynced project and names the command`() {
        val root = project()
        val before = root.pbxproj().readText()
        val failure = assertFailsWith<IllegalStateException> { check(root) }
        assertTrue("./kotlin do kiteSyncIos" in failure.message.orEmpty(), failure.message)
        assertEquals(before, root.pbxproj().readText(), "the check must not write")
    }

    @Test
    fun `the check passes on a synced project`() {
        val root = project()
        sync(root)
        check(root)
    }

    @Test
    fun `an unknown target name fails and leaves the file alone`() {
        val root = project()
        val before = root.pbxproj().readText()
        assertFailsWith<IllegalStateException> { sync(root, targets = listOf("Widget")) }
        assertEquals(before, root.pbxproj().readText())
    }

    @Test
    fun `no xcode project means nothing to do`() {
        val root = Files.createTempDirectory("kite-none")
        syncXcodeProject("4.1.1", 1, null, "com.acme.app", null, emptyList(), null, emptyList(), root.toString())
        checkXcodeProject("4.1.1", 1, null, "com.acme.app", null, emptyList(), null, emptyList(), root.toString())
    }
}
