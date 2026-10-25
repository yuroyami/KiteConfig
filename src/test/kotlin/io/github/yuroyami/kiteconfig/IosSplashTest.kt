package io.github.yuroyami.kiteconfig

import io.github.yuroyami.kiteconfig.core.KiteConfigException
import io.github.yuroyami.kiteconfig.core.OwnedOutputSafety
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.gradle.testfixtures.ProjectBuilder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
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
        assertTrue(json.contains("\"author\" : \"kiteconfig\""), json)
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

    // --- Installing into a real catalog -------------------------------------

    @TempDir
    lateinit var projectDir: File

    private fun logger() = ProjectBuilder.builder().withProjectDir(projectDir).build().logger

    private fun catalog(): File =
        projectDir.resolve("iosApp/iosApp/Assets.xcassets").also { it.mkdirs() }

    private fun install(assets: File, darkImage: BufferedImage?, backup: Boolean) =
        writeIosSplashAssets(
            assetsDir = assets,
            rendered = renderIosSplashAssets(Color.RED, null, art(), darkImage),
            projectRoot = projectDir,
            backupRoot = projectDir.resolve(".kiteconfig/recovery/ios-splash"),
            backup = backup,
            logger = logger(),
        )

    private fun handWrittenSplashImage(assets: File, bytes: ByteArray): File {
        val file = assets.resolve("$IOS_SPLASH_IMAGESET.imageset/$IOS_SPLASH_IMAGE_FILE")
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    /**
     * The installer used to write each file directly, so a splash image someone
     * made by hand was replaced with no recovery copy even with backups on.
     */
    @Test
    fun `an unowned splash image is refused when there is nowhere to recover it`() {
        val assets = catalog()
        val mine = byteArrayOf(1, 2, 3, 4)
        val handWritten = handWrittenSplashImage(assets, mine)

        val failure = assertThrows(KiteConfigException::class.java) {
            install(assets, darkImage = null, backup = false)
        }

        assertTrue(failure.message.orEmpty().contains("unowned"), failure.message.orEmpty())
        assertEquals(mine.toList(), handWritten.readBytes().toList())
    }

    @Test
    fun `an unowned splash image is recovered before the installer claims it`() {
        val assets = catalog()
        val mine = byteArrayOf(1, 2, 3, 4)
        val handWritten = handWrittenSplashImage(assets, mine)

        install(assets, darkImage = null, backup = true)

        assertFalse(handWritten.readBytes().toList() == mine.toList(), "the installer did not claim the path")
        val recovered = projectDir.resolve(".kiteconfig/recovery/ios-splash")
            .walkTopDown()
            .filter { it.isFile }
            .any { it.readBytes().toList() == mine.toList() }
        assertTrue(recovered, "the replaced file was not copied into the recovery area")
    }

    /**
     * A dark image installed once and then removed left its PNG behind after the
     * catalog stopped referring to it.
     */
    @Test
    fun `dropping the dark variant removes the image it owned`() {
        val assets = catalog()
        install(assets, darkImage = art(), backup = false)
        val darkFile = assets.resolve("$IOS_SPLASH_IMAGESET.imageset/$IOS_SPLASH_DARK_IMAGE_FILE")
        assertTrue(darkFile.isFile, "the dark image was never installed")

        install(assets, darkImage = null, backup = false)

        assertFalse(darkFile.exists(), "the dark image outlived the catalog entry that referenced it")
        assertTrue(assets.resolve("$IOS_SPLASH_IMAGESET.imageset/$IOS_SPLASH_IMAGE_FILE").isFile)
    }

    @Test
    fun `two catalogs in one project own their files separately`() {
        val first = iosSplashCatalogIdentity(projectDir, projectDir.resolve("appOne/Assets.xcassets"))
        val second = iosSplashCatalogIdentity(projectDir, projectDir.resolve("appTwo/Assets.xcassets"))

        assertFalse(first == second, "two catalogs shared one ownership id")
        assertTrue(first.startsWith("ios-splash-"), first)
    }

    @Test
    fun `the ownership receipt stays outside the catalog Xcode reads`() {
        val assets = catalog()
        install(assets, darkImage = null, backup = false)

        val strays = assets.walkTopDown().filter { it.isFile && it.path.contains(".kiteconfig") }.toList()
        assertTrue(strays.isEmpty(), "receipt files landed inside the asset catalog: $strays")
    }

    /**
     * Turning a feature off used to leave its files on disk and its keys in the
     * plist, so the app kept showing something the build script no longer
     * mentions.
     */
    @Test
    fun `a launch screen that is turned off is taken back out`() {
        val assets = catalog()
        install(assets, darkImage = null, backup = false)
        val installed = iosSplashOwnedPaths(assets, withDarkImage = false)
        assertTrue(installed.all { it.isFile }, "the splash was never installed")

        val identity = iosSplashCatalogIdentity(projectDir, assets)
        val removed = OwnedOutputSafety.removeInstalledFiles(
            installationRoot = assets,
            manifestFile = iosSplashManifestFile(assets, identity),
            projectRoot = projectDir,
            owner = identity,
        )

        assertEquals(installed.size, removed.size)
        assertTrue(installed.none { it.exists() }, "files the plugin owned were left behind")
        assertFalse(iosSplashManifestFile(assets, identity).exists(), "the receipt outlived its files")
    }

    @Test
    fun `a file edited by hand survives the removal and is reported`() {
        val assets = catalog()
        install(assets, darkImage = null, backup = false)
        val identity = iosSplashCatalogIdentity(projectDir, assets)
        val edited = assets.resolve("$IOS_SPLASH_COLORSET.colorset/Contents.json")
        edited.writeText("{ \"mine\": true }")

        val failure = assertThrows(KiteConfigException::class.java) {
            OwnedOutputSafety.removeInstalledFiles(
                installationRoot = assets,
                manifestFile = iosSplashManifestFile(assets, identity),
                projectRoot = projectDir,
                owner = identity,
            )
        }

        assertTrue(failure.message.orEmpty().contains("modified"), failure.message.orEmpty())
        assertEquals("{ \"mine\": true }", edited.readText())
    }

    @Test
    fun `removing when nothing was installed does nothing`() {
        val assets = catalog()
        val identity = iosSplashCatalogIdentity(projectDir, assets)

        val removed = OwnedOutputSafety.removeInstalledFiles(
            installationRoot = assets,
            manifestFile = iosSplashManifestFile(assets, identity),
            projectRoot = projectDir,
            owner = identity,
        )

        assertTrue(removed.isEmpty())
    }
}
