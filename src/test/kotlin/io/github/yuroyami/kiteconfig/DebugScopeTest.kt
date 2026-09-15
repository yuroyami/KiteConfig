package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DebugScopeTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    private fun KiteConfigExtension.withIdentity() = apply {
        appName.set("Jetzy")
        appId.set("com.example.jetzy")
    }

    private fun KiteConfigExtension.withArt() = apply {
        logo {
            foreground.set(File("art/logo.png"))
            background.set(color("#102A43"))
        }
    }

    @Test
    fun `an empty block is not a request`() {
        val e = ext().withIdentity()
        e.debug { }
        e.android.debug { }
        e.ios.debug { }

        assertFalse(e.debugAppIdFlowsTo(KitePlatform.ANDROID).get())
        assertFalse(e.debugAppNameFlowsTo(KitePlatform.IOS).get())
        assertFalse(e.debugLogoFlowsTo(KitePlatform.ANDROID).get())
        assertEquals("com.example.jetzy", e.debugAppIdFor(KitePlatform.ANDROID).get())
        assertEquals("Jetzy", e.debugAppNameFor(KitePlatform.IOS).get())
    }

    @Test
    fun `a suffix is appended to each platform's release identifier`() {
        val e = ext().withIdentity()
        e.android.appId { suffix.set(".android") }
        e.debug { appId { suffix.set(".debug") } }

        assertEquals("com.example.jetzy.android.debug", e.debugAppIdFor(KitePlatform.ANDROID).get())
        assertEquals("com.example.jetzy.debug", e.debugAppIdFor(KitePlatform.IOS).get())
        assertTrue(e.debugAppIdFlowsTo(KitePlatform.ANDROID).get())
        assertFalse(e.debugAppIdIsExactFor(KitePlatform.ANDROID).get())
    }

    @Test
    fun `a platform's debug corner wins over the shared debug block`() {
        val e = ext().withIdentity()
        e.debug {
            appId { suffix.set(".debug") }
            appName.set("Jetzy Dev")
        }
        e.android.debug { appId { suffix.set(".dev") } }
        e.ios.debug { appName.set("Jetzy (Xcode)") }

        assertEquals("com.example.jetzy.dev", e.debugAppIdFor(KitePlatform.ANDROID).get())
        assertEquals("com.example.jetzy.debug", e.debugAppIdFor(KitePlatform.IOS).get())
        assertEquals("Jetzy Dev", e.debugAppNameFor(KitePlatform.ANDROID).get())
        assertEquals("Jetzy (Xcode)", e.debugAppNameFor(KitePlatform.IOS).get())
    }

    @Test
    fun `an exact debug identifier ignores every suffix`() {
        val e = ext().withIdentity()
        e.debug { appId { suffix.set(".debug") } }
        e.ios.debug { appId.set("com.example.other") }

        assertEquals("com.example.other", e.debugAppIdFor(KitePlatform.IOS).get())
        assertTrue(e.debugAppIdIsExactFor(KitePlatform.IOS).get())
        assertEquals("com.example.jetzy.debug", e.debugAppIdFor(KitePlatform.ANDROID).get())
    }

    @Test
    fun `delivery switches stop the write and keep the value readable`() {
        val e = ext().withIdentity()
        e.debug {
            appId { suffix.set(".debug") }
            appName.set("Jetzy Dev")
        }
        e.ios.debug {
            appId { enabled.set(false) }
            appName { enabled.set(false) }
        }

        assertFalse(e.debugAppIdFlowsTo(KitePlatform.IOS).get())
        assertFalse(e.debugAppNameFlowsTo(KitePlatform.IOS).get())
        assertTrue(e.debugAppIdFlowsTo(KitePlatform.ANDROID).get())
        assertEquals("com.example.jetzy.debug", e.debugAppIdFor(KitePlatform.IOS).get())
    }

    @Test
    fun `desktop has no debug build`() {
        val e = ext().withIdentity()
        e.debug {
            appId { suffix.set(".debug") }
            appName.set("Jetzy Dev")
        }

        assertFalse(e.debugAppIdFlowsTo(KitePlatform.DESKTOP).get())
        assertFalse(e.debugAppNameFlowsTo(KitePlatform.DESKTOP).get())
        assertEquals("com.example.jetzy", e.debugAppIdFor(KitePlatform.DESKTOP).get())
        assertEquals("Jetzy", e.debugAppNameFor(KitePlatform.DESKTOP).get())
    }

    @Test
    fun `each debug logo leaf falls back to the platform's release icon`() {
        val e = ext().withIdentity().withArt()
        e.android.logo { foregroundScale.set(0.61) }
        e.debug { logo { background.set(color("#B00020")) } }

        assertTrue(e.debugLogoFlowsTo(KitePlatform.ANDROID).get())
        assertEquals("#B00020", e.effectiveDebugLogoBackgroundColorFor(KitePlatform.ANDROID).get())
        assertEquals(0.61, e.effectiveDebugLogoScaleFor(KitePlatform.ANDROID).get())
        assertEquals("logo.png", e.effectiveDebugLogoForegroundFor(KitePlatform.ANDROID).get().asFile.name)
        assertNull(e.effectiveDebugLogoScaleFor(KitePlatform.IOS).orNull)
        assertNull(e.effectiveDebugLogoOverlayFor(KitePlatform.IOS).orNull)
    }

    @Test
    fun `the debug icon needs the release icon and can be switched off per platform`() {
        val noArt = ext().withIdentity()
        noArt.debug { logo { background.set(color("#B00020")) } }
        assertFalse(noArt.debugLogoFlowsTo(KitePlatform.ANDROID).get())

        val e = ext().withIdentity().withArt()
        e.debug { logo { overlay.set(File("art/ribbon.png")) } }
        e.ios.debug { logo { enabled.set(false) } }
        assertTrue(e.debugLogoFlowsTo(KitePlatform.ANDROID).get())
        assertFalse(e.debugLogoFlowsTo(KitePlatform.IOS).get())
    }

    @Test
    fun `the debug catalog sits beside the release catalog`() {
        val e = ext()
        val debugCatalog = e.iosDebugAppIconDirectory.get().asFile
        assertEquals("AppIcon-Debug.appiconset", debugCatalog.name)
        assertEquals(e.effectiveIosAppIconDirectory.get().asFile.parentFile, debugCatalog.parentFile)
    }

    @Test
    fun `build types and configurations default to the platform's own debug names`() {
        val e = ext()
        assertEquals(listOf("debug"), e.effectiveAndroidDebugBuildTypes.get())
        assertEquals(listOf("Debug"), e.effectiveIosDebugConfigurations.get())

        e.android.debug { buildTypes("debug", "staging") }
        e.ios.debug { configurations("Staging") }
        assertEquals(listOf("debug", "staging"), e.effectiveAndroidDebugBuildTypes.get())
        assertEquals(listOf("Staging"), e.effectiveIosDebugConfigurations.get())
    }

    @Test
    fun `a debug identifier equal to the release one is refused`() {
        val e = ext().withIdentity()
        e.ios.debug { appId.set("com.example.jetzy") }

        val failure = KiteConfigPlugin().collectModelFailures(e).single()

        assertTrue(failure.message.orEmpty().contains("side by side"), failure.message)
    }

    @Test
    fun `a debug identifier without the shared one is refused`() {
        val e = ext()
        e.appName.set("Jetzy")
        e.ios.debug { appId.set("com.example.other") }

        val failure = KiteConfigPlugin().collectModelFailures(e).single()

        assertTrue(failure.message.orEmpty().contains("needs the shared appId"), failure.message)
    }

    /** A suffix has nothing to attach to without a release identifier, so it must not vanish quietly. */
    @Test
    fun `a debug suffix without a release identifier is refused`() {
        val e = ext()
        e.appName.set("Jetzy")
        e.debug { appId { suffix.set(".debug") } }

        val failure = KiteConfigPlugin().collectModelFailures(e).single()

        assertTrue(failure.message.orEmpty().contains("needs the shared appId"), failure.message)
    }

    @Test
    fun `a healthy debug topic passes validation`() {
        val e = ext().withIdentity().withArt()
        e.debug {
            appId { suffix.set(".debug") }
            appName.set("Jetzy Dev")
            logo { background.set(color("#B00020")) }
        }

        assertEquals(emptyList<Throwable>(), KiteConfigPlugin().collectModelFailures(e))
    }
}
