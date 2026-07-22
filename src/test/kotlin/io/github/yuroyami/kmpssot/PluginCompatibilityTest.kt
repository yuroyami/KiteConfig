package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginCompatibilityTest {
    @Test
    fun `AGP support is explicit and bounded`() {
        assertFalse(isSupportedAgpVersion("8.5.1"))
        assertFalse(isSupportedAgpVersion("8.5"))
        assertTrue(isSupportedAgpVersion("8.5.2"))
        assertFalse(isSupportedAgpVersion("9.1"))
        assertTrue(isSupportedAgpVersion("9.1.1"))
        assertFalse(isSupportedAgpVersion("9.1.1-alpha01"))
        assertFalse(isSupportedAgpVersion("9.2.0"))
        assertFalse(isSupportedAgpVersion("unknown"))
    }

    @Test
    fun `KGP support is explicit and bounded`() {
        assertFalse(isSupportedKgpVersion("2.3.20"))
        assertFalse(isSupportedKgpVersion("2.4"))
        assertFalse(isSupportedKgpVersion("2.4-release-281"))
        assertTrue(isSupportedKgpVersion("2.4.0"))
        assertTrue(isSupportedKgpVersion("2.4.0-release-281"))
        assertTrue(isSupportedKgpVersion("2.4.10"))
        assertFalse(isSupportedKgpVersion("2.4.10-RC"))
        assertFalse(isSupportedKgpVersion("2.4.0-Beta2"))
        assertFalse(isSupportedKgpVersion("2.4.0-dev-1234"))
        assertFalse(isSupportedKgpVersion("2.4.0-release-candidate"))
        assertFalse(isSupportedKgpVersion("2.4.0-release-281-extra"))
        assertFalse(isSupportedKgpVersion("2.5.0"))
    }
}
