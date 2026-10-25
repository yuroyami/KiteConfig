package io.github.yuroyami.kiteconfig.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigValidationTest {
    @Test
    fun `logo safe-zone ratio is finite positive and contained by the canvas`() {
        assertEquals(66.0 / 108.0, validateLogoSafeZoneRatio(66.0 / 108.0))
        assertEquals(1.0, validateLogoSafeZoneRatio(1.0))
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, 0.0, -0.1, 1.000_001).forEach { value ->
            assertThrows(KiteConfigException::class.java) { validateLogoSafeZoneRatio(value) }
        }
    }

    @Test
    fun `validates platform identifiers independently`() {
        assertEquals("com.acme.app", validateAndroidApplicationId("com.acme.app"))
        assertEquals("com.acme.my-app", validateAppleBundleId("com.acme.my-app"))
        assertThrows(KiteConfigException::class.java) { validateAndroidApplicationId("single") }
        assertThrows(KiteConfigException::class.java) { validateAndroidApplicationId("com.2app") }
        assertThrows(KiteConfigException::class.java) { validateAppleBundleId("com.acme.bad_id") }
    }

    @Test
    fun `version name is bounded printable and nonblank`() {
        assertEquals("1.2.3-beta", validateVersionName("1.2.3-beta"))
        listOf("", "   ", "bad\nvalue", "x".repeat(256)).forEach { value ->
            assertThrows(KiteConfigException::class.java) { validateVersionName(value) }
        }
    }

    @Test
    fun `validates Apple versions`() {
        assertEquals("1.2.3", validateAppleMarketingVersion("1.2.3"))
        assertEquals("42.1", validateAppleBuildNumber("42.1"))
        assertEquals("9999.99.99", validateAppleBuildNumber("9999.99.99"))
        assertEquals("0001.00.00", validateAppleBuildNumber("0001.00.00"))
        // 3.0 follows the shared scheme, whose ordinal is ten digits wide.
        assertEquals("1001004000", validateAppleBuildNumber("1001004000"))
        assertEquals("1001004000.7", validateAppleBuildNumber("1001004000.7"))
        assertEquals("20260819.1.2", validateAppleBuildNumber("20260819.1.2"))
        assertThrows(KiteConfigException::class.java) { validateAppleMarketingVersion("1.2") }
        assertThrows(KiteConfigException::class.java) { validateAppleMarketingVersion("1.2.3-rc1") }
        assertThrows(KiteConfigException::class.java) { validateAppleBuildNumber("1.2.3.4") }
        assertThrows(KiteConfigException::class.java) { validateAppleBuildNumber("0") }
        assertThrows(KiteConfigException::class.java) { validateAppleBuildNumber("0000") }
        assertThrows(KiteConfigException::class.java) { validateAppleBuildNumber("12345678901") }
        assertThrows(KiteConfigException::class.java) { validateAppleBuildNumber("1.2.3.4") }
        val hostileBuildNumber = assertThrows(KiteConfigException::class.java) {
            validateAppleBuildNumber("1\n" + "9".repeat(10_000))
        }
        assertTrue(hostileBuildNumber.message.orEmpty().length < 500)
        assertTrue(!hostileBuildNumber.message.orEmpty().contains('\n'))
        assertEquals("12.0", validateUniversalAppIconDeploymentTarget("12.0"))
        assertEquals("18.2.1", validateUniversalAppIconDeploymentTarget("18.2.1"))
        assertThrows(KiteConfigException::class.java) { validateUniversalAppIconDeploymentTarget("11.9") }
        assertThrows(KiteConfigException::class.java) { validateUniversalAppIconDeploymentTarget("012.0") }
        assertThrows(KiteConfigException::class.java) {
            validateUniversalAppIconDeploymentTarget("999999999999999999999999.0")
        }
    }

    @Test
    fun `validates SDK relationships`() {
        validateSdkLevels(36, 26, 36)
        assertThrows(KiteConfigException::class.java) { validateSdkLevels(35, 36, 35) }
        assertThrows(KiteConfigException::class.java) { validateSdkLevels(35, 21, 36) }
        assertThrows(KiteConfigException::class.java) { validateSdkLevels(35, 0, 35) }
        assertThrows(KiteConfigException::class.java) { validateSdkLevels(10_001, 21, 35) }
    }

    @Test
    fun `project relative paths reject cross platform escapes`() {
        assertEquals(
            "iosApp/iosApp/Info.plist",
            validateRelativeProjectPath("iosApp/iosApp/Info.plist", "iosInfoPlistPath"),
        )
        listOf("../outside", "iosApp/../outside", "/tmp/out", "C:\\tmp\\out", "iosApp//Info.plist")
            .forEach { path ->
                assertThrows(KiteConfigException::class.java) {
                    validateRelativeProjectPath(path, "iosInfoPlistPath")
                }
            }
        assertThrows(KiteConfigException::class.java) {
            validateRelativeProjectPath("x".repeat(4_097), "iosInfoPlistPath")
        }
    }

    @Test
    fun `a desktop published build number failure names desktop, not iOS`() {
        val missingCandidate = assertThrows(KiteConfigException::class.java) {
            validatePublishedBuildNumber(null, "5", platform = "desktop")
        }
        assertTrue(missingCandidate.message!!.contains("desktop {"), missingCandidate.message)
        assertTrue(!missingCandidate.message!!.contains("ios {"), missingCandidate.message)

        val notGreater = assertThrows(KiteConfigException::class.java) {
            validatePublishedBuildNumber("5", "5", platform = "desktop")
        }
        assertTrue(notGreater.message!!.contains("desktop {"), notGreater.message)
        assertTrue(!notGreater.message!!.contains("ios {"), notGreater.message)
    }
}
