package io.github.yuroyami.kiteconfig.core

import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets

// Density qualifier → scale factor. Adaptive canvas 108dp; legacy 48dp.
@InternalKiteCoreApi
public val ANDROID_DENSITIES: List<Pair<String, Double>> = listOf(
    "mdpi" to 1.0,
    "hdpi" to 1.5,
    "xhdpi" to 2.0,
    "xxhdpi" to 3.0,
    "xxxhdpi" to 4.0,
)

/**
 * Every file of one Android launcher icon set, keyed by path under `res/`.
 *
 * Shared by the release installer, which writes into the app's source tree, and
 * the debug generator, which writes into `build/`, so both render the same way.
 */
@InternalKiteCoreApi
public fun renderAndroidLauncherIcons(
    foreground: BufferedImage,
    background: BufferedImage,
    safeZoneRatio: Double,
    emitMonochrome: Boolean,
): LinkedHashMap<String, ByteArray> {
    val rendered = linkedMapOf<String, ByteArray>()
    ANDROID_DENSITIES.forEach { (qualifier, scale) ->
        val adaptiveSize = (108 * scale).toInt()
        val legacySize = (48 * scale).toInt()

        rendered["mipmap-$qualifier/ic_launcher_foreground.png"] =
            encodePng(padToSafeZone(foreground, adaptiveSize, safeZoneRatio), "Android logo")
        rendered["mipmap-$qualifier/ic_launcher_background.png"] =
            encodePng(coverCanvas(background, adaptiveSize), "Android logo")

        val legacySquare = legacyComposite(foreground, background, legacySize)
        rendered["mipmap-$qualifier/ic_launcher.png"] = encodePng(legacySquare, "Android logo")
        rendered["mipmap-$qualifier/ic_launcher_round.png"] = encodePng(applyCircleMask(legacySquare), "Android logo")
    }
    val adaptiveXml = adaptiveIconWrapper(monochrome = false).toByteArray(StandardCharsets.UTF_8)
    rendered["mipmap-anydpi-v26/ic_launcher.xml"] = adaptiveXml
    rendered["mipmap-anydpi-v26/ic_launcher_round.xml"] = adaptiveXml
    if (emitMonochrome) {
        val themedXml = adaptiveIconWrapper(monochrome = true).toByteArray(StandardCharsets.UTF_8)
        rendered["mipmap-anydpi-v33/ic_launcher.xml"] = themedXml
        rendered["mipmap-anydpi-v33/ic_launcher_round.xml"] = themedXml
    }
    return rendered
}

private fun adaptiveIconWrapper(monochrome: Boolean): String = """
    |<?xml version="1.0" encoding="utf-8"?>
    |<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    |    <background android:drawable="@mipmap/ic_launcher_background"/>
    |    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    |${if (monochrome) "    <monochrome android:drawable=\"@mipmap/ic_launcher_foreground\"/>" else ""}
    |</adaptive-icon>
    |""".trimMargin()

/** Background scaled to cover the full canvas, preserving aspect (center-cropped). */
private fun coverCanvas(bg: BufferedImage, size: Int): BufferedImage =
    BufferedImage(size, size, BufferedImage.TYPE_INT_RGB).withGraphics {
        color = java.awt.Color.WHITE
        fillRect(0, 0, size, size)
        drawCover(bg, 0, 0, size, size)
    }

/** Legacy composite: BG cover + FG contain at the legacy launcher size. */
private fun legacyComposite(fg: BufferedImage, bg: BufferedImage, size: Int): BufferedImage =
    newArgb(size).withGraphics {
        drawCover(bg, 0, 0, size, size)
        drawContain(fg, 0, 0, size, size)
    }
