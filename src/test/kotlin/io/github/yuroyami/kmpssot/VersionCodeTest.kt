package io.github.yuroyami.kmpssot

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class VersionCodeTest {

    @Test
    fun `derives three-segment versions`() {
        assertEquals(1000003000, deriveVersionCode("0.3.0"))
        assertEquals(1002003004, deriveVersionCode("2.3.4"))
        assertEquals(1999999999, deriveVersionCode("999.999.999"))
    }

    @Test
    fun `derives short forms`() {
        assertEquals(1001, deriveVersionCode("1"))
        assertEquals(1001002, deriveVersionCode("1.2"))
    }

    @Test
    fun `rejects non-numeric segments`() {
        assertThrows(GradleException::class.java) { deriveVersionCode("1.2.3-rc1") }
        assertThrows(GradleException::class.java) { deriveVersionCode("v1.0.0") }
        assertThrows(GradleException::class.java) { deriveVersionCode("1.0.0+build") }
    }

    @Test
    fun `rejects more than three segments`() {
        assertThrows(GradleException::class.java) { deriveVersionCode("1.2.3.4") }
    }

    @Test
    fun `rejects a segment greater than 999`() {
        assertThrows(GradleException::class.java) { deriveVersionCode("1.0.1000") }
    }
}
