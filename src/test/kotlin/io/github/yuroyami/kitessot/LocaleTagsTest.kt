package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
    fun `canonicalizes explicit BCP47 tags and deduplicates after conversion`() {
        assertEquals(listOf("en", "pt-BR", "sr-Latn"), canonicalizeLocales(listOf("en", "pt-rBR", "b+pt+BR", "sr-Latn")))
        assertEquals("pt-rBR", bcp47ToAndroidQualifier("pt-BR"))
        assertEquals("b+sr+Latn", bcp47ToAndroidQualifier("sr-Latn"))
        assertEquals("b+es+419", bcp47ToAndroidQualifier("es-419"))
        assertEquals("b+car", bcp47ToAndroidQualifier("car"))
    }

    @Test
    fun `accepts locale qualifiers and rejects other resource qualifiers`() {
        listOf("en", "fr", "fil", "pt-rBR", "pt-BR", "b+sr+Latn", "de-CH-1901", "sl-rozaj-biske")
            .forEach { assertTrue(looksLikeLocaleQualifier(it), it) }
        listOf(
            "night", "land", "v26", "sw600dp", "xxhdpi", "b+en++US", "",
            "en-u-ca-gregory", "x-private", "i-klingon", "es-r419",
            "de-1901-1901", "sl-rozaj-ROZAJ",
            "art-lojban", "cel-gaulish", "zh-guoyu", "zh-hakka", "zh-xiang",
        ).forEach { assertFalse(looksLikeLocaleQualifier(it), it) }

        assertFalse(looksLikeUnambiguousLocaleQualifier("car"))
        listOf("fr", "fil", "pt-rBR", "b+car")
            .forEach { assertTrue(looksLikeUnambiguousLocaleQualifier(it), it) }
    }

    @Test
    fun `pbxproj knownRegions receives the mapped apple tags`() {
        val src = """
            {
                objects = {
                    AA00000000000000000000AA = {
                        isa = PBXProject;
                        knownRegions = (
                            Base,
                            en,
                        );
                    };
                };
                rootObject = AA00000000000000000000AA;
            }
        """.trimIndent()
        val r = rewritePbxproj(src, null, null, null, null, listOf("en", "pt-rBR"))
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertTrue(r.text.contains("pt-BR"), r.text)
        assertFalse(r.text.contains("pt-rBR"), r.text)
    }

    @Test
    fun `locale auto detection is shallow exact and bounded`(@TempDir resources: File) {
        File(resources, "values-en").mkdir()
        File(resources, "values-pt-rBR").mkdir()
        File(resources, "values-b+sr+Latn").mkdir()
        File(resources, "values-night").mkdir()
        File(resources, "values-pt-BR").mkdir()
        File(resources, "values-sr-Latn").mkdir()
        File(resources, "values-en-night").mkdir()
        File(resources, "values-en-land").mkdir()
        File(resources, "values-en-rUS-night").mkdir()
        File(resources, "values-fr.txt").writeText("not a directory")

        assertEquals(listOf("en", "pt-BR", "sr-Latn"), detectComposeResourceLocales(resources))
        assertThrows(GradleException::class.java) {
            detectComposeResourceLocales(resources, maximumEntries = 2)
        }
    }

    @Test
    fun `locale model rejects oversized tags and collections`() {
        assertFalse(looksLikeLocaleQualifier("a".repeat(256)))
        assertThrows(GradleException::class.java) {
            canonicalizeLocales(List(1_001) { "en" })
        }
    }
}
