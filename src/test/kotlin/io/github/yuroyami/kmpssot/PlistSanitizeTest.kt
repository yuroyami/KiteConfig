package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlistSanitizeTest {

    private fun plist(body: String) = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        |<plist version="1.0">
        |<dict>
        |$body
        |</dict>
        |</plist>
    """.trimMargin()

    private val nameEntry = PlistStringEntry("CFBundleName", "\$(PRODUCT_NAME)")
    private val encEntry = PlistBoolEntry("ITSAppUsesNonExemptEncryption", false)

    @Test
    fun `inserts a missing string key`() {
        val r = sanitizeInfoPlist(
            plist("\t<key>CFBundleExecutable</key>\n\t<string>App</string>"),
            listOf(nameEntry), emptyList(),
        )
        assertNotNull(r.text)
        assertTrue("CFBundleName" in r.inserted)
        assertTrue(r.text!!.contains("<key>CFBundleName</key>"))
        assertTrue(r.text!!.contains("\$(PRODUCT_NAME)"))
    }

    @Test
    fun `does not insert a duplicate when the key already exists with a dict value`() {
        val body = "\t<key>CFBundleName</key>\n\t<dict>\n\t\t<key>inner</key><string>y</string>\n\t</dict>"
        val r = sanitizeInfoPlist(plist(body), listOf(nameEntry), emptyList())
        assertTrue("CFBundleName" !in r.inserted)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun `warns but does not overwrite a hardcoded string value`() {
        val r = sanitizeInfoPlist(
            plist("\t<key>CFBundleName</key>\n\t<string>Hardcoded</string>"),
            listOf(nameEntry), emptyList(),
        )
        assertNull(r.text)
        assertTrue(r.warnings.any { it.contains("hardcoded", ignoreCase = true) })
    }

    @Test
    fun `overwrites a boolean flag when it differs`() {
        val r = sanitizeInfoPlist(
            plist("\t<key>ITSAppUsesNonExemptEncryption</key>\n\t<true/>"),
            emptyList(), listOf(encEntry),
        )
        assertNotNull(r.text)
        assertTrue("ITSAppUsesNonExemptEncryption" in r.overwritten)
        assertTrue(r.text!!.contains("<false/>"))
        assertFalse(r.text!!.contains("<true/>"))
    }

    @Test
    fun `inserts a missing boolean flag`() {
        val r = sanitizeInfoPlist(
            plist("\t<key>CFBundleExecutable</key>\n\t<string>App</string>"),
            emptyList(), listOf(PlistBoolEntry("CADisableMinimumFrameDurationOnPhone", true)),
        )
        assertNotNull(r.text)
        assertTrue(r.text!!.contains("CADisableMinimumFrameDurationOnPhone"))
        assertTrue(r.text!!.contains("<true/>"))
    }

    @Test
    fun `no change leaves text null`() {
        val r = sanitizeInfoPlist(
            plist("\t<key>CFBundleName</key>\n\t<string>\$(PRODUCT_NAME)</string>"),
            listOf(nameEntry), emptyList(),
        )
        assertNull(r.text)
    }

    // --- F8: a trailing <key> with no value element is not duplicated. --------
    @Test
    fun `a dangling trailing key is warned about, not duplicated`() {
        val r = sanitizeInfoPlist(plist("\t<key>CFBundleName</key>"), listOf(nameEntry), emptyList())
        assertNull(r.text)
        assertTrue("CFBundleName" !in r.inserted)
        assertTrue(r.warnings.any { it.contains("no following value", ignoreCase = true) }, r.warnings.toString())
    }

    // --- F6: a key insert does not mangle the XML prolog. ---------------------
    @Test
    fun `insert keeps a faithful prolog (no standalone, DOCTYPE on its own line)`() {
        val r = sanitizeInfoPlist(
            plist("\t<key>CFBundleExecutable</key>\n\t<string>App</string>"),
            listOf(nameEntry), emptyList(),
        )
        assertNotNull(r.text)
        assertFalse(r.text!!.contains("standalone"), r.text!!.take(120))
        assertTrue(r.text!!.contains("?>\n<!DOCTYPE"), r.text!!.take(120))
    }
}
