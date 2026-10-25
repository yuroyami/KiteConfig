package io.github.yuroyami.kiteconfig

import io.github.yuroyami.kiteconfig.core.IOS_LAUNCH_SCREEN_KEY
import io.github.yuroyami.kiteconfig.core.IOS_SPLASH_COLORSET
import io.github.yuroyami.kiteconfig.core.IOS_SPLASH_IMAGESET
import io.github.yuroyami.kiteconfig.core.IOS_SPLASH_IMAGE_FILE
import io.github.yuroyami.kiteconfig.core.PlannedTextChange
import io.github.yuroyami.kiteconfig.core.Utf8FileSnapshot
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.gradle.testfixtures.ProjectBuilder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class SyncIosConfigTaskTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `Swift discovery rejects a source beyond the configured depth`() {
        val root = directory.resolve("iosApp")
        Files.createDirectories(root.resolve("level-one/level-two"))
        Files.writeString(root.resolve("level-one/Allowed.swift"), "import shared\n")
        Files.writeString(root.resolve("level-one/level-two/TooDeep.swift"), "import shared\n")

        val failure = assertThrows(GradleException::class.java) {
            discoverIosSwiftFiles(root, maximumDepth = 2, maximumEntries = 10)
        }

        assertTrue(failure.message.orEmpty().contains("maximum depth 2"), failure.message)
        assertTrue(failure.message.orEmpty().contains("no files were changed"), failure.message)
    }

    @Test
    fun `Swift discovery bounds directories and non-Swift entries too`() {
        val root = directory.resolve("iosApp")
        Files.createDirectories(root.resolve("Sources"))
        Files.writeString(root.resolve("README.txt"), "not Swift\n")
        Files.writeString(root.resolve("Sources/Source.swift"), "import shared\n")

        val failure = assertThrows(GradleException::class.java) {
            discoverIosSwiftFiles(root, maximumDepth = 2, maximumEntries = 2)
        }

        assertTrue(failure.message.orEmpty().contains("maximum of 2 traversal entries"), failure.message)
        assertTrue(failure.message.orEmpty().contains("no files were changed"), failure.message)
    }

    @Test
    fun `Swift discovery does not follow a directory symlink`() {
        val root = directory.resolve("iosApp")
        val outside = directory.resolve("outside")
        Files.createDirectories(root)
        Files.createDirectories(outside)
        Files.writeString(root.resolve("Local.swift"), "import shared\n")
        Files.writeString(outside.resolve("External.swift"), "import shared\n")
        val linkCreated = runCatching {
            Files.createSymbolicLink(root.resolve("linked-sources"), outside)
        }.isSuccess
        assumeTrue(linkCreated, "symbolic links are unavailable in this test environment")

        val discovered = discoverIosSwiftFiles(root, maximumDepth = 3, maximumEntries = 10)

        assertEquals(listOf("Local.swift"), discovered.map { it.fileName.toString() })
    }

    @Test
    fun `text rewrite budget counts snapshots and outputs cumulatively`() {
        val file = directory.resolve("Source.swift").toFile()
        val original = "123456".toByteArray(StandardCharsets.UTF_8)
        val snapshot = Utf8FileSnapshot(file.absoluteFile.normalize(), original, "123456")
        val exactBudget = IosTextRewriteBudget(maximumBytes = 10)
        exactBudget.recordSnapshot(snapshot, "Swift source")
        exactBudget.recordOutput("1234", "Swift source")
        exactBudget.verifyBeforeCommit(
            listOf(PlannedTextChange(file, original, "123456", "1234", "Swift source")),
        )

        val overflowingBudget = IosTextRewriteBudget(maximumBytes = 10)
        overflowingBudget.recordSnapshot(snapshot, "Swift source")
        val failure = assertThrows(GradleException::class.java) {
            overflowingBudget.recordOutput("12345", "Swift source")
        }

        assertTrue(failure.message.orEmpty().contains("cumulative 10-byte snapshot/output budget"), failure.message)
        assertTrue(failure.message.orEmpty().contains("no files were changed"), failure.message)
    }

    // --- Splash ordering ----------------------------------------------------

    private fun iosFixture(): File {
        val root = directory.toFile()
        val appDir = root.resolve("iosApp/iosApp")
        appDir.resolve("Assets.xcassets/AppIcon.appiconset").mkdirs()
        appDir.resolve("Info.plist").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
            	<key>CFBundleExecutable</key>
            	<string>App</string>
            </dict>
            </plist>
            """.trimIndent() + "\n",
        )
        root.resolve("iosApp/iosApp.xcodeproj").mkdirs()
        root.resolve("iosApp/iosApp.xcodeproj/project.pbxproj").writeText(
            """
            {
                objects = {
                    AA00000000000000000000AA = {
                        isa = PBXNativeTarget;
                        name = App;
                        buildConfigurationList = AA00000000000000000000A1;
                        productType = "com.apple.product-type.application";
                    };
                    AA00000000000000000000A1 = {
                        isa = XCConfigurationList;
                        buildConfigurations = ( AA00000000000000000000A2, );
                    };
                    AA00000000000000000000A2 = {
                        isa = XCBuildConfiguration;
                        buildSettings = { PRODUCT_NAME = Old; };
                    };
                    EE00000000000000000000EE = {
                        isa = PBXProject;
                        knownRegions = ( Base, en, );
                    };
                };
                rootObject = EE00000000000000000000EE;
            }
            """.trimIndent() + "\n",
        )
        return root
    }

    private fun splashTask(root: File, splashArt: File): SyncIosConfigTask {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.register("syncIos", SyncIosConfigTask::class.java).get()
        task.projectRootDir.set(root)
        task.pbxprojFile.set(root.resolve("iosApp/iosApp.xcodeproj/project.pbxproj"))
        task.infoPlistFile.set(root.resolve("iosApp/iosApp/Info.plist"))
        task.iosAppDir.set(root.resolve("iosApp"))
        task.appiconsetDir.set(root.resolve("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"))
        task.appName.set("New")
        task.locales.set(emptyList())
        task.targetNames.set(emptyList())
        task.propagateVersion.set(false)
        task.propagateAppName.set(true)
        task.propagateBundleId.set(false)
        task.propagateLocaleList.set(false)
        task.propagateSharedModule.set(false)
        task.propagateLogo.set(false)
        task.sanitizeSourcePlist.set(true)
        task.plistConflictPolicy.set(PlistConflictPolicy.FAIL)
        task.dryRun.set(false)
        task.backup.set(true)
        task.splashEnabled.set(true)
        task.splashColor.set("#101014")
        task.splashImage.set(splashArt)
        return task
    }

    /**
     * The task used to commit the plist and project file and only then decode the
     * splash art, so unreadable art left the plist naming an asset that was never
     * installed. Nothing may change until every splash byte has been rendered.
     */
    @Test
    fun `art that cannot be decoded leaves the project files untouched`() {
        val root = iosFixture()
        val corrupt = root.resolve("art/splash.png")
        corrupt.parentFile.mkdirs()
        // Large enough to clear the size floor, so the failure is the format check.
        corrupt.writeBytes(ByteArray(512) { 0x41 })
        val plist = root.resolve("iosApp/iosApp/Info.plist")
        val pbxproj = root.resolve("iosApp/iosApp.xcodeproj/project.pbxproj")
        val plistBefore = plist.readText()
        val pbxBefore = pbxproj.readText()

        val failure = assertThrows(GradleException::class.java) { splashTask(root, corrupt).sync() }

        assertEquals(plistBefore, plist.readText(), "the plist was committed before the art was checked")
        assertEquals(pbxBefore, pbxproj.readText(), "the project file was committed before the art was checked")
        assertTrue(failure.message.orEmpty().contains("PNG"), failure.message.orEmpty())
        assertTrue(
            root.resolve("iosApp/iosApp/Assets.xcassets/KiteSplashImage.imageset").listFiles().isNullOrEmpty(),
        )
    }

    @Test
    fun `readable art commits the plist and installs the catalog together`() {
        val root = iosFixture()
        val good = root.resolve("art/splash.png")
        good.parentFile.mkdirs()
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply { color = Color.RED; fillRect(0, 0, 8, 8); dispose() }
        ImageIO.write(image, "PNG", good)

        splashTask(root, good).sync()

        val plist = root.resolve("iosApp/iosApp/Info.plist").readText()
        assertTrue(plist.contains(IOS_LAUNCH_SCREEN_KEY), plist)
        val assets = root.resolve("iosApp/iosApp/Assets.xcassets")
        assertTrue(assets.resolve("$IOS_SPLASH_IMAGESET.imageset/$IOS_SPLASH_IMAGE_FILE").isFile)
        assertTrue(assets.resolve("$IOS_SPLASH_COLORSET.colorset/Contents.json").isFile)
    }
}
