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

    /**
     * An excluded platform's own providers are never read, not merely ignored.
     *
     * The gates used to combine both sides with `zip`, which resolves them both
     * before deciding. A provider that throws proves the difference: if any gate
     * still asked, this fails with that provider's message rather than passing.
     */
    @Test
    fun `an excluded platform's own gates are never queried`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        val e = project.extensions.getByType(KiteConfigExtension::class.java)

        fun exploding(topic: String) = project.provider<Boolean> { error("read the excluded desktop $topic") }

        e.skip(e.desktop)
        e.desktop.appName { enabled.set(exploding("appName")) }
        e.desktop.appId { enabled.set(exploding("appId")) }
        e.desktop.version { enabled.set(exploding("version")) }
        e.desktop.locales { enabled.set(exploding("locales")) }
        e.desktop.logo { enabled.set(exploding("logo")) }
        e.desktop.splash { enabled.set(exploding("splash")) }

        assertFalse(e.appNameFlowsTo(KitePlatform.DESKTOP).get())
        assertFalse(e.appIdFlowsTo(KitePlatform.DESKTOP).get())
        assertFalse(e.versionFlowsTo(KitePlatform.DESKTOP).get())
        assertFalse(e.localesFlowsTo(KitePlatform.DESKTOP).get())
        assertFalse(e.logoFlowsTo(KitePlatform.DESKTOP).get())
        assertFalse(e.splashFlowsTo(KitePlatform.DESKTOP).get())
    }
}
