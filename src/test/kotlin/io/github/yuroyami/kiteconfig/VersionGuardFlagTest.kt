package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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
        assertEquals(KiteConfigDiagnosticSeverity.ERROR, hard.single { it.id == "KTCNFG061" }.severity)
        assertEquals(KiteConfigDiagnosticSeverity.ERROR, hard.single { it.id == "KTCNFG062" }.severity)

        val soft = KiteConfigDiagnosticEngine.evaluate(
            KiteConfigDiagnosticContext(
                agpOnClasspath = true, agpRequired = true, activeAgpVersion = "9.9.0",
                kgpOnClasspath = true, kgpRequired = true, activeKgpVersion = "2.9.0",
                versionGuardsIgnored = true,
            ),
        )
        assertEquals(KiteConfigDiagnosticSeverity.WARNING, soft.single { it.id == "KTCNFG061" }.severity)
        assertEquals(KiteConfigDiagnosticSeverity.WARNING, soft.single { it.id == "KTCNFG062" }.severity)
        assertTrue(soft.single { it.id == "KTCNFG061" }.detail.contains("ignoreVersionGuards"))
    }

    @Test
    fun `the override activates the adapters an untested version would refuse`() {
        assertTrue(typedIntegrationsActive(supported = true, ignoreGuards = false))
        assertTrue(typedIntegrationsActive(supported = true, ignoreGuards = true))
        assertTrue(typedIntegrationsActive(supported = false, ignoreGuards = true))
        assertFalse(typedIntegrationsActive(supported = false, ignoreGuards = false))
    }

    /**
     * The KMP-native Android branch used to decide on the tested matrix alone, so
     * the override left that adapter off while the console said every typed
     * integration stayed active. Nothing but the declarations may name the
     * matrix answer directly.
     */
    @Test
    fun `no adapter branch decides on the tested matrix alone`() {
        val source = File("src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigPlugin.kt")
        assertTrue(source.isFile, "expected the project root, got ${File(".").absolutePath}")

        val offenders = source.readLines().withIndex().filter { (_, line) ->
            line.contains("AdaptersUsable") &&
                !line.contains("typedIntegrationsActive") &&
                !line.trimStart().startsWith("val ")
        }
        assertTrue(
            offenders.isEmpty(),
            "these lines gate an adapter without typedIntegrationsActive: " +
                offenders.joinToString { "line ${it.index + 1}: ${it.value.trim()}" },
        )
    }
}
