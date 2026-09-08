package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppIdScopeTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `the shared identifier reaches every platform unchanged`() {
        val e = ext()
        e.appId.set("com.example.jetzy")

        assertEquals("com.example.jetzy", e.androidApplicationId.get())
        assertEquals("com.example.jetzy", e.iosBundleId.get())
        assertEquals("com.example.jetzy", e.desktopBundleId.get())
    }

    @Test
    fun `a platform suffix is appended to the shared base`() {
        val e = ext()
        e.appId.set("com.example.jetzy")
        e.android.appId { suffix.set(".android") }
        e.ios.appId { suffix.set(".ios") }

        assertEquals("com.example.jetzy.android", e.androidApplicationId.get())
        assertEquals("com.example.jetzy.ios", e.iosBundleId.get())
        assertEquals("com.example.jetzy", e.desktopBundleId.get())
    }

    /**
     * A platform's own identifier is the whole answer for that platform. The suffix
     * belongs to the shared base, so it plays no part once the base is replaced.
     */
    @Test
    fun `an exact platform identifier ignores the suffix`() {
        val e = ext()
        e.appId.set("com.example.jetzy")
        e.ios.appId { suffix.set(".ios") }
        e.ios.appId.set("com.example.other")

        assertEquals("com.example.other", e.iosBundleId.get())
        assertEquals("com.example.jetzy", e.androidApplicationId.get())
    }

    @Test
    fun `a later shared identifier does not overwrite a platform one`() {
        val e = ext()
        e.ios.appId.set("com.example.other")
        e.appId.set("com.example.jetzy")

        assertEquals("com.example.other", e.iosBundleId.get())
        assertEquals("com.example.jetzy", e.androidApplicationId.get())
    }

    @Test
    fun `delivery off keeps the identifier readable`() {
        val e = ext()
        e.appId.set("com.example.jetzy")
        e.desktop.appId { enabled.set(false) }

        assertFalse(e.appIdFlowsTo(KitePlatform.DESKTOP).get())
        assertTrue(e.appIdFlowsTo(KitePlatform.ANDROID).get())
        assertEquals("com.example.jetzy", e.desktopBundleId.get())
    }

    @Test
    fun `the resolved identifier is readable per platform`() {
        val e = ext()
        e.appId.set("com.example.jetzy")
        e.android.appId { suffix.set(".android") }

        assertEquals("com.example.jetzy.android", e.appIdFor(KitePlatform.ANDROID).get())
        assertEquals("com.example.jetzy", e.appIdFor(KitePlatform.IOS).get())
    }
}
