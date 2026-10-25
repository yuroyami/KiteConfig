package io.github.yuroyami.kiteconfig.toolchain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VersionResolutionTest {

    @Test
    fun `the default layout matches the gradle plugin`() {
        assertEquals("1004001011", resolveBuildNumber("4.1.1", rebuild = 1, buildNumber = null))
        assertEquals("1001004000", resolveBuildNumber("1.4.0", rebuild = 0, buildNumber = null))
    }

    @Test
    fun `an explicit build number wins over the layout`() {
        assertEquals("77", resolveBuildNumber("4.1.1", rebuild = 1, buildNumber = "77"))
    }

    @Test
    fun `a pre-release version is refused with the setting to fix`() {
        val failure = assertFailsWith<IllegalArgumentException> { resolveBuildNumber("1.2.3-rc1", 0, null) }
        val message = failure.message.orEmpty()
        assertTrue("plugins: kiteconfig: version" in message && "x.y.z" in message, message)
    }

    @Test
    fun `a segment over the layout limit names the segment and its range`() {
        val failure = assertFailsWith<IllegalArgumentException> { resolveBuildNumber("1.0.100", 0, null) }
        val message = failure.message.orEmpty()
        assertTrue("patch" in message && "0..99" in message, message)
    }

    @Test
    fun `a negative rebuild names the rebuild setting`() {
        val failure = assertFailsWith<IllegalArgumentException> { resolveBuildNumber("1.0.0", -1, null) }
        val message = failure.message.orEmpty()
        assertTrue("plugins: kiteconfig: rebuild" in message, message)
    }

    @Test
    fun `android refuses a dotted or too large build number`() {
        val dotted = assertFailsWith<IllegalArgumentException> { androidVersionCode("1.2") }
        assertTrue("plugins: kiteconfig: buildNumber" in dotted.message.orEmpty(), dotted.message)
        val large = assertFailsWith<IllegalArgumentException> { androidVersionCode("2100000001") }
        assertTrue("2100000000" in large.message.orEmpty(), large.message)
    }
}
