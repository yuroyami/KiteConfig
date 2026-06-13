package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PbxprojRewriteTest {

    private val sample = """
        MARKETING_VERSION = 0.0.1;
        CURRENT_PROJECT_VERSION = 1;
        PRODUCT_NAME = Old;
        INFOPLIST_KEY_CFBundleDisplayName = Old;
        INFOPLIST_KEY_CFBundleName = Old;
        PRODUCT_BUNDLE_IDENTIFIER = com.old.id;
        knownRegions = (
        				Base,
        				en,
        			);
    """.trimIndent()

    @Test
    fun `rewrites version, name, bundle id, locales`() {
        val r = rewritePbxproj(sample, "2.3.4", deriveVersionCode("2.3.4"), "New App", "com.new.id", listOf("en", "fr"))
        assertTrue(r.text.contains("MARKETING_VERSION = 2.3.4;"))
        assertTrue(r.text.contains("CURRENT_PROJECT_VERSION = 1002003004;"))
        assertTrue(r.text.contains("PRODUCT_NAME = \"New App\";"))
        assertTrue(r.text.contains("INFOPLIST_KEY_CFBundleDisplayName = \"New App\";"))
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.new.id;"))
        assertTrue(r.text.contains("fr"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `dollar in app name does not crash and stays literal`() {
        // The bug: replace(Regex, String) treats $ as a group reference and throws.
        val r = rewritePbxproj(sample, null, null, "Cost\$ Money", null, null)
        assertTrue(r.text.contains("PRODUCT_NAME = \"Cost\$ Money\";"), r.text)
    }

    @Test
    fun `backslash and quote in app name are pbxproj-escaped`() {
        val r = rewritePbxproj(sample, null, null, "A\"B\\C", null, null)
        assertTrue(r.text.contains("PRODUCT_NAME = \"A\\\"B\\\\C\";"), r.text)
    }

    @Test
    fun `missing knownRegions block warns instead of silently dropping`() {
        val r = rewritePbxproj("PRODUCT_NAME = X;", null, null, null, null, listOf("en"))
        assertEquals(1, r.warnings.size)
    }

    @Test
    fun `untouched when nothing requested`() {
        val r = rewritePbxproj(sample, null, null, null, null, null)
        assertEquals(sample, r.text)
        assertTrue(r.warnings.isEmpty())
    }
}
