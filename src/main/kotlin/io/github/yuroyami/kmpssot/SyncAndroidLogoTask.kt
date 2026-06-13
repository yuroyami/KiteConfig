package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Propagates the FG/BG layer PNGs to a complete Android launcher-icon resource
 * tree. Source PNGs are treated as **the icon as designed** (fills the canvas
 * like an iOS marketing icon). Non-square sources are aspect-fit, never
 * stretched: the FG is *contained* (letterboxed) inside the safe zone, the BG
 * *covers* the canvas (centre-cropped).
 *
 *  - Adaptive FG: source FG fit inside [safeZoneRatio] of the canvas.
 *  - Adaptive BG: source BG covers the 108dp canvas (parallax bleed).
 *  - Legacy fallback: BG cover + FG contain, at the legacy launcher size.
 *
 * Outputs (per density bucket) plus the API-26+ adaptive wrappers are declared
 * as task outputs, so the task is **cacheable and incremental** — icons are only
 * regenerated when the source PNGs, colour, or ratio change, not on every build.
 */
@CacheableTask
abstract class SyncAndroidLogoTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Propagate the FG+BG app-logo PNGs to the Android launcher-icon resource tree."
    }

    @get:[InputFile Optional PathSensitive(PathSensitivity.RELATIVE)]
    abstract val foregroundPng: RegularFileProperty

    @get:[InputFile Optional PathSensitive(PathSensitivity.RELATIVE)]
    abstract val backgroundPng: RegularFileProperty

    @get:[Input Optional] abstract val backgroundColorHex: Property<String>
    @get:Input abstract val safeZoneRatio: Property<Double>
    @get:Input abstract val dryRun: Property<Boolean>

    @get:Internal abstract val androidResDir: DirectoryProperty

    /** Exactly the files this task writes — declared so Gradle can cache/track them safely. */
    @get:OutputFiles abstract val outputFiles: ConfigurableFileCollection

    @TaskAction
    fun sync() {
        val dry = dryRun.get()
        val fgFile = foregroundPng.asFile.orNull
        if (fgFile == null || !fgFile.exists()) {
            logger.warn("[kmpSsot] appLogoPngForeground not found — skipping Android logo.")
            return
        }
        val fg = ImageIO.read(fgFile) ?: run {
            logger.warn("[kmpSsot] Could not decode ${fgFile.path} as an image — skipping Android logo.")
            return
        }

        val bgDescription: String
        val bg: BufferedImage = if (backgroundColorHex.isPresent) {
            val hex = backgroundColorHex.get()
            var color = parseLogoBackgroundColor(hex)
            if (color.alpha < 255) {
                logger.warn("[kmpSsot] appLogoBackgroundColor $hex is semi-transparent — flattening over white so Android matches the iOS icon.")
                color = flattenOverWhite(color)
            }
            bgDescription = "color $hex"
            // 432px matches xxxhdpi adaptive canvas; downscaled per density.
            solidColorImage(432, color)
        } else {
            val bgFile = backgroundPng.asFile.orNull
            if (bgFile == null || !bgFile.exists()) {
                logger.warn("[kmpSsot] appLogoPngBackground not found — skipping Android logo.")
                return
            }
            val decoded = ImageIO.read(bgFile) ?: run {
                logger.warn("[kmpSsot] Could not decode ${bgFile.path} as an image — skipping Android logo.")
                return
            }
            if (decoded.width != decoded.height) {
                logger.warn("[kmpSsot] appLogoPngBackground is not square (${decoded.width}×${decoded.height}) — it will be centre-cropped to fill the canvas.")
            }
            bgDescription = bgFile.name
            decoded
        }

        if (fg.width != fg.height) {
            logger.warn("[kmpSsot] appLogoPngForeground is not square (${fg.width}×${fg.height}) — it will be letterboxed (aspect-preserving) inside the safe zone.")
        }
        if (!fg.colorModel.hasAlpha()) {
            logger.warn("[kmpSsot] appLogoPngForeground has no alpha channel — it will fully cover the background. Use a PNG with transparency for proper layering.")
        }
        if (fg.width < 432) {
            logger.warn("[kmpSsot] appLogoPngForeground is ${fg.width}px wide — recommend ≥432px (xxxhdpi adaptive size) to avoid upscaling artefacts.")
        }

        val ratio = safeZoneRatio.get()
        val resDir = androidResDir.asFile.get()

        DENSITIES.forEach { (qualifier, scale) ->
            val mipmap = resDir.resolve("mipmap-$qualifier")
            val adaptiveSize = (108 * scale).toInt()
            val legacySize = (48 * scale).toInt()

            writePng(mipmap.resolve("ic_launcher_foreground.png"), padToSafeZone(fg, adaptiveSize, ratio), dry)
            writePng(mipmap.resolve("ic_launcher_background.png"), coverCanvas(bg, adaptiveSize), dry)

            val legacySquare = legacyComposite(fg, bg, legacySize)
            writePng(mipmap.resolve("ic_launcher.png"), legacySquare, dry)
            writePng(mipmap.resolve("ic_launcher_round.png"), applyCircleMask(legacySquare), dry)
        }

        val adaptiveDir = resDir.resolve("mipmap-anydpi-v26")
        val adaptiveXml = buildAdaptiveIconWrapper()
        writeText(adaptiveDir.resolve("ic_launcher.xml"), adaptiveXml, dry)
        writeText(adaptiveDir.resolve("ic_launcher_round.xml"), adaptiveXml, dry)

        if (!dry) logger.lifecycle("[kmpSsot] Android logo synced from ${fgFile.name} + $bgDescription.")
    }

    private fun buildAdaptiveIconWrapper(): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        |    <background android:drawable="@mipmap/ic_launcher_background"/>
        |    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
        |</adaptive-icon>
        |""".trimMargin()

    /** Foreground fit (aspect-preserving) inside the safe zone, centred on a transparent canvas. */
    private fun padToSafeZone(fg: BufferedImage, canvasSize: Int, ratio: Double): BufferedImage {
        val safe = (canvasSize * ratio).toInt().coerceAtLeast(1)
        val offset = (canvasSize - safe) / 2
        return newArgb(canvasSize).withGraphics { drawContain(fg, offset, offset, safe, safe) }
    }

    /** Background scaled to cover the full canvas, preserving aspect (centre-cropped). */
    private fun coverCanvas(bg: BufferedImage, size: Int): BufferedImage =
        newArgb(size).withGraphics { drawCover(bg, 0, 0, size, size) }

    /** Legacy composite: BG cover + FG contain at the legacy launcher size. */
    private fun legacyComposite(fg: BufferedImage, bg: BufferedImage, size: Int): BufferedImage =
        newArgb(size).withGraphics {
            drawCover(bg, 0, 0, size, size)
            drawContain(fg, 0, 0, size, size)
        }

    private fun writePng(target: File, image: BufferedImage, dry: Boolean) {
        val bytes = ByteArrayOutputStream().apply { ImageIO.write(image, "PNG", this) }.toByteArray()
        writeBytesSafely(target, bytes, dry, logger, "Android ${target.parentFile.name}/${target.name}")
    }

    private fun writeText(target: File, content: String, dry: Boolean) {
        writeTextSafely(target, content, backup = false, dryRun = dry, logger = logger, label = "Android ${target.name}")
    }

    companion object {
        // Density qualifier → scale factor. Adaptive canvas 108dp; legacy 48dp.
        internal val DENSITIES = listOf(
            "mdpi" to 1.0,
            "hdpi" to 1.5,
            "xhdpi" to 2.0,
            "xxhdpi" to 3.0,
            "xxxhdpi" to 4.0,
        )

        /** Relative paths (under the Android res dir) of every file this task writes. */
        internal val OUTPUT_RELATIVE_PATHS: List<String> = buildList {
            DENSITIES.forEach { (q, _) ->
                add("mipmap-$q/ic_launcher_foreground.png")
                add("mipmap-$q/ic_launcher_background.png")
                add("mipmap-$q/ic_launcher.png")
                add("mipmap-$q/ic_launcher_round.png")
            }
            add("mipmap-anydpi-v26/ic_launcher.xml")
            add("mipmap-anydpi-v26/ic_launcher_round.xml")
        }
    }
}
