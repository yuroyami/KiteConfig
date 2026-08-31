package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogoScopeTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `declaring art alone arms nothing`() {
        val e = ext()
        e.logo { backgroundColor.set("#101014") }
        assertFalse(e.effectiveLogoRewriteArmed.get())
    }

    @Test
    fun `rewrite block arms the source tasks and replaceOld rides on it`() {
        val e = ext()
        e.logo {
            rewrite { replaceOld.set(true) }
        }
        assertTrue(e.effectiveLogoRewriteArmed.get())
        assertTrue(e.effectiveTakeOverLegacyIcons.get())
    }

    @Test
    fun `desktop icons flow from presence and stop on skip`() {
        val e = ext()
        e.logo { backgroundColor.set("#101014") }
        assertTrue(e.effectiveDesktopIcons.get())
        val e2 = ext()
        e2.logo {
            backgroundColor.set("#101014")
            skip(desktop)
        }
        assertFalse(e2.effectiveDesktopIcons.get())
    }

    @Test
    fun `safe zone moves into the android corner`() {
        val e = ext()
        e.logo { android { safeZone.set(0.7) } }
        assertEquals(0.7, e.effectiveLogoSafeZone.get())
    }
}
