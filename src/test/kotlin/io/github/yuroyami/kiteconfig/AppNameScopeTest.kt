package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppNameScopeTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `the shared name reaches every platform`() {
        val e = ext()
        e.appName.set("Jetzy")

        KitePlatform.entries.forEach {
            assertEquals("Jetzy", e.effectiveAppNameFor(it).get())
            assertTrue(e.appNameFlowsTo(it).get())
        }
    }

    @Test
    fun `a platform name replaces the shared one only there`() {
        val e = ext()
        e.appName.set("Jetzy")
        e.ios.appName.set("Jetzy Lite")

        assertEquals("Jetzy Lite", e.effectiveAppNameFor(KitePlatform.IOS).get())
        assertEquals("Jetzy", e.effectiveAppNameFor(KitePlatform.ANDROID).get())
        assertEquals("Jetzy", e.effectiveAppNameFor(KitePlatform.DESKTOP).get())
    }

    /** Nothing is copied when a block opens, so declaration order cannot decide this. */
    @Test
    fun `a later shared name does not overwrite a platform one`() {
        val e = ext()
        e.ios.appName.set("Jetzy Lite")
        e.appName.set("Jetzy")

        assertEquals("Jetzy Lite", e.effectiveAppNameFor(KitePlatform.IOS).get())
        assertEquals("Jetzy", e.effectiveAppNameFor(KitePlatform.ANDROID).get())
    }

    @Test
    fun `delivery off keeps the value readable`() {
        val e = ext()
        e.appName.set("Jetzy")
        e.ios.appName { enabled.set(false) }

        assertFalse(e.appNameFlowsTo(KitePlatform.IOS).get())
        assertTrue(e.appNameFlowsTo(KitePlatform.ANDROID).get())
        assertEquals("Jetzy", e.effectiveAppNameFor(KitePlatform.IOS).get())
    }
}
