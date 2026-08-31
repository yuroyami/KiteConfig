package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PresenceConsentTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `nothing declared, nothing on`() {
        val e = ext()
        assertFalse(e.effectiveBuildConfigEnabled.get())
        assertFalse(e.effectiveIoWorkerEnabled.get())
        assertFalse(e.effectiveNativeOptInsEnabled.get())
    }

    @Test
    fun `presence is the consent`() {
        val e = ext()
        e.buildConfig { }
        e.web { ioWorker { } }
        e.optIns { add("kotlinx.cinterop.ExperimentalForeignApi") }
        assertTrue(e.effectiveBuildConfigEnabled.get())
        assertTrue(e.effectiveIoWorkerEnabled.get())
        assertTrue(e.effectiveNativeOptInsEnabled.get())
    }
}
