package io.github.yuroyami.kiteconfig

import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The read-only view must stay read-only. A member typed as `Property` would
 * hand a consumer a setter and reopen the write path this interface closes.
 */
class ReadBackBoundaryTest {

    private fun members() = KiteConfigValues::class.java.declaredMethods
        .filter { !it.isSynthetic }

    @Test
    fun `every member returns a Provider and never a Property`() {
        val notProviders = members()
            .filterNot { Provider::class.java.isAssignableFrom(it.returnType) }
            .map { "${it.name} returns ${it.returnType.simpleName}" }

        assertEquals(emptyList<String>(), notProviders, notProviders.joinToString("\n"))

        val leaks = members()
            .filter { Property::class.java.isAssignableFrom(it.returnType) }
            .map { it.name }

        assertEquals(emptyList<String>(), leaks, leaks.joinToString("\n"))
    }

    @Test
    fun `the interface exposes all eighteen values`() {
        val names = members().map { it.name }.toSet()

        val expected = setOf(
            "getVersion", "getVersionCode", "getIosBuildNumber",
            "getIosMarketingVersion", "getDesktopBuildNumber",
            "getAppName", "appNameFor", "getId", "getAndroidApplicationId",
            "getIosBundleId", "getDesktopBundleId",
            "getCanonicalLocales", "getJvmTarget", "getResolvedSharedProjectPath",
            "getMinSdk", "getTargetSdk", "getCompileSdk", "getNdk",
        )

        assertEquals(18, expected.size)
        assertTrue(names.containsAll(expected), "missing: ${expected - names}")
    }
}
