package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO

class ImageOpsTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `strict decoder accepts a bounded PNG`() {
        val file = directory.resolve("logo.png")
        ImageIO.write(BufferedImage(31, 17, BufferedImage.TYPE_INT_ARGB), "PNG", file)

        val decoded = readBoundedLogoPng(file, "foreground")

        assertEquals(31, decoded.width)
        assertEquals(17, decoded.height)
    }

    @Test
    fun `decoded logo and provenance hash come from one immutable byte snapshot`() {
        val file = directory.resolve("snapshot.png")
        ImageIO.write(BufferedImage(19, 23, BufferedImage.TYPE_INT_ARGB), "PNG", file)
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        val snapshot = readBoundedLogoPngSnapshot(file, "foreground")

        assertEquals(19, snapshot.image.width)
        assertEquals(23, snapshot.image.height)
        assertEquals(expectedHash, snapshot.sha256)
    }

    @Test
    fun `logo provenance canonicalizes equivalent color syntax`() {
        val foregroundHash = "a".repeat(64)

        val lower = logoInputFingerprint("renderer-v1", foregroundHash, null, "#ffffff")
        val explicitAlpha = logoInputFingerprint("renderer-v1", foregroundHash, null, "#FFFFFFFF")

        assertEquals(lower, explicitAlpha)
    }

    @Test
    fun `strict decoder rejects another format hidden behind png extension`() {
        val file = directory.resolve("not-really.png")
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "JPEG", file)

        val error = assertThrows(IllegalArgumentException::class.java) {
            readBoundedLogoPng(file, "foreground")
        }

        assertTrue(error.message.orEmpty().contains("PNG signature"), error.message)
    }

    @Test
    fun `IHDR dimensions are bounded before image decoding`() {
        val file = directory.resolve("huge.png")
        val header = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            .copyInto(header)
        ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(8, 13)
            position(12)
            put(byteArrayOf(0x49, 0x48, 0x44, 0x52))
            putInt(16, MAX_LOGO_DIMENSION + 1)
            putInt(20, 1)
        }
        file.writeBytes(header)

        val error = assertThrows(IllegalArgumentException::class.java) {
            readBoundedLogoPng(file, "foreground")
        }

        assertTrue(error.message.orEmpty().contains("dimensions"), error.message)
    }

    @Test
    fun `symlinked PNG input is rejected`() {
        val real = directory.resolve("real.png")
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "PNG", real)
        val link = directory.toPath().resolve("link.png")
        try {
            Files.createSymbolicLink(link, real.toPath().fileName)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported")
        } catch (_: java.nio.file.FileSystemException) {
            assumeTrue(false, "symbolic links are not permitted")
        }

        val error = assertThrows(GradleException::class.java) {
            readBoundedLogoPng(link.toFile(), "foreground")
        }

        assertTrue(error.message.orEmpty().contains("Unsafe foreground path"), error.message)
    }
}
