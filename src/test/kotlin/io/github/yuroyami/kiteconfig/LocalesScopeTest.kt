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
    fun `the tag list is normalized and de-duplicated`() {
        val e = ext()
        e.locales { tags.set(listOf("en", "en", "pt-BR")) }

        assertEquals(listOf("en", "pt-BR"), e.canonicalLocales.get())
    }

    @Test
    fun `a platform narrows the list only for itself`() {
        val e = ext()
        e.locales { tags.set(listOf("en", "ar", "fr")) }
        e.ios.locales { tags.set(listOf("en", "fr")) }

        assertEquals(listOf("en", "fr"), e.effectiveLocalesFor(KitePlatform.IOS).get())
        assertEquals(listOf("en", "ar", "fr"), e.effectiveLocalesFor(KitePlatform.ANDROID).get())
    }

    /** An empty list is an answer, not a request to go looking for one. */
    @Test
    fun `an explicitly empty list is not a request to detect`() {
        val e = ext()
        e.locales { tags.set(emptyList()) }

        assertEquals(emptyList<String>(), e.effectiveLocales.get())
        assertEquals(emptyList<String>(), e.effectiveLocalesFor(KitePlatform.ANDROID).get())
    }

    @Test
    fun `the Android resource filter defaults off and lives in the Android block`() {
        assertFalse(ext().effectiveFilterAndroidResources.get())

        val e = ext()
        e.android.locales { filterResources.set(true) }
        assertTrue(e.effectiveFilterAndroidResources.get())
    }

    @Test
    fun `delivery off keeps the list readable`() {
        val e = ext()
        e.locales { tags.set(listOf("en")) }
        e.ios.locales { enabled.set(false) }

        assertFalse(e.localesFlowsTo(KitePlatform.IOS).get())
        assertTrue(e.localesFlowsTo(KitePlatform.ANDROID).get())
        assertEquals(listOf("en"), e.effectiveLocalesFor(KitePlatform.IOS).get())
    }
}
