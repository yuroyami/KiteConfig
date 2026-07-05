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

    // --- F2: keys are anchored on the left, so a sibling key that *ends with*
    // the target name is never corrupted. --------------------------------------
    @Test
    fun `does not corrupt a sibling key that ends with the target name`() {
        val src = "MY_PRODUCT_NAME = KeepMe;\nPRODUCT_NAME = Old;"
        val r = rewritePbxproj(src, null, null, "New", null, null)
        assertTrue(r.text.contains("MY_PRODUCT_NAME = KeepMe;"), r.text)
        assertTrue(r.text.contains("PRODUCT_NAME = \"New\";"), r.text)
    }

    // --- F3: a quoted value containing ';' is not split mid-string. ------------
    @Test
    fun `rewrites a quoted value that contains a semicolon without splitting it`() {
        val src = "PRODUCT_NAME = \"App; Inc\";"
        val r = rewritePbxproj(src, null, null, "New", null, null)
        assertEquals("PRODUCT_NAME = \"New\";", r.text)
    }

    // --- F3: a value missing its terminating ';' can't swallow later lines. ----
    @Test
    fun `a setting missing its semicolon does not eat the following line`() {
        val src = "PRODUCT_NAME = brokenNoSemicolon\nPRODUCT_BUNDLE_IDENTIFIER = com.keep.me;"
        val r = rewritePbxproj(src, null, null, "New", "com.new.id", null)
        // PRODUCT_NAME line has no ';' before the newline → left untouched, not merged.
        assertTrue(r.text.contains("PRODUCT_NAME = brokenNoSemicolon"), r.text)
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.new.id;"), r.text)
    }
}
