package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class AndroidDebugIconsGenTest {

    @TempDir
    lateinit var directory: File

    /** A real PNG outside the generated output tree: a centred square, transparent corners. */
    private fun mark(name: String, color: Color = Color.WHITE, size: Int = 512): File {
        val file = directory.resolve("art/$name")
        file.parentFile.mkdirs()
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            this.color = color
            fillRect(size / 4, size / 4, size / 2, size / 2)
            dispose()
        }
        ImageIO.write(image, "PNG", file)
        return file
    }

    /** [into] keeps each case in its own generated tree, so no case can see another's output. */
    private fun iconsTask(into: String = "debug-icons-res"): GenerateAndroidDebugIconsTask {
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        val task = project.tasks.register("debugIcons", GenerateAndroidDebugIconsTask::class.java).get()
        task.outputDir.set(project.layout.buildDirectory.dir("generated/kiteconfig/$into"))
        task.safeZoneRatio.set(66.0 / 108.0)
        return task
    }

    private fun GenerateAndroidDebugIconsTask.output(relative: String): File =
        outputDir.get().asFile.resolve(relative)

    @Test
    fun `the generated tree has the same files as the release installer`() {
        val task = iconsTask()
        task.foreground.set(mark("fg.png"))
        task.backgroundColor.set("#B00020")
        task.emitMonochrome.set(true)

        task.generate()

        SyncAndroidLogoTask.OUTPUT_RELATIVE_PATHS.forEach { relative ->
            assertTrue(task.output(relative).isFile, "missing $relative")
        }
        val legacy = ImageIO.read(task.output("mipmap-xxxhdpi/ic_launcher.png"))
        assertEquals(192, legacy.width)
        assertEquals(0xB00020, legacy.getRGB(2, 2) and 0xFFFFFF)
    }

    @Test
    fun `monochrome wrappers appear only when asked for`() {
        val task = iconsTask(into = "debug-icons-res-plain")
        task.foreground.set(mark("fg.png"))
        task.backgroundColor.set("#B00020")

        task.generate()

        assertFalse(task.output("mipmap-anydpi-v33/ic_launcher.xml").exists())
        assertTrue(task.output("mipmap-anydpi-v26/ic_launcher.xml").isFile)
    }

    @Test
    fun `an overlay reaches every rendered icon`() {
        val task = iconsTask(into = "debug-icons-res-overlay")
        task.foreground.set(mark("fg.png"))
        task.backgroundColor.set("#102A43")
        task.overlay.set(mark("ribbon.png", color = Color.RED))

        task.generate()

        val legacy = ImageIO.read(task.output("mipmap-xxxhdpi/ic_launcher.png"))
        // The mark sits in the middle of the canvas, and the overlay paints it red.
        assertEquals(0xFF0000, legacy.getRGB(96, 96) and 0xFFFFFF)
    }

    @Test
    fun `the same art renders the same bytes as the release installer`() {
        val fg = ImageIO.read(mark("fg.png"))
        val bg = solidColorImage(432, parseLogoBackgroundColor("#102A43"))

        val first = renderAndroidLauncherIcons(fg, bg, 66.0 / 108.0, emitMonochrome = true)
        val second = renderAndroidLauncherIcons(fg, bg, 66.0 / 108.0, emitMonochrome = true)

        assertEquals(SyncAndroidLogoTask.OUTPUT_RELATIVE_PATHS.toSet(), first.keys)
        first.forEach { (path, bytes) -> assertTrue(bytes.contentEquals(second.getValue(path)), path) }
    }

    @Test
    fun `a missing background names the debug member`() {
        val task = iconsTask(into = "debug-icons-res-nobg")
        task.foreground.set(mark("fg.png"))

        val failure = assertThrows(GradleException::class.java) { task.generate() }

        assertTrue(failure.message.orEmpty().contains("debug { logo { background } }"), failure.message)
    }
}
