package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionCodeTest {

    private fun derive(version: String, rebuild: Int = 0): Int =
        computeVersionCode(VersionSchemes.DEFAULT, version, rebuild, "android")

    @Test
    fun `default scheme packs major minor patch and rebuild`() {
        assertEquals(1001004000, derive("1.4.0"))
        assertEquals(1001004010, derive("1.4.1"))
        assertEquals(1001005000, derive("1.5.0"))
        assertEquals(1000003000, derive("0.3.0"))
        assertEquals(1999999999, derive("999.999.99", rebuild = 9))
    }

    @Test
    fun `rebuild claims the units digit without disturbing the next patch`() {
        assertEquals(1001004001, derive("1.4.0", rebuild = 1))
        assertEquals(1001004009, derive("1.4.0", rebuild = 9))
        // Every version owns ten codes, so a rebuild can never reach the next patch.
        assertTrue(derive("1.4.0", rebuild = 9) < derive("1.4.1"))
        // A version bump outranks any rebuild before it, so rebuild never needs resetting.
        assertTrue(derive("1.4.1") > derive("1.4.0", rebuild = 9))
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
        // patch is two digits in 3.0 so that rebuild can have one.
        val patch = assertThrows(GradleException::class.java) { derive("1.0.100") }
        assertTrue(patch.message.orEmpty().contains("patch"))
        assertTrue(patch.message.orEmpty().contains("scheme"), patch.message.orEmpty())

        val rebuild = assertThrows(GradleException::class.java) { derive("1.0.0", rebuild = 10) }
        assertTrue(rebuild.message.orEmpty().contains("rebuild"))

        assertThrows(GradleException::class.java) { derive("1000.0.0") }
    }

    @Test
    fun `a custom scheme replaces the layout entirely`() {
        val compact = VersionCodeScheme { v ->
            1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.rebuild
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
            listOf(0, 1, 9).forEach { rebuild ->
                val ordinal = computeVersionCode(VersionSchemes.DEFAULT, version, rebuild, "ios")
                assertTrue(
                    isValidAppleBuildNumber(ordinal.toString()),
                    "scheme produced $ordinal for $version+$rebuild, which Apple validation rejects",
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
        assertTrue(invalidBaseline.message.orEmpty().contains("android.publishedVersionCode"))
    }
}
