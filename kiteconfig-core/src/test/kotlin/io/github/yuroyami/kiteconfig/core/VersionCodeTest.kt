package io.github.yuroyami.kiteconfig.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VersionCodeTest {

    @Test
    fun `the default layout packs major minor patch and rebuild`() {
        assertEquals("1001004001", defaultBuildNumber(1, 4, 0, 1))
        assertEquals("1004001011", defaultBuildNumber(4, 1, 1, 1))
    }
}
