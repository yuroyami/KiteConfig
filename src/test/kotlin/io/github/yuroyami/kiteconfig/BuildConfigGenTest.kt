package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildConfigGenTest {

    /** Everything Gradle would print for [failure], including wrapped causes. */
    private fun reportedText(failure: Throwable): String = generateSequence(failure, Throwable::cause)
        .mapNotNull(Throwable::message)
        .joinToString("\n")

    @Test
    fun `a field whose provider has no value names that field instead of voiding the list`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.objects.newInstance(KiteConfigBuildConfigExtension::class.java)
        extension.fields.convention(emptyList())
        extension.stringField("BASE_URL", "https://example.invalid")
        // -PpublicChannel was never passed, so this provider has no value. Gradle's
        // ListProperty.add(Provider) makes the WHOLE list absent in that case, which
        // used to surface far away as "customFields doesn't have a configured value".
        extension.stringField("CHANNEL", project.providers.gradleProperty("publicChannel"))

        // Gradle wraps a throwing provider in PropertyQueryException, so assert on the
        // whole chain: that is the text a consumer actually reads in the build output.
        val reported = reportedText(assertThrows(Exception::class.java) { extension.fields.get() })
        assertTrue(reported.contains("CHANNEL"), reported)
        assertTrue(reported.contains("orElse"), reported)
        assertTrue(reported.contains("buildConfig"), reported)
    }

    @Test
    fun `a field whose provider has a value is emitted normally`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.objects.newInstance(KiteConfigBuildConfigExtension::class.java)
        extension.fields.convention(emptyList())
        extension.stringField("CHANNEL", project.providers.provider { "stable" })

        assertEquals(listOf("CHANNEL: String = \"stable\""), extension.fields.get())
    }

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
            "kiteconfig.generated", "Cfg", false, "", "", 0, "", "", emptyList(),
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
        assertThrows(IllegalArgumentException::class.java) { buildConfigFieldLine("Int", "object", "5") }
        assertThrows(IllegalArgumentException::class.java) { buildConfigFieldLine("Int", "_", "5") }
        assertEquals("OK_1: Int = 5", buildConfigFieldLine("Int", "OK_1", "5"))
    }

    @Test
    fun `string literal escaping produces a safe single literal`() {
        val lit = kotlinStringLiteral("a\"b\\c\$d\u0000\u000C\u001B\u2028\u2029\uD800")
        assertTrue(lit.startsWith("\"") && lit.endsWith("\""), lit)
        assertTrue(lit.contains("\\\""), lit)
        assertTrue(lit.contains("\\\\"), lit)
        assertTrue(lit.contains("\\\$d"), lit)
        assertTrue(lit.contains("\\u0000"), lit)
        assertTrue(lit.contains("\\u000C"), lit)
        assertTrue(lit.contains("\\u001B"), lit)
        assertTrue(lit.contains("\\u2028"), lit)
        assertTrue(lit.contains("\\u2029"), lit)
        assertTrue(lit.contains("\\uD800"), lit)
        assertFalse(
            lit.any { it == '\u0000' || it == '\u000C' || it == '\u001B' || it == '\u2028' || it == '\u2029' },
            lit,
        )
    }

    @Test
    fun `rejects duplicate custom fields and identity collisions`() {
        assertThrows(IllegalArgumentException::class.java) {
            source(customFields = listOf("FLAG: Boolean = true", "FLAG: Boolean = false"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            source(customFields = listOf("versionCode: Int = 7"))
        }
        // The identity names are available to a fields-only object.
        assertTrue(
            source(includeIdentity = false, customFields = listOf("versionCode: Int = 7"))
                .contains("public const val versionCode: Int = 7")
        )
    }

    @Test
    fun `rejects non-finite doubles and arbitrary source fragments`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildConfigFieldLine("Double", "BAD", "NaN")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildConfigFieldLine("Double", "BAD", "Infinity")
        }
        assertThrows(IllegalArgumentException::class.java) {
            source(customFields = listOf("X: String = \"safe\"; error(\"injected\")"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            source(customFields = listOf("X: Any = malicious()"))
        }
    }

    @Test
    fun `legacy field transport is parsed and rendered canonically`() {
        assertEquals("COUNT: Int = 5", parseBuildConfigField("COUNT : Int= 5").renderBody())
        assertEquals("LIMIT: Long = 9L", parseBuildConfigField("LIMIT: Long = 9").renderBody())
        assertEquals(
            "TEXT: String = \"a\\n\\\$b\"",
            parseBuildConfigField("TEXT: String = \"a\\n\\\$b\"").renderBody(),
        )
    }

    @Test
    fun `renders minimum integral constants as compilable Kotlin`() {
        assertEquals("MIN_INT: Int = Int.MIN_VALUE", BuildConfigField.IntValue("MIN_INT", Int.MIN_VALUE).renderBody())
        assertEquals("MIN_LONG: Long = Long.MIN_VALUE", BuildConfigField.LongValue("MIN_LONG", Long.MIN_VALUE).renderBody())
        assertTrue(
            source(
                includeIdentity = false,
                customFields = listOf("MIN_LONG: Long = -9223372036854775808L"),
            ).contains("public const val MIN_LONG: Long = Long.MIN_VALUE"),
        )
    }

    @Test
    fun `bounds custom field count and string payloads`() {
        assertThrows(IllegalArgumentException::class.java) {
            source(includeIdentity = false, customFields = List(513) { "F$it: Int = $it" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            BuildConfigField.StringValue("HUGE", "x".repeat(10_001))
        }
    }

    @Test
    fun `validates package and class at the final generation boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            source(packageName = "com._.gen")
        }
        assertThrows(IllegalArgumentException::class.java) {
            source(className = "class")
        }
    }

    private fun source(
        packageName: String = "com.acme.gen",
        className: String = "BuildConfig",
        includeIdentity: Boolean = true,
        customFields: List<String> = emptyList(),
    ): String = generateBuildConfigSource(
        packageName = packageName,
        className = className,
        includeIdentity = includeIdentity,
        appName = "Demo",
        versionName = "1.2.3",
        versionCode = 1,
        androidApplicationId = "com.acme",
        iosBundleId = "com.acme",
        locales = listOf("en"),
        customFields = customFields,
    )
}
