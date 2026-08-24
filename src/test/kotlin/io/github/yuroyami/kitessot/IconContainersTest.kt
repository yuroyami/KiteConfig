package io.github.yuroyami.kitessot

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
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

        val declaredSizes = ICNS_ENTRIES.toMap()
        val seen = mutableListOf<String>()
        var offset = 8
        while (offset < bytes.size) {
            val type = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            assertTrue(length > 8, "entry $type has no payload")
            assertTrue(offset + length <= bytes.size, "entry $type runs past the end")
            val payload = bytes.copyOfRange(offset + 8, offset + length)
            assertEquals(0x89.toByte(), payload[0])
            assertEquals("PNG", String(payload, 1, 3, Charsets.US_ASCII))
            // Dimensions must come from the payload itself, not from ICNS_ENTRIES, so a
            // mismatched entry (wrong OSType paired with the wrong pixel size) fails here.
            val decoded = ImageIO.read(ByteArrayInputStream(payload))
            val expectedSize = declaredSizes.getValue(type)
            assertEquals(expectedSize, decoded.width)
            assertEquals(expectedSize, decoded.height)
            seen += type
            offset += length
        }
        assertEquals(bytes.size, offset)
        assertEquals(ICNS_ENTRIES.map { it.first }, seen)
    }

    @Test
    fun `writeIcns skips the legacy small PNG types`() {
        val types = ICNS_ENTRIES.map { it.first }
        assertTrue("icp4" !in types, types.toString())
        assertTrue("icp5" !in types, types.toString())
    }

    @Test
    fun `writeIco emits a directory whose offsets and lengths address real PNG payloads`() {
        val bytes = writeIco(square(256))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0, buffer.getShort(0).toInt())
        assertEquals(1, buffer.getShort(2).toInt())
        assertEquals(ICO_SIZES.size, buffer.getShort(4).toInt())

        ICO_SIZES.forEachIndexed { index, size ->
            val entry = 6 + index * 16
            val storedWidth = bytes[entry].toInt() and 0xff
            assertEquals(if (size == 256) 0 else size, storedWidth)
            assertEquals(32, buffer.getShort(entry + 6).toInt())
            val length = buffer.getInt(entry + 8)
            val offset = buffer.getInt(entry + 12)
            assertTrue(offset + length <= bytes.size, (offset + length).toString())
            assertEquals(0x89.toByte(), bytes[offset])
            assertEquals("PNG", String(bytes, offset + 1, 3, Charsets.US_ASCII))
        }
    }
}
