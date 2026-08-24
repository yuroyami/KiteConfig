package io.github.yuroyami.kitessot

import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IconContainersTest {

    private fun square(size: Int): BufferedImage =
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)

    @Test
    fun `writeIcns emits a parseable container with every declared entry`() {
        val bytes = writeIcns(square(1024))

        assertEquals("icns", String(bytes, 0, 4, Charsets.US_ASCII))
        val declared = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(bytes.size, declared)

        val seen = mutableListOf<String>()
        var offset = 8
        while (offset < bytes.size) {
            val type = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            assertTrue(length > 8, "entry $type has no payload")
            assertTrue(offset + length <= bytes.size, "entry $type runs past the end")
            val payload = bytes.copyOfRange(offset + 8, offset + length)
            assertEquals(0x89.toByte(), payload[0], "entry $type payload is not PNG")
            assertEquals("PNG", String(payload, 1, 3, Charsets.US_ASCII), "entry $type payload is not PNG")
            seen += type
            offset += length
        }
        assertEquals(bytes.size, offset)
        assertEquals(ICNS_ENTRIES.map { it.first }, seen)
    }

    @Test
    fun `writeIcns skips the legacy small PNG types`() {
        val types = ICNS_ENTRIES.map { it.first }
        assertTrue("icp4" !in types, "icp4 handles PNG inconsistently and must not be emitted")
        assertTrue("icp5" !in types, "icp5 handles PNG inconsistently and must not be emitted")
    }
}
