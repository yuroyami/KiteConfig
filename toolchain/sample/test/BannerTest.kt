package io.github.yuroyami.kiteconfig.sample

import kiteconfig.generated.KiteBuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class BannerTest {

    @Test
    fun theGeneratedValuesFollowThePluginSettings() {
        assertEquals("Kite Sample 4.1.1 (1004001011)", banner())
        assertEquals(listOf("en", "pt-BR"), KiteBuildConfig.locales)
        assertEquals("https://example.com/api", KiteBuildConfig.API_URL)
    }
}
