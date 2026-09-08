package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootFlowTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `root skip silences a platform for every fact`() {
        val e = ext()
        e.appName.set("Jetzy")
        e.skip(e.desktop)

        assertFalse(e.appNameFlowsTo(KitePlatform.DESKTOP).get())
        assertFalse(e.appIdFlowsTo(KitePlatform.DESKTOP).get())
        assertFalse(e.versionFlowsTo(KitePlatform.DESKTOP).get())
        assertFalse(e.effectiveDesktopEnabled.get())
        assertTrue(e.appNameFlowsTo(KitePlatform.ANDROID).get())
    }

    /** Selection is the outer boundary, so a platform saying yes cannot reopen it. */
    @Test
    fun `root exclusion wins over a platform turning delivery on`() {
        val e = ext()
        e.appName.set("Jetzy")
        e.skip(e.ios)
        e.ios.appName { enabled.set(true) }

        assertFalse(e.appNameFlowsTo(KitePlatform.IOS).get())
    }

    @Test
    fun `delivery is decided per topic, not for the whole platform`() {
        val e = ext()
        e.appName.set("Jetzy")
        e.appId.set("com.example.jetzy")
        e.ios.appName { enabled.set(false) }

        assertFalse(e.appNameFlowsTo(KitePlatform.IOS).get())
        assertTrue(e.appIdFlowsTo(KitePlatform.IOS).get())
        assertTrue(e.appNameFlowsTo(KitePlatform.ANDROID).get())
    }
}
