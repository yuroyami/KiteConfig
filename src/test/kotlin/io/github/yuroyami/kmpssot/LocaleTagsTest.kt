package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocaleTagsTest {

    @Test
    fun `maps android region and bcp47 tags to apple form`() {
        assertEquals("pt-BR", androidTagToAppleTag("pt-rBR"))
        assertEquals("zh-CN", androidTagToAppleTag("zh-rCN"))
        assertEquals("sr-Latn", androidTagToAppleTag("b+sr+Latn"))
        assertEquals("en", androidTagToAppleTag("en"))       // plain language passes through
        assertEquals("pt-BR", androidTagToAppleTag("pt-BR")) // already-apple passes through
    }

    @Test
    fun `accepts locale qualifiers and rejects other resource qualifiers`() {
        listOf("en", "fr", "fil", "pt-rBR", "b+sr+Latn").forEach { assertTrue(looksLikeLocaleQualifier(it), it) }
        listOf("night", "land", "v26", "sw600dp", "xxhdpi", "").forEach { assertFalse(looksLikeLocaleQualifier(it), it) }
    }

    @Test
    fun `pbxproj knownRegions receives the mapped apple tags`() {
        val src = "knownRegions = (\n\t\t\t\tBase,\n\t\t\t\ten,\n\t\t\t);"
        val r = rewritePbxproj(src, null, null, null, null, listOf("en", "pt-rBR"))
        assertTrue(r.text.contains("pt-BR"), r.text)
        assertFalse(r.text.contains("pt-rBR"), r.text)
    }
}
