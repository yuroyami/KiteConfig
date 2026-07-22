package io.github.yuroyami.kitessot

import org.gradle.api.JavaVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Locks the string form [ClassicAndroidWiring] feeds to `JvmTarget.fromTarget`:
 * Java 8 must be "1.8" (not "8"), 17/21 are bare. A regression here would silently
 * break the Kotlin jvmTarget alignment (T11).
 */
class JavaVersionMappingTest {

    @Test
    fun `javaVersion renders the jvmTarget string form`() {
        assertEquals("1.8", JavaVersion.toVersion(8).toString())
        assertEquals("11", JavaVersion.toVersion(11).toString())
        assertEquals("17", JavaVersion.toVersion(17).toString())
        assertEquals("21", JavaVersion.toVersion(21).toString())
    }
}
