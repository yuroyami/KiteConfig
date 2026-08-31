package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformBlocksTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `sdk function sets any subset`() {
        val e = ext()
        e.android { sdk(min = 26, target = 36) }
        assertEquals(26, e.android.minSdk.get())
        assertEquals(36, e.android.targetSdk.get())
        assertFalse(e.android.compileSdk.isPresent)
        assertTrue(e.effectiveApplySdkLevels.get())
    }

    @Test
    fun `sdk levels flow only when declared`() {
        val e = ext()
        assertFalse(e.effectiveApplySdkLevels.get())
    }

    @Test
    fun `ios rewrite arms the xcode task and cleanPlist rides it`() {
        val e = ext()
        e.ios {
            rewrite {
                targets("iosApp")
                cleanPlist.set(true)
            }
        }
        assertTrue(e.effectiveSyncIos.get())
        assertTrue(e.effectiveSanitizeIosProject.get())
    }

    @Test
    fun `desktop flows automatically`() {
        val e = ext()
        assertTrue(e.effectiveDesktopEnabled.get())
    }
}
