package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoRewriteTest {

    private fun ext(): KiteSsotExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteSsotPlugin::class.java)
        return project.extensions.getByType(KiteSsotExtension::class.java)
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
        val findings = KiteSsotDiagnosticEngine.evaluate(KiteSsotDiagnosticContext(autoRewrites = true))
        assertTrue(findings.any { it.id == "KMPS094" && it.severity == KiteSsotDiagnosticSeverity.WARNING })
        val quiet = KiteSsotDiagnosticEngine.evaluate(KiteSsotDiagnosticContext())
        assertFalse(quiet.any { it.id == "KMPS094" })
    }
}
