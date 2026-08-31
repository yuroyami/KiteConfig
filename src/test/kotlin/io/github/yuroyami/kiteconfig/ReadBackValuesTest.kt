package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The read-only view must report exactly what the engine resolved, so these
 * assert through the [KiteConfigValues] type rather than the extension.
 */
class ReadBackValuesTest {

    private fun values(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `the version family reads back through the interface`() {
        val e = values()
        e.version.set("1.4.0")
        val read: KiteConfigValues = e

        assertEquals("1.4.0", read.version.get())
        assertEquals(1001004000, read.versionCode.get())
        assertEquals("1001004000", read.iosBuildNumber.get())
        assertEquals("1.4.0", read.iosMarketingVersion.get())
        assertEquals("1001004000", read.desktopBuildNumber.get())
    }

    @Test
    fun `identity reads back with its platform suffixes applied`() {
        val e = values()
        e.appName.set("Syncplay")
        e.id("com.example.app") {
            android { suffix.set(".droid") }
        }
        val read: KiteConfigValues = e

        assertEquals("Syncplay", read.appName.get())
        assertEquals("Syncplay", read.appNameFor(KitePlatform.ANDROID).get())
        assertEquals("com.example.app", read.id.get())
        assertEquals("com.example.app.droid", read.androidApplicationId.get())
        assertEquals("com.example.app", read.iosBundleId.get())
        assertEquals("com.example.app", read.desktopBundleId.get())
    }

    @Test
    fun `build values read back including the sdk levels`() {
        val e = values()
        e.jvmTarget.set(17)
        e.locales { pinned.set(listOf("en", "fr")) }
        e.android { sdk(min = 24, target = 35, compile = 35) }
        val read: KiteConfigValues = e

        assertEquals(listOf("en", "fr"), read.canonicalLocales.get())
        assertEquals(17, read.jvmTarget.get())
        assertEquals(24, read.minSdk.get())
        assertEquals(35, read.targetSdk.get())
        assertEquals(35, read.compileSdk.get())
    }
}
