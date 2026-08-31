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
        assertFalse(e.versionFlowsTo(KitePlatform.DESKTOP).get())
        assertFalse(e.effectiveDesktopEnabled.get())
        assertTrue(e.appNameFlowsTo(KitePlatform.ANDROID).get())
    }

    @Test
    fun `topic skip and root skip compose`() {
        val e = ext()
        e.appName("Jetzy") { skip(ios) }
        assertFalse(e.appNameFlowsTo(KitePlatform.IOS).get())
        assertTrue(e.appNameFlowsTo(KitePlatform.ANDROID).get())
        assertTrue(e.idFlowsTo(KitePlatform.IOS).get())
    }
}
