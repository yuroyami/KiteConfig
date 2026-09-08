package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SplashScopeTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    /** A launch screen is a visible change, so configuring art is not asking for one. */
    @Test
    fun `an empty block leaves splash off`() {
        val e = ext()
        e.splash { }

        assertFalse(e.effectiveAndroidSplash.get())
        assertFalse(e.effectiveDesktopSplash.get())
        assertFalse(e.effectiveIosSplash.get())
    }

    @Test
    fun `configured artwork alone leaves splash off`() {
        val e = ext()
        e.splash {
            image.set(File("art/splash.png"))
            backgroundColor.set("#101014")
            dark { backgroundColor.set("#000000") }
        }

        assertFalse(e.effectiveAndroidSplash.get())
        assertFalse(e.effectiveDesktopSplash.get())
    }

    @Test
    fun `nothing declared, nothing flows`() {
        val e = ext()

        assertFalse(e.effectiveAndroidSplash.get())
        assertFalse(e.effectiveDesktopSplash.get())
    }

    @Test
    fun `enabling it turns it on everywhere`() {
        val e = ext()
        e.splash { enabled.set(true) }

        assertTrue(e.effectiveAndroidSplash.get())
        assertTrue(e.effectiveDesktopSplash.get())
        assertTrue(e.effectiveIosSplash.get())
    }

    @Test
    fun `art falls back to the logo, resolved per platform`() {
        val e = ext()
        e.logo { background.set(color("#101014")) }
        e.ios.logo { background.set(color("#222222")) }
        e.splash { enabled.set(true) }

        assertEquals("#101014", e.effectiveSplashColorFor(KitePlatform.ANDROID).get())
        assertEquals("#222222", e.effectiveSplashColorFor(KitePlatform.IOS).get())
    }

    @Test
    fun `a platform can decline a launch screen the root asked for`() {
        val e = ext()
        e.splash { enabled.set(true) }
        e.desktop.splash { enabled.set(false) }

        assertTrue(e.effectiveAndroidSplash.get())
        assertFalse(e.effectiveDesktopSplash.get())
    }

    /** A root false is an inheritable default, not a ban. */
    @Test
    fun `a platform can ask for one the root left off`() {
        val e = ext()
        e.splash { enabled.set(false) }
        e.android.splash { enabled.set(true) }

        assertTrue(e.effectiveAndroidSplash.get())
        assertFalse(e.effectiveDesktopSplash.get())
    }

    @Test
    fun `root exclusion still wins over a platform saying yes`() {
        val e = ext()
        e.skip(e.desktop)
        e.desktop.splash { enabled.set(true) }

        assertFalse(e.effectiveDesktopSplash.get())
    }

    @Test
    fun `iOS follows the same switch as everyone else`() {
        val e = ext()
        e.splash { enabled.set(true) }
        assertTrue(e.effectiveIosSplash.get())

        e.ios.splash { enabled.set(false) }
        assertFalse(e.effectiveIosSplash.get())
    }

    @Test
    fun `the dark variant turns itself on and a platform can drop it`() {
        val e = ext()
        e.splash {
            enabled.set(true)
            dark { backgroundColor.set("#000000") }
        }

        assertTrue(e.splashDarkEnabledFor(KitePlatform.ANDROID).get())
        assertEquals("#000000", e.effectiveSplashDarkColorFor(KitePlatform.ANDROID).get())

        e.ios.splash { dark { enabled.set(false) } }
        assertFalse(e.splashDarkEnabledFor(KitePlatform.IOS).get())
        assertTrue(e.splashDarkEnabledFor(KitePlatform.ANDROID).get())
    }

    @Test
    fun `the Android theme lives in the Android block`() {
        val e = ext()
        e.android.splash { theme.set("AppTheme") }

        assertEquals("AppTheme", e.effectiveSplashAndroidTheme.get())
    }
}
