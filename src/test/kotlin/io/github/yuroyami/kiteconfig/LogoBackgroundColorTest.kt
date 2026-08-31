package io.github.yuroyami.kiteconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

class LogoBackgroundColorTest {

    @Test
    fun `accepts RRGGBB and AARRGGBB`() {
        validateLogoBackgroundColorHex("#FF5500")
        validateLogoBackgroundColorHex("#80FF5500")
    }

    @Test
    fun `rejects malformed hex`() {
        assertThrows(IllegalArgumentException::class.java) { validateLogoBackgroundColorHex("FF5500") }
        assertThrows(IllegalArgumentException::class.java) { validateLogoBackgroundColorHex("#FFF") }
        assertThrows(IllegalArgumentException::class.java) { validateLogoBackgroundColorHex("#GG0000") }
    }

    @Test
    fun `parses opaque RRGGBB`() {
        val c = parseLogoBackgroundColor("#FF5500")
        assertEquals(255, c.red)
        assertEquals(0x55, c.green)
        assertEquals(0x00, c.blue)
        assertEquals(255, c.alpha)
    }

    @Test
    fun `parses AARRGGBB alpha-first`() {
        val c = parseLogoBackgroundColor("#80FF0000")
        assertEquals(0x80, c.alpha)
        assertEquals(255, c.red)
        assertEquals(0, c.green)
        assertEquals(0, c.blue)
    }

    @Test
    fun `flattens a translucent colour over white to opaque`() {
        val c = flattenOverWhite(Color(255, 0, 0, 128))
        assertEquals(255, c.alpha)
        assertEquals(255, c.red)
        assertTrue(c.green in 126..129, "green was ${c.green}")
        assertTrue(c.blue in 126..129, "blue was ${c.blue}")
    }
}
