package io.github.yuroyami.kitessot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets

class IosSplashTest {

    private fun plist(body: String) = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        |<plist version="1.0">
        |<dict>
        |$body
        |</dict>
        |</plist>
    """.trimMargin()

    private val plainBody = "\t<key>CFBundleExecutable</key>\n\t<string>App</string>"

    /** A UILaunchScreen dictionary that points at something the project already chose. */
    private val foreignLaunchScreen = "\t<key>$IOS_LAUNCH_SCREEN_KEY</key>\n\t<dict>\n" +
        "\t\t<key>$IOS_LAUNCH_SCREEN_COLOR_KEY</key>\n\t\t<string>MyColor</string>\n\t</dict>"

    private fun art(size: Int = 4) = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)

    // --- Color set ----------------------------------------------------------

    @Test
    fun `a six digit hex becomes opaque srgb components`() {
        val json = iosSplashColorsetJson(parseIosSplashColor("#336699", "splash { backgroundColor }"), null)

        assertTrue(json.contains("\"color-space\" : \"srgb\""), json)
        assertTrue(json.contains("\"alpha\" : \"1.000\""), json)
        assertTrue(json.contains("\"red\" : \"0.200\""), json)
        assertTrue(json.contains("\"green\" : \"0.400\""), json)
        assertTrue(json.contains("\"blue\" : \"0.600\""), json)
        assertTrue(json.contains("\"author\" : \"kitessot\""), json)
    }

    @Test
    fun `an eight digit hex reads alpha first`() {
        val json = iosSplashColorsetJson(parseIosSplashColor("#80FF0000", "splash { backgroundColor }"), null)

        assertTrue(json.contains("\"alpha\" : \"0.502\""), json)
        assertTrue(json.contains("\"red\" : \"1.000\""), json)
        assertTrue(json.contains("\"green\" : \"0.000\""), json)
        assertTrue(json.contains("\"blue\" : \"0.000\""), json)
    }

    @Test
    fun `the color set carries a dark appearance only when a dark color is set`() {
        val light = parseIosSplashColor("#FFFFFF", "splash { backgroundColor }")
        val dark = parseIosSplashColor("#000000", "splash { dark { backgroundColor } }")

        val withoutDark = iosSplashColorsetJson(light, null)
        assertFalse(withoutDark.contains("appearances"), withoutDark)
        assertEquals(1, withoutDark.split("\"idiom\" : \"universal\"").size - 1, withoutDark)

        val withDark = iosSplashColorsetJson(light, dark)
        assertTrue(withDark.contains("\"appearance\" : \"luminosity\""), withDark)
        assertTrue(withDark.contains("\"value\" : \"dark\""), withDark)
        assertEquals(2, withDark.split("\"idiom\" : \"universal\"").size - 1, withDark)
    }

    @Test
    fun `a non hex plate color names the member that set it`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            parseIosSplashColor("rebeccapurple", "splash { backgroundColor }")
        }

        assertTrue(failure.message.orEmpty().contains("splash { backgroundColor }"), failure.message)
        assertTrue(failure.message.orEmpty().contains("#RRGGBB"), failure.message)
    }

    // --- Image set ----------------------------------------------------------

    @Test
    fun `the image set is one universal entry until a dark image exists`() {
        val single = iosSplashImagesetJson(withDarkImage = false)
        assertTrue(single.contains("\"filename\" : \"$IOS_SPLASH_IMAGE_FILE\""), single)
        assertTrue(single.contains("\"idiom\" : \"universal\""), single)
        assertFalse(single.contains("\"scale\""), single)
        assertFalse(single.contains("appearances"), single)
        assertFalse(single.contains(IOS_SPLASH_DARK_IMAGE_FILE), single)

        val paired = iosSplashImagesetJson(withDarkImage = true)
        assertTrue(paired.contains("\"filename\" : \"$IOS_SPLASH_DARK_IMAGE_FILE\""), paired)
        assertTrue(paired.contains("\"appearance\" : \"luminosity\""), paired)
    }

    @Test
    fun `rendering produces exactly the owned catalog files`() {
        val color = parseIosSplashColor("#101010", "splash { backgroundColor }")

        val light = renderIosSplashAssets(color, null, art(), null)
        assertEquals(iosSplashOwnedRelativePaths(withDarkImage = false), light.keys.toList())

        val dark = renderIosSplashAssets(color, color, art(), art())
        assertEquals(iosSplashOwnedRelativePaths(withDarkImage = true), dark.keys.toList())
        val imageset = String(
            dark.getValue("$IOS_SPLASH_IMAGESET.imageset/Contents.json"),
            StandardCharsets.UTF_8,
        )
        assertTrue(imageset.contains(IOS_SPLASH_DARK_IMAGE_FILE), imageset)
    }

    @Test
    fun `owned paths stay under the two catalog entries`() {
        val paths = iosSplashOwnedRelativePaths(withDarkImage = true)

        assertEquals(4, paths.size)
        assertTrue(
            paths.all {
                it.startsWith("$IOS_SPLASH_COLORSET.colorset/") || it.startsWith("$IOS_SPLASH_IMAGESET.imageset/")
            },
            paths.toString(),
        )
    }

    // --- UILaunchScreen -----------------------------------------------------

    @Test
    fun `a missing launch screen is inserted as a nested dictionary`() {
        val result = mergeIosSplashLaunchScreen(plist(plainBody))

        assertNotNull(result.text, result.errors.toString())
        assertEquals(listOf(IOS_LAUNCH_SCREEN_KEY), result.inserted)
        val text = result.text!!
        assertTrue(text.contains("<key>$IOS_LAUNCH_SCREEN_KEY</key>"), text)
        assertTrue(text.contains("<key>$IOS_LAUNCH_SCREEN_COLOR_KEY</key>"), text)
        assertTrue(text.contains("<string>$IOS_SPLASH_COLORSET</string>"), text)
        assertTrue(text.contains("<string>$IOS_SPLASH_IMAGESET</string>"), text)
        assertTrue(text.contains("<key>$IOS_LAUNCH_SCREEN_SAFE_AREA_KEY</key>"), text)
        assertTrue(text.contains("<true/>"), text)
    }

    @Test
    fun `the inserted launch screen is stable on a second pass`() {
        val once = mergeIosSplashLaunchScreen(plist(plainBody))
        assertNotNull(once.text, once.errors.toString())

        val twice = mergeIosSplashLaunchScreen(once.text!!)

        assertTrue(twice.errors.isEmpty(), twice.errors.toString())
        assertNull(twice.text)
    }

    @Test
    fun `the splash merge accepts the sanitized plist it is chained onto`() {
        val sanitized = sanitizeInfoPlist(
            plist(plainBody),
            listOf(PlistStringEntry("CFBundleName", "\$(PRODUCT_NAME)")),
            listOf(PlistBoolEntry("ITSAppUsesNonExemptEncryption", false)),
        )
        assertNotNull(sanitized.text, sanitized.errors.toString())

        val result = mergeIosSplashLaunchScreen(sanitized.text!!)

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertNotNull(result.text)
        val text = result.text!!
        assertTrue(text.contains("<key>CFBundleName</key>"), text)
        assertTrue(text.contains("<key>$IOS_LAUNCH_SCREEN_KEY</key>"), text)
    }

    @Test
    fun `an empty launch screen dictionary is filled in`() {
        val result = mergeIosSplashLaunchScreen(plist("\t<key>$IOS_LAUNCH_SCREEN_KEY</key>\n\t<dict/>"))

        assertNotNull(result.text, result.errors.toString())
        assertEquals(
            listOf(
                "$IOS_LAUNCH_SCREEN_KEY.$IOS_LAUNCH_SCREEN_COLOR_KEY",
                "$IOS_LAUNCH_SCREEN_KEY.$IOS_LAUNCH_SCREEN_IMAGE_KEY",
                "$IOS_LAUNCH_SCREEN_KEY.$IOS_LAUNCH_SCREEN_SAFE_AREA_KEY",
            ),
            result.inserted,
        )
        val text = result.text!!
        assertTrue(text.contains("<string>$IOS_SPLASH_COLORSET</string>"), text)
    }

    @Test
    fun `an already correct launch screen is left alone`() {
        val body = "\t<key>$IOS_LAUNCH_SCREEN_KEY</key>\n\t<dict>\n" +
            "\t\t<key>$IOS_LAUNCH_SCREEN_COLOR_KEY</key>\n\t\t<string>$IOS_SPLASH_COLORSET</string>\n" +
            "\t\t<key>$IOS_LAUNCH_SCREEN_IMAGE_KEY</key>\n\t\t<string>$IOS_SPLASH_IMAGESET</string>\n" +
            "\t\t<key>$IOS_LAUNCH_SCREEN_SAFE_AREA_KEY</key>\n\t\t<true/>\n\t</dict>"

        val result = mergeIosSplashLaunchScreen(plist(body))

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertNull(result.text)
    }

    @Test
    fun `the default policy fails on a launch screen that points elsewhere`() {
        val result = mergeIosSplashLaunchScreen(plist(foreignLaunchScreen))

        assertNull(result.text)
        assertTrue(
            result.errors.any { it.contains("$IOS_LAUNCH_SCREEN_KEY.$IOS_LAUNCH_SCREEN_COLOR_KEY") },
            result.errors.toString(),
        )
        assertTrue(result.errors.any { it.contains("MyColor") }, result.errors.toString())
    }

    @Test
    fun `REPLACE overwrites the differing entry and adds the missing ones`() {
        val result = mergeIosSplashLaunchScreen(
            plist(foreignLaunchScreen),
            conflictPolicy = PlistConflictPolicy.REPLACE,
        )

        assertNotNull(result.text, result.errors.toString())
        assertEquals(listOf("$IOS_LAUNCH_SCREEN_KEY.$IOS_LAUNCH_SCREEN_COLOR_KEY"), result.overwritten)
        val text = result.text!!
        assertFalse(text.contains("MyColor"), text)
        assertTrue(text.contains("<string>$IOS_SPLASH_COLORSET</string>"), text)
        assertTrue(text.contains("<string>$IOS_SPLASH_IMAGESET</string>"), text)
    }

    @Test
    fun `KEEP warns and preserves the value the project already had`() {
        val result = mergeIosSplashLaunchScreen(
            plist(foreignLaunchScreen),
            conflictPolicy = PlistConflictPolicy.KEEP,
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertNotNull(result.text, result.warnings.toString())
        assertTrue(result.warnings.any { it.contains("conflictPolicy=KEEP") }, result.warnings.toString())
        val text = result.text!!
        assertTrue(text.contains("<string>MyColor</string>"), text)
        assertTrue(text.contains("<string>$IOS_SPLASH_IMAGESET</string>"), text)
    }

    @Test
    fun `a launch screen that is not a dictionary follows the conflict policy`() {
        val body = "\t<key>$IOS_LAUNCH_SCREEN_KEY</key>\n\t<string>Legacy</string>"

        val failed = mergeIosSplashLaunchScreen(plist(body))
        assertNull(failed.text)
        assertTrue(failed.errors.any { it.contains(IOS_LAUNCH_SCREEN_KEY) }, failed.errors.toString())

        val replaced = mergeIosSplashLaunchScreen(plist(body), conflictPolicy = PlistConflictPolicy.REPLACE)
        assertNotNull(replaced.text, replaced.errors.toString())
        val text = replaced.text!!
        assertFalse(text.contains("Legacy"), text)
        assertTrue(text.contains("<key>$IOS_LAUNCH_SCREEN_IMAGE_KEY</key>"), text)
    }

    @Test
    fun `an existing storyboard is warned about because iOS prefers it`() {
        val body = "$plainBody\n\t<key>UILaunchStoryboardName</key>\n\t<string>LaunchScreen</string>"

        val result = mergeIosSplashLaunchScreen(plist(body))

        assertNotNull(result.text, result.errors.toString())
        assertTrue(result.warnings.any { it.contains("UILaunchStoryboardName") }, result.warnings.toString())
    }

    @Test
    fun `a binary plist is refused before any parsing`() {
        val result = mergeIosSplashLaunchScreen("bplist00  ")

        assertNull(result.text)
        assertTrue(result.errors.any { it.contains("not an XML property list") }, result.errors.toString())
    }

    @Test
    fun `a duplicate root key aborts the whole splash plan`() {
        val body = "\t<key>CFBundleName</key>\n\t<string>A</string>\n\t<key>CFBundleName</key>\n\t<string>B</string>"

        val result = mergeIosSplashLaunchScreen(plist(body))

        assertNull(result.text)
        assertTrue(result.errors.any { it.contains("duplicate key") }, result.errors.toString())
    }
}
