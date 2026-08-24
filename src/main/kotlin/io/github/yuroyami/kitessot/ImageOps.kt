package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

internal const val MAX_LOGO_PNG_BYTES: Long = 32L * 1024L * 1024L
internal const val MAX_LOGO_DIMENSION: Int = 4096
internal const val MAX_LOGO_PIXELS: Long = 16L * 1024L * 1024L

internal data class LogoPngSnapshot(val image: BufferedImage, val sha256: String)

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)

/**
 * Stable provenance for the exact logo inputs and renderer policy that produced
 * an installed asset set. Renderer implementations must bump their version
 * component whenever an algorithm or template changes.
 */
internal fun logoInputFingerprint(
    rendererVersion: String,
    foregroundSha256: String,
    backgroundSha256: String?,
    backgroundColor: String?,
    parameters: Map<String, String> = emptyMap(),
): String {
    require(rendererVersion.isNotBlank()) { "Logo renderer fingerprint version must not be blank." }
    require(foregroundSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid foreground logo fingerprint." }
    require((backgroundSha256 == null) xor (backgroundColor == null)) {
        "Exactly one logo background file or color is required for provenance."
    }
    require(backgroundSha256 == null || backgroundSha256.matches(Regex("[0-9a-f]{64}"))) {
        "Invalid background logo fingerprint."
    }
    val digest = MessageDigest.getInstance("SHA-256")
    fun component(name: String, value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        digest.update(name.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(encoded.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0)
        digest.update(encoded)
        digest.update(0)
    }

    component("renderer", rendererVersion)
    component("foregroundSha256", foregroundSha256)
    if (backgroundSha256 != null) {
        component("backgroundKind", "png")
        component("backgroundSha256", backgroundSha256)
    } else {
        component("backgroundKind", "color")
        component("backgroundColor", canonicalLogoBackgroundColor(checkNotNull(backgroundColor)))
    }
    parameters.toSortedMap().forEach { (name, value) -> component("parameter:$name", value) }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun logoInputFingerprintForFiles(
    rendererVersion: String,
    foreground: File,
    background: File?,
    backgroundColor: String?,
    parameters: Map<String, String> = emptyMap(),
): String = logoInputFingerprint(
    rendererVersion = rendererVersion,
    foregroundSha256 = boundedLogoFileSha256(foreground, "logo { foreground }"),
    backgroundSha256 = background?.let { boundedLogoFileSha256(it, "logo { background }") },
    backgroundColor = backgroundColor,
    parameters = parameters,
)

private fun canonicalLogoBackgroundColor(value: String): String =
    (parseLogoBackgroundColor(value).rgb.toLong() and 0xffff_ffffL)
        .toString(16)
        .padStart(8, '0')
        .uppercase()

/**
 * Strict, bounded PNG decoding for logo inputs.
 *
 * The signature and IHDR dimensions are checked before ImageIO allocates the
 * destination raster. This rejects format spoofing and caps both compressed and
 * decoded size so an image bomb cannot exhaust the Gradle daemon.
 */
internal fun readBoundedLogoPng(file: File, label: String): BufferedImage =
    readBoundedLogoPngSnapshot(file, label).image

internal fun readBoundedLogoPngSnapshot(file: File, label: String): LogoPngSnapshot {
    val bytes = readBoundedLogoBytes(file, label)
    require(bytes.size >= 24) { "$label is a truncated PNG: ${file.path}" }
    val header = bytes.copyOfRange(0, 24)
    require(header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
        "$label must be a PNG file (its content does not have a PNG signature): ${file.path}"
    }
    require(ByteBuffer.wrap(header, 8, 4).order(ByteOrder.BIG_ENDIAN).int == 13) {
        "$label has an invalid PNG IHDR length: ${file.path}"
    }
    require(header.copyOfRange(12, 16).contentEquals(byteArrayOf(0x49, 0x48, 0x44, 0x52))) {
        "$label has no valid PNG IHDR chunk: ${file.path}"
    }
    val dimensions = ByteBuffer.wrap(header, 16, 8).order(ByteOrder.BIG_ENDIAN)
    val width = dimensions.int.toLong() and 0xFFFF_FFFFL
    val height = dimensions.int.toLong() and 0xFFFF_FFFFL
    require(width in 1..MAX_LOGO_DIMENSION.toLong() && height in 1..MAX_LOGO_DIMENSION.toLong()) {
        "$label dimensions ${width}×$height exceed the supported 1…$MAX_LOGO_DIMENSION range: ${file.path}"
    }
    require(width * height <= MAX_LOGO_PIXELS) {
        "$label contains ${width * height} pixels; maximum is $MAX_LOGO_PIXELS: ${file.path}"
    }

    try {
        ByteArrayInputStream(bytes).use { raw ->
            MemoryCacheImageInputStream(raw).use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                require(readers.hasNext()) { "$label cannot be decoded as PNG: ${file.path}" }
                val reader = readers.next()
                try {
                    require(reader.formatName.equals("png", ignoreCase = true)) {
                        "$label must be decoded by a PNG reader, got ${reader.formatName}: ${file.path}"
                    }
                    reader.setInput(stream, true, true)
                    require(reader.getWidth(0).toLong() == width && reader.getHeight(0).toLong() == height) {
                        "$label PNG dimensions changed while decoding: ${file.path}"
                    }
                    val image = reader.read(0)
                        ?: throw IllegalArgumentException("$label decoded to no image: ${file.path}")
                    return LogoPngSnapshot(image, sha256Hex(bytes))
                } finally {
                    reader.dispose()
                }
            }
        }
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: IOException) {
        throw IllegalArgumentException("$label is not a valid decodable PNG: ${file.path}", e)
    }
}

internal fun boundedLogoFileSha256(file: File, label: String): String =
    sha256Hex(readBoundedLogoBytes(file, label))

private fun readBoundedLogoBytes(file: File, label: String): ByteArray {
    if (!file.exists()) throw IllegalArgumentException("$label does not exist: ${file.path}")
    OwnedOutputSafety.requireSafePath(file, label)
    val path = file.toPath()
    val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(attrs.isRegularFile) { "$label must be a regular PNG file: ${file.path}" }
    require(attrs.size() in 24..MAX_LOGO_PNG_BYTES) {
        "$label must be between 24 bytes and $MAX_LOGO_PNG_BYTES bytes; got ${attrs.size()}: ${file.path}"
    }

    val snapshotBytes = ByteArrayOutputStream(minOf(attrs.size(), 8192L).toInt())
    Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val byteBuffer = ByteBuffer.wrap(buffer)
        var total = 0L
        while (true) {
            byteBuffer.clear()
            val count = channel.read(byteBuffer)
            if (count < 0) break
            if (count == 0) continue
            require(count.toLong() <= MAX_LOGO_PNG_BYTES - total) {
                "$label grew beyond $MAX_LOGO_PNG_BYTES bytes while being snapshotted: ${file.path}"
            }
            snapshotBytes.write(buffer, 0, count)
            total += count
        }
        require(total == attrs.size()) { "$label changed while being snapshotted: ${file.path}" }
    }
    val bytes = snapshotBytes.toByteArray()
    val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(
        after.isRegularFile && after.size() == attrs.size() &&
            after.fileKey() == attrs.fileKey() && after.lastModifiedTime() == attrs.lastModifiedTime()
    ) {
        "$label changed while being snapshotted: ${file.path}"
    }
    return bytes
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Run [block] on a quality-configured Graphics2D, disposing it afterward; returns the receiver. */
internal inline fun BufferedImage.withGraphics(block: Graphics2D.() -> Unit): BufferedImage {
    val g = createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.composite = AlphaComposite.SrcOver
        g.block()
    } finally {
        g.dispose()
    }
    return this
}

internal fun newArgb(size: Int): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)

