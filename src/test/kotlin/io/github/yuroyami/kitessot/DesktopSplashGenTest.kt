package io.github.yuroyami.kitessot

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

class DesktopSplashGenTest {

    @TempDir
    lateinit var directory: File

    private fun task(): GenerateDesktopSplashTask {
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        return project.tasks.create("kiteInternalDesktopSplash", GenerateDesktopSplashTask::class.java).apply {
            outputDir.set(project.layout.buildDirectory.dir("generated/kitessot/desktop-splash"))
        }
    }

    /** A solid red PNG, so every interior pixel of the scaled art is exactly red. */
    private fun art(side: Int): File {
        val file = directory.resolve("art.png")
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
            .withGraphics { color = Color.RED; fillRect(0, 0, side, side) }
        ImageIO.write(image, "PNG", file)
        return file
    }

    private fun GenerateDesktopSplashTask.splashPng(): BufferedImage =
        ImageIO.read(outputDir.get().asFile.resolve(DESKTOP_SPLASH_RESOURCE_PATH))

    @Test
    fun `the composed splash has the default canvas size and the plate in its corners`() {
        val task = task()
        task.image.set(art(40))
        task.backgroundColor.set("#102030")

        task.generate()

        val png = task.splashPng()
        assertEquals(800, png.width)
        assertEquals(480, png.height)
        assertEquals(Color(0x10, 0x20, 0x30).rgb, png.getRGB(0, 0))
        assertEquals(Color(0x10, 0x20, 0x30).rgb, png.getRGB(799, 479))
    }

    @Test
    fun `the art is centered and never taller than half the canvas`() {
        val task = task()
        task.image.set(art(10))
        task.backgroundColor.set("#000000")
        task.canvasWidth.set(100)
        task.canvasHeight.set(100)

        task.generate()

        // A square logo fills the 50 pixel band, so it covers x and y 25 to 75.
        val png = task.splashPng()
        // Resampled pixels are compared loosely; the plate is a plain fill, so it is exact.
        val centre = Color(png.getRGB(50, 50))
        assertTrue(centre.red > 200 && centre.green < 40 && centre.blue < 40, "no art at the centre: $centre")
        assertEquals(Color.BLACK.rgb, png.getRGB(50, 10))
        assertEquals(Color.BLACK.rgb, png.getRGB(50, 90))
        assertEquals(Color.BLACK.rgb, png.getRGB(10, 50))
    }

    @Test
    fun `an unset color falls back to a white plate`() {
        val task = task()
        task.image.set(art(8))

        task.generate()

        assertEquals(Color.WHITE.rgb, task.splashPng().getRGB(0, 0))
    }

    @Test
    fun `missing art names both the splash and the logo block`() {
        val failure = assertThrows(GradleException::class.java) { task().generate() }

        assertTrue(failure.message.orEmpty().contains("splash { image }"), failure.message)
        assertTrue(failure.message.orEmpty().contains("logo { foreground }"), failure.message)
    }

}
