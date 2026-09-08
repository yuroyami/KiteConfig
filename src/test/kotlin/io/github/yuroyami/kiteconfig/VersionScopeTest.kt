package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionScopeTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `the default scheme packs the shared version`() {
        val e = ext()
        e.version.set("1.4.0")

        assertEquals(1001004000, e.versionCode.get())
        assertEquals("1001004000", e.effectiveIosBuildNumber.get())
        assertEquals("1001004000", e.effectiveDesktopBuildNumber.get())
    }

    @Test
    fun `a shared scheme reaches every platform and a platform rebuild feeds it`() {
        val e = ext()
        e.version.set("1.4.1")
        e.version {
            scheme { v -> (1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.rebuild).toString() }
        }
        e.android.version { rebuild.set(2) }

        assertEquals(1_040_102, e.versionCode.get())
        assertEquals("1040100", e.effectiveIosBuildNumber.get())
    }

    @Test
    fun `a platform rebuild overrides the shared one only there`() {
        val e = ext()
        e.version.set("1.4.0")
        e.version { rebuild.set(1) }
        e.ios.version { rebuild.set(5) }

        assertEquals(1001004001, e.versionCode.get())
        assertEquals("1001004005", e.effectiveIosBuildNumber.get())
    }

    @Test
    fun `an exact build number skips the scheme and marks the code explicit`() {
        val e = ext()
        e.version.set("1.4.0")
        e.android.version { buildNumber.set("555") }

        assertEquals(555, e.versionCode.get())
        assertTrue(e.effectiveHasExplicitVersionCode.get())
        // Only Android was pinned, so the others still derive.
        assertEquals("1001004000", e.effectiveIosBuildNumber.get())
    }

    @Test
    fun `a platform scheme beats the shared one on its platform only`() {
        val e = ext()
        e.version.set("2.1.0")
        e.version { scheme { v -> (100 * v.minor + v.rebuild).toString() } }
        e.ios.version { scheme { "7" } }

        assertEquals(100, e.versionCode.get())
        assertEquals("7", e.effectiveIosBuildNumber.get())
    }

    /** Apple keeps the shown version and the build number in separate fields. */
    @Test
    fun `an iOS version override changes the marketing version only`() {
        val e = ext()
        e.version.set("1.4.0")
        e.ios.version.set("1.5.0")

        assertEquals("1.5.0", e.effectiveIosMarketingVersion.get())
        assertEquals("1001005000", e.effectiveIosBuildNumber.get())
        assertEquals(1001004000, e.versionCode.get())
    }

    @Test
    fun `a later shared version does not overwrite a platform one`() {
        val e = ext()
        e.ios.version.set("1.5.0")
        e.version.set("1.4.0")

        assertEquals("1.5.0", e.effectiveIosMarketingVersion.get())
        assertEquals("1.4.0", e.version.get())
    }

    @Test
    fun `delivery off keeps the number readable`() {
        val e = ext()
        e.version.set("1.4.0")
        e.desktop.version { enabled.set(false) }

        assertFalse(e.versionFlowsTo(KitePlatform.DESKTOP).get())
        assertTrue(e.versionFlowsTo(KitePlatform.ANDROID).get())
        assertEquals("1001004000", e.effectiveDesktopBuildNumber.get())
    }
}
