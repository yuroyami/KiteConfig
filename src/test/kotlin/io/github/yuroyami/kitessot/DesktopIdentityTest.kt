package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopIdentityTest {

    @Test
    fun `a normal version passes every format`() {
        val formats = setOf("Msi", "Exe", "Dmg", "Pkg", "Deb", "Rpm", "AppImage")
        assertEquals("1.4.0", validateDesktopPackageVersion("1.4.0", formats))
    }

    @Test
    fun `a minor above the Windows cap fails and names the limits`() {
        val failure = assertThrows(GradleException::class.java) {
            validateDesktopPackageVersion("1.300.0", setOf("Msi"))
        }
        assertTrue(failure.message!!.contains("1.300.0"), failure.message)
        assertTrue(failure.message!!.contains("255"), failure.message)
        assertTrue(failure.message!!.contains("targetFormats"), failure.message)
    }

    @Test
    fun `the same version passes when no Windows format is requested`() {
        assertEquals("1.300.0", validateDesktopPackageVersion("1.300.0", setOf("Dmg", "Deb")))
    }

    @Test
    fun `a build above the Windows cap fails`() {
        assertThrows(GradleException::class.java) {
            validateDesktopPackageVersion("1.0.70000", setOf("Exe"))
        }
    }

    @Test
    fun `the Linux slug lowercases and replaces punctuation`() {
        assertEquals("jetzy", deriveLinuxPackageName("Jetzy"))
        assertEquals("my-app", deriveLinuxPackageName("My App"))
        assertEquals("acme-tool", deriveLinuxPackageName("Acme_Tool"))
    }

    @Test
    fun `a slug that cannot start with an alphanumeric fails and names the escape hatch`() {
        val failure = assertThrows(GradleException::class.java) { deriveLinuxPackageName("!!!") }
        assertTrue(failure.message!!.contains("desktop { linuxPackageName }"), failure.message)
    }

    @Test
    fun `a slug under Debian's two-character minimum fails and names the escape hatch`() {
        val failure = assertThrows(GradleException::class.java) { deriveLinuxPackageName("X") }
        assertTrue(failure.message!!.contains("desktop { linuxPackageName }"), failure.message)
    }

    @Test
    fun `the derived upgrade uuid is stable and depends on appId`() {
        assertEquals(deriveUpgradeUuid("com.acme.app"), deriveUpgradeUuid("com.acme.app"))
        assertNotEquals(deriveUpgradeUuid("com.acme.app"), deriveUpgradeUuid("com.acme.other"))
        assertTrue(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                .matches(deriveUpgradeUuid("com.acme.app")),
            deriveUpgradeUuid("com.acme.app"),
        )
    }
}