internal fun resize(src: BufferedImage, w: Int, h: Int): BufferedImage {
    if (src.width == w && src.height == h) return src
    return BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).withGraphics { drawImage(src, 0, 0, w, h, null) }
}

/** Draw [img] scaled to fit *inside* the box, preserving aspect ratio, centered. Never stretches. */
internal fun Graphics2D.drawContain(img: BufferedImage, x: Int, y: Int, w: Int, h: Int) {
    val scale = minOf(w.toDouble() / img.width, h.toDouble() / img.height)
    val dw = (img.width * scale).toInt().coerceAtLeast(1)
    val dh = (img.height * scale).toInt().coerceAtLeast(1)
    drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null)
}

/** Draw [img] scaled to *cover* the box, preserving aspect ratio, centered; overflow is clipped. */
internal fun Graphics2D.drawCover(img: BufferedImage, x: Int, y: Int, w: Int, h: Int) {
    val scale = maxOf(w.toDouble() / img.width, h.toDouble() / img.height)
    val dw = (img.width * scale).toInt().coerceAtLeast(1)
    val dh = (img.height * scale).toInt().coerceAtLeast(1)
    val prevClip = clip
    clip = Rectangle(x, y, w, h)
    drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null)
    clip = prevClip
}

internal fun applyCircleMask(src: BufferedImage): BufferedImage =
    BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB).withGraphics {
        clip = Ellipse2D.Float(0f, 0f, src.width.toFloat(), src.height.toFloat())
        drawImage(src, 0, 0, null)
    }

/** Encode [image] as PNG bytes. [label] names the artifact in the failure message. */
internal fun encodePng(image: BufferedImage, label: String): ByteArray {
    val output = ByteArrayOutputStream()
    if (!ImageIO.write(image, "PNG", output)) {
        throw GradleException("[kiteSsot] This JDK has no PNG encoder; cannot render $label.")
    }
    return output.toByteArray()
}

/** Centre [fg] inside a square canvas, filling only [ratio] of each side. */
internal fun padToSafeZone(fg: BufferedImage, canvasSize: Int, ratio: Double): BufferedImage {
    val safe = (canvasSize * ratio).toInt().coerceAtLeast(1)
    val offset = (canvasSize - safe) / 2
    return newArgb(canvasSize).withGraphics { drawContain(fg, offset, offset, safe, safe) }
}

/** Clip [src] to a rounded square. [cornerRatio] is the corner radius as a fraction of the side. */
internal fun applyRoundedRectMask(src: BufferedImage, cornerRatio: Double): BufferedImage {
    val side = minOf(src.width, src.height).toFloat()
    // RoundRectangle2D takes the arc DIAMETER, so a radius fraction doubles here.
    val arc = (side * cornerRatio * 2).toFloat()
    return BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB).withGraphics {
        clip = RoundRectangle2D.Float(0f, 0f, src.width.toFloat(), src.height.toFloat(), arc, arc)
        drawImage(src, 0, 0, null)
    }
}
