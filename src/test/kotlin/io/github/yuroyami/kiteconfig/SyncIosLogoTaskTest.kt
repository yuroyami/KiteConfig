package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class SyncIosLogoTaskTest {

    @TempDir
    lateinit var projectDir: File

    private fun art(name: String): File {
        val file = projectDir.resolve("art/$name")
        file.parentFile.mkdirs()
        ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "PNG", file)
        return file
    }

    private fun debugCatalog(): File =
        projectDir.resolve("iosApp/iosApp/Assets.xcassets/AppIcon-Debug.appiconset")

    private fun receipt(): File =
        projectDir.resolve("iosApp/iosApp/.kiteconfig/${SyncIosLogoTask.catalogIdentity(projectDir, debugCatalog())}/owned-files-v1")

    /** The debug installer as the plugin registers it: same task class, a sibling catalog, an install switch. */
    private fun task(install: Boolean): SyncIosLogoTask {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val task = project.tasks.register("debugLogo", SyncIosLogoTask::class.java).get()
        task.projectRootDir.set(projectDir)
        task.debugVariant.set(true)
        task.install.set(install)
        task.foregroundPng.set(art("fg.png"))
        task.backgroundColorHex.set("#B00020")
        task.appiconsetDir.set(debugCatalog())
        task.dryRun.set(false)
        task.backup.set(false)
        return task
    }

    @Test
    fun `installing writes the catalog and its receipt beside the release catalog`() {
        task(install = true).sync()

        assertTrue(debugCatalog().resolve("AppIcon-1024.png").isFile)
        assertTrue(debugCatalog().resolve("Contents.json").isFile)
        assertTrue(receipt().isFile, "receipt missing")
    }

    @Test
    fun `turning the debug icon off takes the catalog back out`() {
        task(install = true).sync()
        assertTrue(debugCatalog().isDirectory)

        task(install = false).sync()

        assertFalse(debugCatalog().exists(), "the debug catalog outlived its configuration")
        assertFalse(receipt().exists(), "the receipt outlived its files")
    }

    @Test
    fun `removing when nothing was installed does nothing`() {
        task(install = false).sync()

        assertFalse(debugCatalog().exists())
    }

    @Test
    fun `a dry run removes nothing`() {
        task(install = true).sync()
        val task = task(install = false)
        task.dryRun.set(true)

        task.sync()

        assertTrue(debugCatalog().resolve("AppIcon-1024.png").isFile)
    }

    /** A file the user put in the catalog is theirs, so the folder stays and so does the file. */
    @Test
    fun `removal leaves files it does not own`() {
        task(install = true).sync()
        val mine = debugCatalog().resolve("notes.txt").apply { writeText("mine") }

        task(install = false).sync()

        assertTrue(mine.isFile, "a file the plugin never wrote was removed")
        assertFalse(debugCatalog().resolve("AppIcon-1024.png").exists())
    }

    @Test
    fun `messages name the debug member`() {
        val task = task(install = true)
        task.foregroundPng.set(projectDir.resolve("art/missing.png"))

        val failure = assertThrows(GradleException::class.java) { task.sync() }

        assertTrue(failure.message.orEmpty().contains("debug { logo { foreground } }"), failure.message)
        assertFalse(debugCatalog().exists())
    }

    @Test
    fun `same-named iOS catalogs receive distinct ownership namespaces`() {
        val project = projectDir.resolve("project").apply { mkdirs() }
        val first = project.resolve("ios/App/Assets.xcassets/AppIcon.appiconset")
        val second = project.resolve("ios/Widget/Assets.xcassets/AppIcon.appiconset")

        val firstIdentity = SyncIosLogoTask.catalogIdentity(project, first)
        val secondIdentity = SyncIosLogoTask.catalogIdentity(project, second)

        assertTrue(firstIdentity != secondIdentity)
        assertTrue(firstIdentity.startsWith("ios-appicon-") && secondIdentity.startsWith("ios-appicon-"))
    }
}
