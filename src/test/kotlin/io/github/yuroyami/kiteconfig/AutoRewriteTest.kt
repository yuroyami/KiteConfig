package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoRewriteTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `auto defaults off`() {
        val e = ext()
        e.logo { rewrite { } }
        e.ios { rewrite { } }
        assertFalse(e.effectiveAutoRewriteLogo.get())
        assertFalse(e.effectiveAutoRewriteXcode.get())
    }

    @Test
    @OptIn(DiscouragedKiteApi::class)
    fun `auto needs an armed rewrite to count`() {
        val e = ext()
        e.logo { rewrite { auto.set(true) } }
        assertTrue(e.effectiveAutoRewriteLogo.get())
        val e2 = ext()
        e2.ios.rewrite.auto.set(true)
        assertFalse(e2.effectiveAutoRewriteXcode.get())
        e2.ios { rewrite { } }
        assertTrue(e2.effectiveAutoRewriteXcode.get())
    }

    @Test
    fun `doctor warns while auto is on`() {
        val findings = KiteConfigDiagnosticEngine.evaluate(KiteConfigDiagnosticContext(autoRewrites = true))
        assertTrue(findings.any { it.id == "KTCNFG094" && it.severity == KiteConfigDiagnosticSeverity.WARNING })
        val quiet = KiteConfigDiagnosticEngine.evaluate(KiteConfigDiagnosticContext())
        assertFalse(quiet.any { it.id == "KTCNFG094" })
    }
}
