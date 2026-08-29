package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SplashScopeTest {

    private fun ext(): KiteSsotExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteSsotPlugin::class.java)
        return project.extensions.getByType(KiteSsotExtension::class.java)
    }

    @Test
    fun `empty splash flows android and desktop, never ios`() {
        val e = ext()
        e.splash { }
        assertTrue(e.effectiveAndroidSplash.get())
        assertTrue(e.effectiveDesktopSplash.get())
        assertFalse(e.effectiveIosSplash.get())
    }

    @Test
    fun `nothing declared, nothing flows`() {
        val e = ext()
        assertFalse(e.effectiveAndroidSplash.get())
        assertFalse(e.effectiveDesktopSplash.get())
    }

    @Test
    fun `facts default to logo art`() {
        val e = ext()
        e.logo { backgroundColor.set("#101014") }
        e.splash { }
        assertEquals("#101014", e.effectiveSplashColor.get())
    }

    @Test
    fun `skip stops one platform`() {
        val e = ext()
        e.splash { skip(desktop) }
        assertTrue(e.effectiveAndroidSplash.get())
        assertFalse(e.effectiveDesktopSplash.get())
    }

    @Test
    fun `ios needs both rewrites armed`() {
        val e = ext()
        e.splash { rewrite { } }
        assertFalse(e.effectiveIosSplash.get())
        e.ios { rewrite { } }
        assertTrue(e.effectiveIosSplash.get())
    }

    @Test
    fun `dark variant and android theme corner hold their values`() {
        val e = ext()
        e.splash {
            dark { backgroundColor.set("#000000") }
            android { theme.set("AppTheme") }
        }
        assertEquals("#000000", e.effectiveSplashDarkColor.get())
        assertEquals("AppTheme", e.effectiveSplashAndroidTheme.get())
    }
}
