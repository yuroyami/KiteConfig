package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionCodeTest {

    @Test
    fun `derives three-segment versions`() {
        assertEquals(1000003000, deriveVersionCode("0.3.0"))
        assertEquals(1002003004, deriveVersionCode("2.3.4"))
        assertEquals(1999999999, deriveVersionCode("999.999.999"))
    }

    @Test
    fun `rejects short forms because their width is not monotonic`() {
        assertThrows(GradleException::class.java) { deriveVersionCode("1") }
        assertThrows(GradleException::class.java) { deriveVersionCode("1.2") }
    }

    @Test
    fun `rejects non-numeric segments`() {
        assertThrows(GradleException::class.java) { deriveVersionCode("1.2.3-rc1") }
        assertThrows(GradleException::class.java) { deriveVersionCode("v1.0.0") }
        assertThrows(GradleException::class.java) { deriveVersionCode("1.0.0+build") }
        assertThrows(GradleException::class.java) { deriveVersionCode("x".repeat(256)) }
    }

    @Test
    fun `rejects more than three segments`() {
        assertThrows(GradleException::class.java) { deriveVersionCode("1.2.3.4") }
    }

    @Test
    fun `rejects a segment greater than 999`() {
        assertThrows(GradleException::class.java) { deriveVersionCode("1.0.1000") }
    }

    @Test
    fun `rejects ambiguous leading zeroes`() {
        assertThrows(GradleException::class.java) { deriveVersionCode("01.2.3") }
        assertThrows(GradleException::class.java) { deriveVersionCode("1.02.3") }
        assertThrows(GradleException::class.java) { deriveVersionCode("1.2.003") }
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
        assertTrue(!invalidBaseline.message.orEmpty().contains("{ versionCodeOverride }"))
    }
}
