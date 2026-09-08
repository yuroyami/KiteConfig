package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionCodeTest {

    /** The Android path: the scheme's text read back as the one integer Play wants. */
    private fun derive(version: String, rebuild: Int = 0): Int =
        androidVersionCodeFrom(computeBuildNumber(VersionSchemes.DEFAULT, version, rebuild, "android"))

    @Test
    fun `default scheme packs major minor patch and rebuild`() {
        assertEquals(1001004000, derive("1.4.0"))
        assertEquals(1001004010, derive("1.4.1"))
        assertEquals(1001005000, derive("1.5.0"))
        assertEquals(1000003000, derive("0.3.0"))
        assertEquals(1999999999, derive("999.999.99", rebuild = 9))
    }

    /** The digits must not move: an installed app's numbering cannot change under it. */
    @Test
    fun `the default scheme still produces the numbers it always did`() {
        assertEquals("1001004000", VersionSchemes.DEFAULT.compute(ConfigVersion(1, 4, 0, 0)))
        assertEquals("1001004001", VersionSchemes.DEFAULT.compute(ConfigVersion(1, 4, 0, 1)))
        assertEquals("1999999999", VersionSchemes.DEFAULT.compute(ConfigVersion(999, 999, 99, 9)))
    }

    @Test
    fun `rebuild claims the units digit without disturbing the next patch`() {
        assertEquals(1001004001, derive("1.4.0", rebuild = 1))
        assertEquals(1001004009, derive("1.4.0", rebuild = 9))
        // Every version owns ten numbers, so a rebuild can never reach the next patch.
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
        val patch = assertThrows(GradleException::class.java) { derive("1.0.100") }
        assertTrue(patch.message.orEmpty().contains("patch"))
        assertTrue(patch.message.orEmpty().contains("scheme"), patch.message.orEmpty())

        val overflow = assertThrows(GradleException::class.java) { derive("1.0.0", rebuild = 10) }
        assertTrue(overflow.message.orEmpty().contains("rebuild"))

        assertThrows(GradleException::class.java) { derive("1000.0.0") }
    }

    @Test
    fun `a scheme you wrote replaces the layout entirely`() {
        val compact = VersionCodeScheme { v ->
            (1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.rebuild).toString()
        }
        assertEquals("1040000", computeBuildNumber(compact, "1.4.0", 0, "android"))
        assertEquals("1040107", computeBuildNumber(compact, "1.4.1", 7, "android"))
        // A scheme you wrote is not held to the default layout's digit budget.
        assertEquals("1000900", computeBuildNumber(compact, "1.0.9", 0, "android"))
    }

    /**
     * The stores disagree about what a build number looks like, so the shared
     * contract is text and each platform narrows it.
     */
    @Test
    fun `a dotted build number is fine for Apple and refused by Android`() {
        val appleStyle = VersionCodeScheme { v -> "${v.major}.${v.minor}.${v.patch * 10 + v.rebuild}" }

        assertEquals("1.4.1", computeBuildNumber(appleStyle, "1.4.0", 1, "ios"))
        assertTrue(isValidAppleBuildNumber(computeBuildNumber(appleStyle, "1.4.0", 1, "ios")))

        val failure = assertThrows(GradleException::class.java) {
            androidVersionCodeFrom(computeBuildNumber(appleStyle, "1.4.0", 1, "android"))
        }
        assertTrue(failure.message.orEmpty().contains("one whole number"), failure.message.orEmpty())
        assertTrue(failure.message.orEmpty().contains("android { version { buildNumber"), failure.message.orEmpty())
    }

    @Test
    fun `an Android number outside the Play range fails with guidance`() {
        val tooBig = VersionCodeScheme { "2100000001" }
        val failure = assertThrows(GradleException::class.java) {
            androidVersionCodeFrom(computeBuildNumber(tooBig, "1.0.0", 0, "android"))
        }
        assertTrue(failure.message.orEmpty().contains("2100000000"))
        assertThrows(GradleException::class.java) {
            androidVersionCodeFrom(computeBuildNumber(VersionCodeScheme { "0" }, "1.0.0", 0, "android"))
        }
    }

    @Test
    fun `an empty scheme result is refused rather than delivered`() {
        val failure = assertThrows(GradleException::class.java) {
            computeBuildNumber(VersionCodeScheme { "" }, "1.0.0", 0, "ios")
        }
        assertTrue(failure.message.orEmpty().contains("empty"), failure.message.orEmpty())
    }

    @Test
    fun `every default scheme result is a legal Apple build number`() {
        listOf("0.1.0", "1.0.0", "1.4.1", "12.7.30", "999.999.99").forEach { version ->
            listOf(0, 1, 9).forEach { rebuild ->
                val number = computeBuildNumber(VersionSchemes.DEFAULT, version, rebuild, "ios")
                assertTrue(
                    isValidAppleBuildNumber(number),
                    "scheme produced $number for $version+$rebuild, which Apple validation rejects",
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
        assertEquals(101, validatePublishedVersionCode(101, "100"))
        assertThrows(GradleException::class.java) { validatePublishedVersionCode(null, "100") }
        assertThrows(GradleException::class.java) { validatePublishedVersionCode(100, "100") }

        val invalidBaseline = assertThrows(GradleException::class.java) {
            validatePublishedVersionCode(101, "0")
        }
        assertTrue(
            invalidBaseline.message.orEmpty().contains("android { version { publishedBuildNumber } }"),
            invalidBaseline.message.orEmpty(),
        )

        val notANumber = assertThrows(GradleException::class.java) {
            validatePublishedVersionCode(101, "1.0.0")
        }
        assertTrue(notANumber.message.orEmpty().contains("one whole number"), notANumber.message.orEmpty())
    }

    @Test
    fun `a desktop failure names the desktop override, not the android one`() {
        val failure = assertThrows(GradleException::class.java) {
            computeBuildNumber(VersionSchemes.DEFAULT, "1.2.3-rc1", 0, "desktop")
        }
        assertTrue(failure.message!!.contains("desktop { version { buildNumber"), failure.message)
        assertFalse(failure.message!!.contains("android { version { buildNumber"), failure.message)
    }
}
