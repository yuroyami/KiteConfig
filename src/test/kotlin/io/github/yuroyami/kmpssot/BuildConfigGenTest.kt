package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildConfigGenTest {

    @Test
    fun `emits identity and custom fields under the chosen class name`() {
        val src = generateBuildConfigSource(
            packageName = "com.acme.gen",
            className = "BuildConfig",
            includeIdentity = true,
            appName = "Demo",
            versionName = "1.2.3",
            versionCode = 1001002003,
            androidApplicationId = "com.acme.app",
            iosBundleId = "com.acme.app.ios",
            locales = listOf("en"),
            customFields = listOf("BASE_URL: String = \"https://x\"", "TIMEOUT: Int = 30000"),
        )
        assertTrue(src.contains("package com.acme.gen"), src)
        assertTrue(src.contains("public object BuildConfig {"), src)
        assertTrue(src.contains("public const val versionCode: Int = 1001002003"), src)
        assertTrue(src.contains("public const val BASE_URL: String = \"https://x\""), src)
        assertTrue(src.contains("public const val TIMEOUT: Int = 30000"), src)
        assertTrue(src.contains("DO NOT EDIT"), src)
    }

    @Test
    fun `includeIdentity false emits only custom fields`() {
        val src = generateBuildConfigSource(
            "kmpssot.generated", "Cfg", false, "", "", 0, "", "", emptyList(),
            listOf("FLAG: Boolean = true"),
        )
        assertFalse(src.contains("appName"), src)
        assertTrue(src.contains("public object Cfg {"), src)
        assertTrue(src.contains("public const val FLAG: Boolean = true"), src)
    }

    @Test
    fun `field line requires a valid identifier`() {
        assertThrows(IllegalArgumentException::class.java) { buildConfigFieldLine("String", "not valid", "\"x\"") }
        assertThrows(IllegalArgumentException::class.java) { buildConfigFieldLine("Int", "1abc", "5") }
        assertEquals("OK_1: Int = 5", buildConfigFieldLine("Int", "OK_1", "5"))
    }

    @Test
    fun `string literal escaping produces a safe single literal`() {
        val lit = kotlinStringLiteral("a\"b\\c\$d")
        assertTrue(lit.startsWith("\"") && lit.endsWith("\""), lit)
        assertTrue(lit.contains("\\\""), lit)
        assertTrue(lit.contains("\\\\"), lit)
        assertTrue(lit.contains("\\\$d"), lit)
    }
}
