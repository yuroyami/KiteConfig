package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildInfoGenTest {

    @Test
    fun `emits identity as constants`() {
        val src = generateBuildInfoSource(
            "com.acme.gen", "Demo", "1.2.3", 1001002003, "com.acme.app", "com.acme.app.ios", listOf("en", "fr"),
        )
        assertTrue(src.contains("package com.acme.gen"), src)
        assertTrue(src.contains("public const val appName: String = \"Demo\""), src)
        assertTrue(src.contains("public const val versionName: String = \"1.2.3\""), src)
        assertTrue(src.contains("public const val versionCode: Int = 1001002003"), src)
        assertTrue(src.contains("public const val androidApplicationId: String = \"com.acme.app\""), src)
        assertTrue(src.contains("public val locales: List<String> = listOf(\"en\", \"fr\")"), src)
        assertTrue(src.contains("DO NOT EDIT"), src)
    }

    @Test
    fun `unset fields render as empty and zero`() {
        val src = generateBuildInfoSource("kmpssot.generated", "", "", 0, "", "", emptyList())
        assertTrue(src.contains("appName: String = \"\""), src)
        assertTrue(src.contains("versionCode: Int = 0"), src)
        assertTrue(src.contains("locales: List<String> = listOf()"), src)
    }

    @Test
    fun `string literal escaping produces a safe single literal`() {
        val lit = kotlinStringLiteral("a\"b\\c\$d")
        assertTrue(lit.startsWith("\"") && lit.endsWith("\""), lit)
        assertTrue(lit.contains("\\\""), lit) // escaped quote
        assertTrue(lit.contains("\\\\"), lit) // escaped backslash
        assertTrue(lit.contains("\\\$d"), lit) // dollar is escaped → no live template
    }
}
