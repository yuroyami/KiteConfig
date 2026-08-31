package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionScopeTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `default formula packs the version`() {
        val e = ext()
        e.version.set("1.4.0")
        assertEquals(1001004000, e.versionCode.get())
    }

    @Test
    fun `topic formula feeds all platforms and corner reupload feeds the formula`() {
        val e = ext()
        e.version("1.4.1") {
            formula { v -> 1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.reupload }
            android { reupload.set(2) }
            ios { }
        }
        assertEquals(1_040_102, e.versionCode.get())
        assertEquals("1040100", e.effectiveIosBuildNumber.get())
    }

    @Test
    fun `pin skips the formula and marks the code explicit`() {
        val e = ext()
        e.version("1.4.0") {
            android { pin.set(555) }
        }
        assertEquals(555, e.versionCode.get())
        assertTrue(e.effectiveHasExplicitVersionCode.get())
    }

    @Test
    fun `corner formula beats topic formula on its platform only`() {
        val e = ext()
        e.version("2.1.0") {
            formula { v -> 100 * v.minor + v.reupload }
            ios { formula { v -> 7 } }
        }
        assertEquals(100, e.versionCode.get())
        assertEquals("7", e.effectiveIosBuildNumber.get())
    }
}
