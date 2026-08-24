package io.github.yuroyami.kitessot

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
        assertTrue(isSupportedAgpVersion("9.2.0"))
        assertTrue(isSupportedAgpVersion("9.2.1"))
        assertTrue(isSupportedAgpVersion("9.3.0"))
        assertTrue(isSupportedAgpVersion("9.3.1"))
        assertFalse(isSupportedAgpVersion("9.3.1-alpha01"))
        assertFalse(isSupportedAgpVersion("9.4.0"))
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

    @Test
    fun `the compose range accepts the tested line and its pre-release builds`() {
        assertTrue(isSupportedComposeVersion("1.11.0"))
        assertTrue(isSupportedComposeVersion("1.12.0"))
        assertTrue(isSupportedComposeVersion("1.12.0-rc01"), "Compose ships long rc lines people build on")
        assertTrue(isSupportedComposeVersion("1.12.0-beta03"))
    }

    @Test
    fun `the compose range rejects versions outside it`() {
        assertFalse(isSupportedComposeVersion("1.10.9"))
        assertFalse(isSupportedComposeVersion("1.13.0"))
        assertFalse(isSupportedComposeVersion("not-a-version"))
    }
}
