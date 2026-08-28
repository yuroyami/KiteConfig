package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppNameScopeTest {

    private fun ext(): KiteSsotExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteSsotPlugin::class.java)
        return project.extensions.getByType(KiteSsotExtension::class.java)
    }

    @Test
    fun `simple form flows the same value everywhere`() {
        val e = ext()
        e.appName.set("Jetzy")
        KitePlatform.entries.forEach {
            assertEquals("Jetzy", e.effectiveAppNameFor(it).get())
            assertTrue(e.appNameFlowsTo(it).get())
        }
    }

    @Test
    fun `detailed form sets base, overrides, and flow`() {
        val e = ext()
        e.appName("Jetzy") {
            ios("Jetzy Lite")
            skip(desktop)
        }
        assertEquals("Jetzy", e.effectiveAppNameFor(KitePlatform.ANDROID).get())
        assertEquals("Jetzy Lite", e.effectiveAppNameFor(KitePlatform.IOS).get())
        assertFalse(e.appNameFlowsTo(KitePlatform.DESKTOP).get())
        assertTrue(e.appNameFlowsTo(KitePlatform.ANDROID).get())
    }
}
