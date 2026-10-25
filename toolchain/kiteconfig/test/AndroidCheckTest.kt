package io.github.yuroyami.kiteconfig.toolchain

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidCheckTest {

    private fun check(
        applicationId: String? = "com.acme.app",
        versionCode: Int? = 1004001011,
        versionName: String? = "4.1.1",
        checkSettings: Boolean = true,
    ) = checkAndroidSettings(
        checkSettings = checkSettings, version = "4.1.1", rebuild = 1, buildNumber = null,
        appId = "com.acme.app", androidApplicationId = null,
        applicationId = applicationId, versionCode = versionCode, versionName = versionName,
    )

    @Test
    fun `matching values pass`() {
        check()
    }

    @Test
    fun `a different versionCode fails and prints the line to write`() {
        val failure = assertFailsWith<IllegalStateException> { check(versionCode = 1004001010) }
        val message = failure.message.orEmpty()
        assertTrue("    versionCode: 1004001011" in message, message)
        assertTrue("Found: versionCode: 1004001010." in message, message)
        assertTrue("versionName" !in message, message)
    }

    @Test
    fun `an unset value fails and names the missing line`() {
        val failure = assertFailsWith<IllegalStateException> { check(versionCode = 1, versionName = "unspecified") }
        val message = failure.message.orEmpty()
        assertTrue("    versionCode: 1004001011" in message, message)
        assertTrue("    versionName: 4.1.1" in message, message)
    }

    @Test
    fun `a different application id fails`() {
        val failure = assertFailsWith<IllegalStateException> { check(applicationId = "com.acme.other") }
        assertTrue("    applicationId: com.acme.app" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `the check is skipped unless checkSettings is on`() {
        check(applicationId = "com.acme.library", versionCode = 1, versionName = "unspecified", checkSettings = false)
    }
}
