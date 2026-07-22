package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
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
 * This is an installer into a user-owned Android source tree, so it is deliberately
 * non-cacheable and always performs its validation. A checksum ownership manifest
 * prevents overwriting or deleting user-authored files at generated paths.
 */
@DisableCachingByDefault(because = "Installs and validates files in a user-owned Android resource tree.")
abstract class SyncAndroidLogoTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Propagate the FG+BG app-logo PNGs to the Android launcher-icon resource tree."
        outputs.upToDateWhen { false }
        projectRootDir.convention(project.layout.projectDirectory)
        emitMonochrome.convention(false)
        cleanupLegacyArtifacts.convention(false)
        backupDir.convention(project.layout.projectDirectory.dir(".kmpssot/recovery/android-logo"))
    }

    /** Validated explicitly so configured-but-missing files receive actionable plugin diagnostics. */
    @get:Internal
    abstract val foregroundPng: RegularFileProperty

    @get:Internal
    abstract val backgroundPng: RegularFileProperty

    @get:[Input Optional] abstract val backgroundColorHex: Property<String>
    @get:Input abstract val safeZoneRatio: Property<Double>
    @get:Input abstract val emitMonochrome: Property<Boolean>
    @get:Input abstract val cleanupLegacyArtifacts: Property<Boolean>
    @get:Internal abstract val dryRun: Property<Boolean>

    @get:Internal abstract val androidResDir: DirectoryProperty
    @get:Internal abstract val projectRootDir: DirectoryProperty
    @get:Internal abstract val backupDir: DirectoryProperty

    /** Compatibility surface for diagnostics/wiring; source-tree files are not Gradle task outputs. */
    @get:Internal abstract val outputFiles: ConfigurableFileCollection

    @TaskAction
    fun sync() {
        val dry = dryRun.get()
        val fgFile = foregroundPng.asFile.orNull
        if (fgFile == null) {
            logger.lifecycle("[kmpSsot] Android logo sync skipped: appLogoPngForeground is not configured.")
            return
        }
        if (!fgFile.exists()) {
            throw GradleException(
                "[kmpSsot] appLogoPngForeground points to a missing file: ${fgFile.path}. " +
                    "Fix the path or disable logo propagation.",
            )
        }
        val resDir = androidResDir.asFile.get()
        OwnedOutputSafety.requireInstallerInsideProject(
            resDir,
            projectRootDir.asFile.get(),
            "Android logo output",
        )
        OwnedOutputSafety.requireInputOutsideOutput(fgFile, resDir, "appLogoPngForeground")
        val fgSnapshot = decodeLogo(fgFile, "appLogoPngForeground")
        val fg = fgSnapshot.image

        val bgDescription: String
        var bgSnapshot: LogoPngSnapshot? = null
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
            if (bgFile == null) {
                throw GradleException(
                    "[kmpSsot] Android logo requires appLogoPngBackground or appLogoBackgroundColor.",
                )
            }
            if (!bgFile.exists()) {
                throw GradleException(
                    "[kmpSsot] appLogoPngBackground points to a missing file: ${bgFile.path}. " +
                        "Fix the path or configure appLogoBackgroundColor.",
                )
            }
            OwnedOutputSafety.requireInputOutsideOutput(bgFile, resDir, "appLogoPngBackground")
            val decoded = decodeLogo(bgFile, "appLogoPngBackground")
            bgSnapshot = decoded
            if (decoded.image.width != decoded.image.height) {
                logger.warn("[kmpSsot] appLogoPngBackground is not square (${decoded.image.width}×${decoded.image.height}) — it will be centre-cropped to fill the canvas.")
            }
            bgDescription = bgFile.name
            decoded.image
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

        val ratio = validateLogoSafeZoneRatio(safeZoneRatio.get())
        OwnedOutputSafety.requireSafePath(resDir, "Android resource output")

        val templateCollisions = collidingTemplateIcons(resDir)
        if (templateCollisions.isNotEmpty() && !cleanupLegacyArtifacts.get()) {
            failOnTemplateCollisions(resDir, templateCollisions)
        }

        val rendered = linkedMapOf<String, ByteArray>()

        DENSITIES.forEach { (qualifier, scale) ->
            val adaptiveSize = (108 * scale).toInt()
            val legacySize = (48 * scale).toInt()

            rendered["mipmap-$qualifier/ic_launcher_foreground.png"] = encodePng(padToSafeZone(fg, adaptiveSize, ratio))
            rendered["mipmap-$qualifier/ic_launcher_background.png"] = encodePng(coverCanvas(bg, adaptiveSize))

            val legacySquare = legacyComposite(fg, bg, legacySize)
            rendered["mipmap-$qualifier/ic_launcher.png"] = encodePng(legacySquare)
            rendered["mipmap-$qualifier/ic_launcher_round.png"] = encodePng(applyCircleMask(legacySquare))
        }

        val adaptiveXml = buildAdaptiveIconWrapper(monochrome = false).toByteArray(StandardCharsets.UTF_8)
        rendered["mipmap-anydpi-v26/ic_launcher.xml"] = adaptiveXml
        rendered["mipmap-anydpi-v26/ic_launcher_round.xml"] = adaptiveXml
        if (emitMonochrome.getOrElse(false)) {
            val themedXml = buildAdaptiveIconWrapper(monochrome = true).toByteArray(StandardCharsets.UTF_8)
            rendered["mipmap-anydpi-v33/ic_launcher.xml"] = themedXml
            rendered["mipmap-anydpi-v33/ic_launcher_round.xml"] = themedXml
        }
        val configuredColor = backgroundColorHex.orNull
        val inputFingerprint = logoInputFingerprint(
            rendererVersion = RENDERER_FINGERPRINT_VERSION,
            foregroundSha256 = fgSnapshot.sha256,
            backgroundSha256 = bgSnapshot?.sha256,
            backgroundColor = configuredColor,
            parameters = mapOf(
                "emitMonochrome" to emitMonochrome.getOrElse(false).toString(),
                "safeZoneRatio" to ratio.toString(),
            ),
        )

        if (dry) {
            rendered.keys.forEach { logger.lifecycle("[kmpSsot][dry-run] would write Android logo: $it") }
            return
        }

        val manifest = resDir.parentFile.resolve(".kmpssot/android-logo-owned-files-v1")
        if (cleanupLegacyArtifacts.get()) {
            val legacy = listOf(
                resDir.resolve("drawable/ic_launcher.xml"),
                resDir.resolve("values/ic_launcher_background.xml"),
            ) + templateCollisions
            OwnedOutputSafety.replaceInstalledFilesWithBackup(
                installationRoot = resDir,
                manifestFile = manifest,
                backupRoot = backupDir.asFile.get(),
                projectRoot = projectRootDir.asFile.get(),
                owner = "android-logo",
                files = rendered,
                inputFingerprint = inputFingerprint,
                additionalTakeoverFiles = legacy,
            )
        } else {
            OwnedOutputSafety.replaceInstalledFiles(
                installationRoot = resDir,
                manifestFile = manifest,
                projectRoot = projectRootDir.asFile.get(),
                owner = "android-logo",
                files = rendered,
                inputFingerprint = inputFingerprint,
            )
        }

        logger.lifecycle("[kmpSsot] Android logo synced from ${fgFile.name} + $bgDescription.")
    }

    private fun decodeLogo(file: File, label: String): LogoPngSnapshot = try {
        readBoundedLogoPngSnapshot(file, label)
    } catch (e: IllegalArgumentException) {
        throw GradleException("[kmpSsot] ${e.message}", e)
    }

    private fun buildAdaptiveIconWrapper(monochrome: Boolean): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        |    <background android:drawable="@mipmap/ic_launcher_background"/>
        |    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
        |${if (monochrome) "    <monochrome android:drawable=\"@mipmap/ic_launcher_foreground\"/>" else ""}
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

    private fun encodePng(image: BufferedImage): ByteArray {
        val output = ByteArrayOutputStream()
        if (!ImageIO.write(image, "PNG", output)) {
            throw GradleException("[kmpSsot] This JDK has no PNG encoder; cannot render Android logo.")
        }
        return output.toByteArray()
    }

    /**
     * Fail on template launcher icons that share a generated PNG's stem but a
     * different extension (typically `ic_launcher.webp` from the Android Studio
     * wizard). Two `ic_launcher.*` in one `mipmap-*` bucket is a duplicate
     * resource → AAPT2 fails the merge, so the first logo sync would otherwise
     * break the consumer's Android build with a cryptic error.
     */
    private fun failOnTemplateCollisions(resDir: File, collisions: List<File>) {
        val displayed = collisions.take(MAX_DISPLAYED_COLLISIONS)
        val omitted = collisions.size - displayed.size
        throw GradleException(
            "[kmpSsot] Found ${collisions.size} template launcher icon(s) that collide with the " +
                    "generated PNGs (same name, different extension) and will fail the Android resource merge:\n" +
                    displayed.joinToString("\n") {
                        "    ${diagnosticSafeText(it.relativeToOrSelf(resDir).path)}"
                    } +
                    (if (omitted > 0) "\n    … and $omitted more" else "") +
                    "\n  Back them up/remove them, or opt into cleanupLegacyLogoArtifacts for a reversible migration."
        )
    }

    companion object {
        internal const val RENDERER_FINGERPRINT_VERSION = "android-logo-renderer-v1"
        private const val MAX_RESOURCE_SCAN_ENTRIES = 10_000
        private const val MAX_DISPLAYED_COLLISIONS = 20

        // Density qualifier → scale factor. Adaptive canvas 108dp; legacy 48dp.
        internal val DENSITIES = listOf(
            "mdpi" to 1.0,
            "hdpi" to 1.5,
            "xhdpi" to 2.0,
            "xxhdpi" to 3.0,
            "xxxhdpi" to 4.0,
        )

        /** Stems of every launcher icon the plugin generates (as PNG). */
        internal val LAUNCHER_STEMS = setOf(
            "ic_launcher", "ic_launcher_round", "ic_launcher_foreground", "ic_launcher_background",
        )

        /**
         * Files under `mipmap-*` that share a launcher stem with a generated PNG but
         * carry a different extension (e.g. template `.webp`), so they'd collide in
         * the resource merge. Pure/shared so both the sync warning and the cleanup
         * task see the same set.
         */
        internal fun collidingTemplateIcons(
            resDir: File,
            maximumEntries: Int = MAX_RESOURCE_SCAN_ENTRIES,
        ): List<File> {
            require(maximumEntries > 0) { "maximumEntries must be positive" }
            val collisions = mutableListOf<File>()
            var entryCount = 0
            DENSITIES.forEach { (q, _) ->
                val directory = resDir.resolve("mipmap-$q").toPath()
                if (!Files.exists(directory, NOFOLLOW_LINKS)) return@forEach
                OwnedOutputSafety.requireSafePath(directory.toFile(), "Android mipmap directory")
                Files.newDirectoryStream(directory).use { entries ->
                    for (path in entries) {
                        entryCount += 1
                        if (entryCount > maximumEntries) {
                            throw GradleException(
                                "[kmpSsot] Refusing to scan more than $maximumEntries Android mipmap " +
                                    "entries below ${diagnosticSafeText(resDir.path)}.",
                            )
                        }
                        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        if (attrs.isSymbolicLink || attrs.isOther) {
                            throw GradleException(
                                "[kmpSsot] Refusing unsafe launcher resource entry: " +
                                    diagnosticSafeText(path.toString()),
                            )
                        }
                        val file = path.toFile()
                        if (
                            attrs.isRegularFile && file.nameWithoutExtension in LAUNCHER_STEMS &&
                                file.extension.lowercase() != "png"
                        ) {
                            collisions.add(file)
                        }
                    }
                }
            }
            return collisions.sortedBy { it.toPath().toAbsolutePath().normalize().toString() }
        }

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
            add("mipmap-anydpi-v33/ic_launcher.xml")
            add("mipmap-anydpi-v33/ic_launcher_round.xml")
        }
    }
}
