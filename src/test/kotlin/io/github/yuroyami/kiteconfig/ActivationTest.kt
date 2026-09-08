package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A feature is on when you gave it what it needs.
 *
 * Activation used to depend on whether the block was called, so configuring the
 * same object directly, which is what a convention plugin does, generated
 * nothing and reported nothing.
 */
class ActivationTest {

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
    fun `an empty block is not a request`() {
        val e = ext()
        e.buildConfig { }
        e.optIns { }
        e.web { ioWorker { } }

        assertFalse(e.effectiveBuildConfigEnabled.get())
        assertFalse(e.effectiveNativeOptInsEnabled.get())
        assertFalse(e.effectiveIoWorkerEnabled.get())
    }

    @Test
    fun `content turns the generated features on`() {
        val e = ext()
        e.buildConfig { stringField("API_HOST", "example.com") }
        e.optIns { add("kotlinx.cinterop.ExperimentalForeignApi") }

        assertTrue(e.effectiveBuildConfigEnabled.get())
        assertTrue(e.effectiveNativeOptInsEnabled.get())
    }

    /** The route a convention plugin takes has to mean the same thing. */
    @Test
    fun `configuring the object directly counts`() {
        val e = ext()
        e.buildConfig.stringField("API_HOST", "example.com")
        e.optIns.add("kotlinx.cinterop.ExperimentalForeignApi")

        assertTrue(e.effectiveBuildConfigEnabled.get())
        assertTrue(e.effectiveNativeOptInsEnabled.get())
    }

    @Test
    fun `a feature that needs nothing from you asks to be switched on`() {
        val e = ext()
        e.web { ioWorker { targets("js") } }
        assertFalse(e.effectiveIoWorkerEnabled.get())

        e.web { ioWorker { enabled.set(true) } }
        assertTrue(e.effectiveIoWorkerEnabled.get())
    }

    @Test
    fun `an explicit switch wins over the content rule`() {
        val identityOnly = ext()
        identityOnly.buildConfig { enabled.set(true) }
        assertTrue(identityOnly.effectiveBuildConfigEnabled.get())

        val off = ext()
        off.buildConfig {
            enabled.set(false)
            stringField("API_HOST", "example.com")
        }
        assertFalse(off.effectiveBuildConfigEnabled.get())
    }
}
