package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionGuardFlagTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `guards are on by default`() {
        assertFalse(ext().effectiveIgnoreVersionGuards.get())
    }

    @Test
    @OptIn(DiscouragedKiteApi::class)
    fun `the flag flips the effective value`() {
        val e = ext()
        e.ignoreVersionGuards.set(true)
        assertTrue(e.effectiveIgnoreVersionGuards.get())
    }

    @Test
    fun `compatibility findings soften to warnings under the flag`() {
        val hard = KiteConfigDiagnosticEngine.evaluate(
            KiteConfigDiagnosticContext(
                agpOnClasspath = true, agpRequired = true, activeAgpVersion = "9.9.0",
                kgpOnClasspath = true, kgpRequired = true, activeKgpVersion = "2.9.0",
            ),
        )
        assertEquals(KiteConfigDiagnosticSeverity.ERROR, hard.single { it.id == "KMPS061" }.severity)
        assertEquals(KiteConfigDiagnosticSeverity.ERROR, hard.single { it.id == "KMPS062" }.severity)

        val soft = KiteConfigDiagnosticEngine.evaluate(
            KiteConfigDiagnosticContext(
                agpOnClasspath = true, agpRequired = true, activeAgpVersion = "9.9.0",
                kgpOnClasspath = true, kgpRequired = true, activeKgpVersion = "2.9.0",
                versionGuardsIgnored = true,
            ),
        )
        assertEquals(KiteConfigDiagnosticSeverity.WARNING, soft.single { it.id == "KMPS061" }.severity)
        assertEquals(KiteConfigDiagnosticSeverity.WARNING, soft.single { it.id == "KMPS062" }.severity)
        assertTrue(soft.single { it.id == "KMPS061" }.detail.contains("ignoreVersionGuards"))
    }
}
