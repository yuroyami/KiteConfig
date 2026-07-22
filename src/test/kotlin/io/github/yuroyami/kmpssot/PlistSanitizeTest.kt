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
        assertTrue(r.errors.isNotEmpty())
    }

    @Test
    fun `default conflict policy fails without overwriting a hardcoded string value`() {
        val r = sanitizeInfoPlist(
            plist("\t<key>CFBundleName</key>\n\t<string>Hardcoded</string>"),
            listOf(nameEntry), emptyList(),
        )
        assertNull(r.text)
        assertTrue(r.errors.any { it.contains("CFBundleName") })
    }

    @Test
    fun `conflict diagnostics bound and escape source-controlled values`() {
        val hostileValue = "line\n\t" + "x".repeat(10_000)
        val r = sanitizeInfoPlist(
            plist("\t<key>CFBundleName</key>\n\t<string>$hostileValue</string>"),
            listOf(nameEntry),
            emptyList(),
        )

        assertNull(r.text)
        val message = r.errors.single { it.contains("CFBundleName") }
        assertTrue(message.length < 500, message.length.toString())
        assertFalse(message.contains('\n'), message)
        assertTrue(message.contains("\\n\\t"), message)
    }

    @Test
    fun `overwrites a boolean flag when it differs`() {
        val r = sanitizeInfoPlist(
            plist("\t<key>ITSAppUsesNonExemptEncryption</key>\n\t<true/>"),
            emptyList(), listOf(encEntry), PlistConflictPolicy.REPLACE,
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
        assertTrue(r.errors.any { it.contains("no following value", ignoreCase = true) }, r.errors.toString())
    }

    // --- T14: back-to-back inserts don't stack a blank line between entries. ---
    @Test
    fun `back-to-back inserts do not leave a blank line between entries`() {
        val r = sanitizeInfoPlist(
            plist("\t<key>CFBundleExecutable</key>\n\t<string>App</string>"),
            listOf(
                PlistStringEntry("CFBundleName", "\$(PRODUCT_NAME)"),
                PlistStringEntry("CFBundleDisplayName", "\$(PRODUCT_NAME)"),
            ),
            emptyList(),
        )
        assertNotNull(r.text)
        assertFalse(r.text!!.contains("\n\n"), r.text!!)
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

    @Test
    fun `duplicate root key aborts the complete plan`() {
        val body = "\t<key>CFBundleName</key>\n\t<string>A</string>\n" +
            "\t<key>CFBundleName</key>\n\t<string>B</string>"
        val r = sanitizeInfoPlist(plist(body), listOf(nameEntry), emptyList())
        assertNull(r.text)
        assertTrue(r.errors.any { it.contains("duplicate") }, r.errors.toString())
    }

    @Test
    fun `whitespace around key text does not alias the required plist key`() {
        val original = plist("\t<key> CFBundleName </key>\n\t<string>Unrelated</string>")

        val r = sanitizeInfoPlist(original, listOf(nameEntry), emptyList())

        assertNotNull(r.text)
        assertTrue("CFBundleName" in r.inserted)
        assertTrue(r.text!!.contains("<key> CFBundleName </key>"))
        assertTrue(r.text!!.contains("<key>CFBundleName</key>"))
        assertTrue(r.text!!.contains("\$(PRODUCT_NAME)"))
    }

    @Test
    fun `nested element inside a managed key aborts the plan`() {
        val original = plist("\t<key><string>CFBundleName</string></key>\n\t<string>Hardcoded</string>")

        val r = sanitizeInfoPlist(original, listOf(nameEntry), emptyList(), PlistConflictPolicy.REPLACE)

        assertNull(r.text)
        assertTrue(r.errors.any { it.contains("key") && it.contains("text only") }, r.errors.toString())
    }

    @Test
    fun `nested element inside a managed string aborts the plan`() {
        val original = plist("\t<key>CFBundleName</key>\n\t<string><foo>\$(PRODUCT_NAME)</foo></string>")

        val r = sanitizeInfoPlist(original, listOf(nameEntry), emptyList(), PlistConflictPolicy.REPLACE)

        assertNull(r.text)
        assertTrue(r.errors.any { it.contains("nested elements") }, r.errors.toString())
    }

    @Test
    fun `non-empty managed boolean aborts the plan`() {
        val original = plist("\t<key>ITSAppUsesNonExemptEncryption</key>\n\t<true>garbage</true>")

        val r = sanitizeInfoPlist(original, emptyList(), listOf(encEntry), PlistConflictPolicy.REPLACE)

        assertNull(r.text)
        assertTrue(r.errors.any { it.contains("non-empty") }, r.errors.toString())
    }

    @Test
    fun `internal entity declaration is rejected before parsing`() {
        val unsafe = """<?xml version="1.0"?><!DOCTYPE plist [<!ENTITY x "boom">]><plist><dict/></plist>"""
        val r = sanitizeInfoPlist(unsafe, listOf(nameEntry), emptyList())
        assertNull(r.text)
        assertTrue(r.errors.any { it.contains("entity", ignoreCase = true) }, r.errors.toString())
    }

    @Test
    fun `plist size budget measures UTF-8 bytes rather than characters`() {
        val oversizedUtf8 = plist("\t<!--${"é".repeat(2_100_000)}-->")

        val r = sanitizeInfoPlist(oversizedUtf8, listOf(nameEntry), emptyList())

        assertNull(r.text)
        assertTrue(r.errors.any { it.contains("4 MiB UTF-8") }, r.errors.toString())
    }

    @Test
    fun `keep policy preserves conflict and reports it`() {
        val original = plist("\t<key>CFBundleName</key>\n\t<string>Hardcoded</string>")
        val r = sanitizeInfoPlist(original, listOf(nameEntry), emptyList(), PlistConflictPolicy.KEEP)
        assertNull(r.text)
        assertTrue(r.errors.isEmpty())
        assertTrue(r.warnings.any { it.contains("KEEP") })
    }

    @Test
    fun `replace policy converges a string conflict`() {
        val r = sanitizeInfoPlist(
            plist("\t<key>CFBundleName</key>\n\t<string>Hardcoded</string>"),
            listOf(nameEntry), emptyList(), PlistConflictPolicy.REPLACE,
        )
        assertNotNull(r.text)
        assertTrue("CFBundleName" in r.overwritten)
        assertTrue(r.text!!.contains("\$(PRODUCT_NAME)"))
    }
}
