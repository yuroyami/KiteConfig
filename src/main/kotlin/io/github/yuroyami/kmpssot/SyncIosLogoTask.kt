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
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Composites the FG/BG layers into the iOS AppIcon.appiconset:
 *  - BG *covers* the 1024² canvas, FG *contains* (aspect-preserving) on top.
 *  - Flattened to opaque RGB — App Store marketing icons MUST NOT have alpha,
 *    so any FG/BG transparency is baked against white.
 *  - Writes `AppIcon-1024.png` + a single-image universal `Contents.json`.
 *
 * Requires iOS deployment target 14+. Cacheable/incremental — declared outputs
 * mean the icon is only rebuilt when the source PNGs/colour change.
 */
@CacheableTask
abstract class SyncIosLogoTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Composite FG+BG app-logo PNGs into the iOS AppIcon.appiconset (single 1024 universal)."
    }

    @get:[InputFile Optional PathSensitive(PathSensitivity.RELATIVE)]
    abstract val foregroundPng: RegularFileProperty

    @get:[InputFile Optional PathSensitive(PathSensitivity.RELATIVE)]
    abstract val backgroundPng: RegularFileProperty

    @get:[Input Optional] abstract val backgroundColorHex: Property<String>
    @get:Input abstract val dryRun: Property<Boolean>

    @get:Internal abstract val appiconsetDir: DirectoryProperty

    @get:OutputFiles abstract val outputFiles: ConfigurableFileCollection

    @TaskAction
    fun sync() {
        val dry = dryRun.get()
        val fgFile = foregroundPng.asFile.orNull
        if (fgFile == null || !fgFile.exists()) {
            logger.warn("[kmpSsot] appLogoPngForeground not found — skipping iOS logo.")
            return
        }
        val fg = ImageIO.read(fgFile) ?: run {
            logger.warn("[kmpSsot] Could not decode ${fgFile.path} as an image — skipping iOS logo.")
            return
        }

        val bgDescription: String
        val bg = if (backgroundColorHex.isPresent) {
            val hex = backgroundColorHex.get()
            bgDescription = "color $hex"
            solidColorImage(IOS_SIZE, parseLogoBackgroundColor(hex))
        } else {
            val bgFile = backgroundPng.asFile.orNull
            if (bgFile == null || !bgFile.exists()) {
                logger.warn("[kmpSsot] appLogoPngBackground not found — skipping iOS logo.")
                return
            }
            bgDescription = bgFile.name
            ImageIO.read(bgFile) ?: run {
                logger.warn("[kmpSsot] Could not decode ${bgFile.path} as an image — skipping iOS logo.")
                return
            }
        }

        // INT_RGB so any residual alpha is composed against opaque white.
        val composite = BufferedImage(IOS_SIZE, IOS_SIZE, BufferedImage.TYPE_INT_RGB).withGraphics {
            color = Color.WHITE
            fillRect(0, 0, IOS_SIZE, IOS_SIZE)
            drawCover(bg, 0, 0, IOS_SIZE, IOS_SIZE)
            drawContain(fg, 0, 0, IOS_SIZE, IOS_SIZE)
        }

        val outDir = appiconsetDir.asFile.get()
        val pngBytes = ByteArrayOutputStream().apply { ImageIO.write(composite, "PNG", this) }.toByteArray()
        writeBytesSafely(outDir.resolve("AppIcon-1024.png"), pngBytes, dry, logger, "iOS AppIcon-1024.png")
        writeTextSafely(outDir.resolve("Contents.json"), CONTENTS_JSON, backup = false, dryRun = dry, logger = logger, label = "iOS Contents.json")

        if (!dry) logger.lifecycle("[kmpSsot] iOS AppIcon synced from ${fgFile.name} + $bgDescription (1024×1024 opaque).")
    }

    companion object {
        internal const val IOS_SIZE = 1024
        internal val OUTPUT_FILE_NAMES = listOf("AppIcon-1024.png", "Contents.json")

        private val CONTENTS_JSON = """
            |{
            |  "images" : [
            |    {
            |      "filename" : "AppIcon-1024.png",
            |      "idiom" : "universal",
            |      "platform" : "ios",
            |      "size" : "1024x1024"
            |    }
            |  ],
            |  "info" : {
            |    "author" : "kmp-ssot",
            |    "version" : 1
            |  }
            |}
            |""".trimMargin()
    }
}
