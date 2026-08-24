package io.github.yuroyami.kitessot

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

/**
 * The ICNS entry types KiteSSOT emits, each with its pixel size.
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
