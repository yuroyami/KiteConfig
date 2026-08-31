package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalesScopeTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `pin sets the list and canonicalization still applies`() {
        val e = ext()
        e.locales {
            pin("en", "en", "pt-BR")
        }
        assertEquals(listOf("en", "pt-BR"), e.canonicalLocales.get())
    }

    @Test
    fun `filterAndroidRes defaults off and turns on in the scope`() {
        val e = ext()
        assertFalse(e.effectiveFilterAndroidResources.get())
        val e2 = ext()
        e2.locales { filterAndroidRes.set(true) }
        assertTrue(e2.effectiveFilterAndroidResources.get())
    }
}
