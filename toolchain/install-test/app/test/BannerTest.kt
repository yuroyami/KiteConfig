package io.github.yuroyami.kiteconfig.installtest

import kotlin.test.Test
import kotlin.test.assertEquals

class BannerTest {

    @Test
    fun `the generated values follow the plugin settings`() {
        assertEquals("Install Test 1.2.3 (1001002030)", banner())
    }
}
