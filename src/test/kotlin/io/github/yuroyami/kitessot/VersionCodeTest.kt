package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionCodeTest {

    private fun derive(version: String, reupload: Int = 0): Int =
        computeVersionCode(VersionSchemes.DEFAULT, version, reupload, "android")

    @Test
    fun `default scheme packs major minor patch and reupload`() {
        assertEquals(1001004000, derive("1.4.0"))
        assertEquals(1001004010, derive("1.4.1"))
        assertEquals(1001005000, derive("1.5.0"))
        assertEquals(1000003000, derive("0.3.0"))
        assertEquals(1999999999, derive("999.999.99", reupload = 9))
    }

    @Test
    fun `reupload claims the units digit without disturbing the next patch`() {
        assertEquals(1001004001, derive("1.4.0", reupload = 1))
        assertEquals(1001004009, derive("1.4.0", reupload = 9))
        // Every version owns ten codes, so a re-upload can never reach the next patch.
        assertTrue(derive("1.4.0", reupload = 9) < derive("1.4.1"))
        // A version bump outranks any re-upload before it, so reupload never needs resetting.
        assertTrue(derive("1.4.1") > derive("1.4.0", reupload = 9))
    }

    @Test
    fun `rejects short forms because their width is not monotonic`() {
        assertThrows(GradleException::class.java) { derive("1") }
        assertThrows(GradleException::class.java) { derive("1.2") }
    }

    @Test
    fun `rejects non-numeric segments`() {
        assertThrows(GradleException::class.java) { derive("1.2.3-rc1") }
        assertThrows(GradleException::class.java) { derive("v1.0.0") }
        assertThrows(GradleException::class.java) { derive("1.0.0+build") }
        assertThrows(GradleException::class.java) { derive("x".repeat(256)) }
    }

    @Test
    fun `rejects more than three segments`() {
        assertThrows(GradleException::class.java) { derive("1.2.3.4") }
    }

    @Test
    fun `rejects ambiguous leading zeroes`() {
        assertThrows(GradleException::class.java) { derive("01.2.3") }
        assertThrows(GradleException::class.java) { derive("1.02.3") }
        assertThrows(GradleException::class.java) { derive("1.2.003") }
    }

    @Test
    fun `default scheme states its digit budget instead of overflowing`() {
        // patch is two digits in 3.0 so that reupload can have one.
        val patch = assertThrows(GradleException::class.java) { derive("1.0.100") }
        assertTrue(patch.message.orEmpty().contains("patch"))
        assertTrue(patch.message.orEmpty().contains("scheme"), patch.message.orEmpty())

        // The message still names the `android { rebuild }` dial, which stays as it is here.
        val overflow = assertThrows(GradleException::class.java) { derive("1.0.0", reupload = 10) }
        assertTrue(overflow.message.orEmpty().contains("rebuild"))

        assertThrows(GradleException::class.java) { derive("1000.0.0") }
    }

    @Test
    fun `a custom scheme replaces the layout entirely`() {
        val compact = VersionCodeScheme { v ->
            1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.reupload
        }
        assertEquals(1_040_000, computeVersionCode(compact, "1.4.0", 0, "android"))
        assertEquals(1_040_107, computeVersionCode(compact, "1.4.1", 7, "android"))
        // Custom schemes are not held to the default layout's digit budget.
        assertEquals(1_000_900, computeVersionCode(compact, "1.0.9", 0, "android"))
    }

    @Test
    fun `a resolved code outside the Play range fails with guidance`() {
        val tooBig = VersionCodeScheme { 2_100_000_001 }
        val failure = assertThrows(GradleException::class.java) {
            computeVersionCode(tooBig, "1.0.0", 0, "android")
        }
        assertTrue(failure.message.orEmpty().contains("2100000000"))
        assertThrows(GradleException::class.java) {
            computeVersionCode(VersionCodeScheme { 0 }, "1.0.0", 0, "android")
        }
    }

    @Test
    fun `every scheme result is a legal Apple build number`() {
        // iOS follows the same ordinal Android does, so the two rules have to agree.
        // An earlier four-digit cap in the Apple validator silently rejected it.
        listOf("0.1.0", "1.0.0", "1.4.1", "12.7.30", "999.999.99").forEach { version ->
            listOf(0, 1, 9).forEach { reupload ->
                val ordinal = computeVersionCode(VersionSchemes.DEFAULT, version, reupload, "ios")
                assertTrue(
                    isValidAppleBuildNumber(ordinal.toString()),
                    "scheme produced $ordinal for $version+$reupload, which Apple validation rejects",
                )
            }
        }
    }

    @Test
    fun `validates explicit Play version code range`() {
        assertEquals(1, validateVersionCode(1))
        assertEquals(2_100_000_000, validateVersionCode(2_100_000_000))
        assertThrows(GradleException::class.java) { validateVersionCode(0) }
        assertThrows(GradleException::class.java) { validateVersionCode(-1) }
        assertThrows(GradleException::class.java) { validateVersionCode(Int.MAX_VALUE) }
    }

    @Test
    fun `published baseline is labeled and must precede the next code`() {
        assertEquals(101, validatePublishedVersionCode(101, 100))
        assertThrows(GradleException::class.java) { validatePublishedVersionCode(null, 100) }
        assertThrows(GradleException::class.java) { validatePublishedVersionCode(100, 100) }

        val invalidBaseline = assertThrows(GradleException::class.java) {
            validatePublishedVersionCode(101, 0)
        }
        assertTrue(invalidBaseline.message.orEmpty().contains("version { android { shipped } }"))
    }

    @Test
    fun `a desktop failure names the desktop override, not the android one`() {
        val failure = assertThrows(GradleException::class.java) {
            computeVersionCode(VersionSchemes.DEFAULT, "1.2.3-rc1", 0, "desktop")
        }
        assertTrue(failure.message!!.contains("desktop { buildNumber"), failure.message)
    }
}
