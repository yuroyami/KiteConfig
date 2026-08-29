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

class AndroidSplashGenTest {

    @TempDir
    lateinit var directory: File

    /** A real PNG outside the generated output tree. */
    private fun art(name: String, size: Int = 512): File {
        val file = directory.resolve("art/$name")
        file.parentFile.mkdirs()
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, size, size)
        graphics.dispose()
        ImageIO.write(image, "PNG", file)
        return file
    }

    /** [into] keeps each case in its own generated tree, so no case can see another's output. */
    private fun splashTask(theme: String? = "AppTheme", into: String = "splash-res"): GenerateAndroidSplashTask {
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        val task = project.tasks.register("splash", GenerateAndroidSplashTask::class.java).get()
        task.outputDir.set(project.layout.buildDirectory.dir("generated/kitessot/$into"))
        if (theme != null) task.theme.set(theme)
        return task
    }

    private fun GenerateAndroidSplashTask.output(relative: String): File =
        outputDir.get().asFile.resolve(relative)

    @Test
    fun `pre-12 gets a pure alias and Android 12 gets the splash attributes`() {
        val task = splashTask()
        task.image.set(art("splash.png"))
        task.backgroundColor.set("#101014")

        task.generate()

        val alias = task.output("values/kitessot_splash.xml").readText()
        assertTrue(alias.contains("<style name=\"KiteSplash\" parent=\"AppTheme\"/>"), alias)
        assertFalse(alias.contains("windowSplashScreen"), alias)

        val twelve = task.output("values-v31/kitessot_splash.xml").readText()
        assertTrue(twelve.contains("<color name=\"kite_splash_bg\">#101014</color>"), twelve)
        assertTrue(twelve.contains("<style name=\"KiteSplash\" parent=\"AppTheme\">"), twelve)
        assertTrue(
            twelve.contains("<item name=\"android:windowSplashScreenBackground\">@color/kite_splash_bg</item>"),
            twelve,
        )
        assertTrue(
            twelve.contains("<item name=\"android:windowSplashScreenAnimatedIcon\">@drawable/kite_splash_icon</item>"),
            twelve,
        )
    }

    @Test
    fun `every density bucket gets a splash icon on the 288dp canvas`() {
        val task = splashTask()
        task.image.set(art("splash.png"))
        task.backgroundColor.set("#FFFFFF")

        task.generate()

        listOf("mdpi" to 288, "hdpi" to 432, "xhdpi" to 576, "xxhdpi" to 864, "xxxhdpi" to 1152)
            .forEach { (density, side) ->
                val png = task.output("drawable-$density/kite_splash_icon.png")
                assertTrue(png.isFile, "missing $density icon")
                assertTrue(png.length() > 0, "empty $density icon")
                val decoded = ImageIO.read(png)
                assertEquals(side, decoded.width, density)
                assertEquals(side, decoded.height, density)
            }
    }

    @Test
    fun `night resources appear only with a dark variant`() {
        val light = splashTask()
        light.image.set(art("splash.png"))
        light.backgroundColor.set("#101014")
        light.generate()

        assertFalse(light.output("values-night-v31/kitessot_splash.xml").exists())
        assertFalse(light.output("drawable-night-hdpi/kite_splash_icon.png").exists())

        val dark = splashTask(into = "splash-res-dark")
        dark.image.set(art("splash.png"))
        dark.backgroundColor.set("#101014")
        dark.darkBackgroundColor.set("#000000")
        dark.darkImage.set(art("splash_dark.png"))
        dark.generate()

        val night = dark.output("values-night-v31/kitessot_splash.xml").readText()
        assertTrue(night.contains("<color name=\"kite_splash_bg\">#000000</color>"), night)
        assertFalse(night.contains("<style"), night)
        assertTrue(dark.output("drawable-night-xxxhdpi/kite_splash_icon.png").isFile)
    }

    @Test
    fun `missing art names both splash and logo`() {
        val task = splashTask()

        val failure = assertThrows(GradleException::class.java) { task.generate() }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("splash { image }"), message)
        assertTrue(message.contains("logo { foreground }"), message)
    }

    @Test
    fun `a missing theme names the android corner`() {
        val task = splashTask(theme = null)
        task.image.set(art("splash.png"))

        val failure = assertThrows(GradleException::class.java) { task.generate() }

        assertTrue(failure.message.orEmpty().contains("splash { android { theme"), failure.message)
    }

    @Test
    fun `an invalid color names the splash member it came from`() {
        val task = splashTask()
        task.image.set(art("splash.png"))
        task.backgroundColor.set("red")

        val failure = assertThrows(GradleException::class.java) { task.generate() }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("splash { backgroundColor }"), message)
        assertTrue(message.contains("#RRGGBB"), message)
    }

}
