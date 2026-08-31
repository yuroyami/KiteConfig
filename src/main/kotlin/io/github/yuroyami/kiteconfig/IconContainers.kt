package io.github.yuroyami.kiteconfig

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

/**
 * The ICNS entry types KiteConfig emits, each with its pixel size.
 *
 * `icp4` and `icp5` are deliberately absent. They nominally accept PNG at 16
 * and 32 pixels, and readers disagree about that. `ic11` and `ic12` cover the
 * same pixel sizes as retina entries and are read consistently.
 */
internal val ICNS_ENTRIES: List<Pair<String, Int>> = listOf(
    "ic07" to 128,
    "ic08" to 256,
    "ic09" to 512,
    "ic10" to 1024,
    "ic11" to 32,
    "ic12" to 64,
    "ic13" to 256,
    "ic14" to 512,
)

private fun bigEndian(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte(),
)

/** Build a macOS `.icns` container from one square source image. */
internal fun writeIcns(square: BufferedImage): ByteArray {
    val body = ByteArrayOutputStream()
    for ((type, size) in ICNS_ENTRIES) {
        val payload = encodePng(resize(square, size, size), "macOS icon entry $type")
        body.write(type.toByteArray(Charsets.US_ASCII))
        body.write(bigEndian(payload.size + 8))
        body.write(payload)
    }
    val entries = body.toByteArray()
    val out = ByteArrayOutputStream()
    out.write("icns".toByteArray(Charsets.US_ASCII))
    out.write(bigEndian(entries.size + 8))
    out.write(entries)
    return out.toByteArray()
}

/** The Windows `.ico` entry sizes KiteConfig emits. */
internal val ICO_SIZES: List<Int> = listOf(16, 24, 32, 48, 64, 128, 256)

private fun littleEndianShort(value: Int): ByteArray =
    byteArrayOf(value.toByte(), (value ushr 8).toByte())

private fun littleEndianInt(value: Int): ByteArray = byteArrayOf(
    value.toByte(),
    (value ushr 8).toByte(),
    (value ushr 16).toByte(),
    (value ushr 24).toByte(),
)

/** Build a Windows `.ico` container from one square source image. */
internal fun writeIco(square: BufferedImage): ByteArray {
    val payloads = ICO_SIZES.map { size ->
        encodePng(resize(square, size, size), "Windows icon entry ${size}px")
    }
    val directoryBytes = 6 + ICO_SIZES.size * 16
    val out = ByteArrayOutputStream()
    out.write(littleEndianShort(0))
    out.write(littleEndianShort(1))
    out.write(littleEndianShort(ICO_SIZES.size))

    var offset = directoryBytes
    ICO_SIZES.forEachIndexed { index, size ->
        // A 256 pixel side is stored as 0, which is what the format reserves for it.
        val stored = if (size == 256) 0 else size
        out.write(byteArrayOf(stored.toByte(), stored.toByte(), 0, 0))
        out.write(littleEndianShort(1))
        out.write(littleEndianShort(32))
        out.write(littleEndianInt(payloads[index].size))
        out.write(littleEndianInt(offset))
        offset += payloads[index].size
    }
    payloads.forEach(out::write)
    return out.toByteArray()
}
