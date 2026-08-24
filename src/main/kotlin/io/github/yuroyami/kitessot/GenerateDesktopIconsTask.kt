package io.github.yuroyami.kitessot

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File

/** Apple's icon grid corner radius, as a fraction of the side. Do not pre-double: [applyRoundedRectMask] does that. */
private const val MACOS_CORNER_RATIO = 0.225
private const val BASE_SIZE = 1024

/**
 * Generate the macOS, Windows, and Linux installer icons from `logo { }` art into
 * a plugin-owned `build/` directory, following [GenerateIoWorkerTask].
 *
 * This is the automatic tier: output never leaves `build/`, so unlike the Android
 * and Apple logo installers this needs no ownership manifest, no backups, no
 * recovery area, and no opt-in task. `dryRun` is accepted and ignored (logged at
 * info) because generated output is a build input, not a source-tree mutation.
 */
@CacheableTask
abstract class GenerateDesktopIconsTask : DefaultTask() {

    init {
        group = "kitessot"
        description = "Generate the macOS, Windows, and Linux installer icons from logo { } art."
        generatedRoot.convention(project.layout.buildDirectory.dir("generated/kitessot"))
    }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val foreground: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val background: RegularFileProperty

    @get:[Input Optional]
    abstract val backgroundColor: Property<String>

    @get:Input
    abstract val roundMacOsIcon: Property<Boolean>

    /** Declared output so `iconFile.set(task.flatMap { outputDir.file(...) })` carries the dependency. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Safety boundary for plugin-owned generated output. */
    @get:Internal
    abstract val generatedRoot: DirectoryProperty

    /** Build-owned code generation must remain correct in preview mode; this generated input does not honor it. */
    @get:Internal
    abstract val dryRun: Property<Boolean>

    @TaskAction
    fun generate() {
        if (dryRun.getOrElse(false)) {
            logger.info("[kiteSsot] dryRun does not suppress build-owned desktop icon generation.")
        }
        val fg = decodeLogo(foreground.asFile.get(), "logo { foreground }").image
        val bg = if (backgroundColor.isPresent) {
            solidColorImage(BASE_SIZE, parseLogoBackgroundColor(backgroundColor.get()))
        } else {
            decodeLogo(background.asFile.get(), "logo { background }").image
        }

        // Same composite the Apple icon uses: BG covers, FG contains, flattened to opaque.
        val base = BufferedImage(BASE_SIZE, BASE_SIZE, BufferedImage.TYPE_INT_RGB).withGraphics {
            color = Color.WHITE
            fillRect(0, 0, BASE_SIZE, BASE_SIZE)
            drawCover(bg, 0, 0, BASE_SIZE, BASE_SIZE)
            drawContain(fg, 0, 0, BASE_SIZE, BASE_SIZE)
        }
        val macSource = if (roundMacOsIcon.get()) applyRoundedRectMask(base, MACOS_CORNER_RATIO) else base
        val files = mapOf(
            "app.icns" to writeIcns(macSource),
            "app.ico" to writeIco(base),
            "app.png" to encodePng(resize(base, 512, 512), "Linux app icon"),
        )
        OwnedOutputSafety.replaceGeneratedTree(
            outputRoot = outputDir.get().asFile,
            allowedRoot = generatedRoot.get().asFile,
            owner = "desktop-icons",
            files = files,
        )
        logger.info("[kiteSsot] Generated desktop icons: ${outputDir.get().asFile}")
    }

    private fun decodeLogo(file: File, label: String): LogoPngSnapshot = try {
        readBoundedLogoPngSnapshot(file, label)
    } catch (e: IllegalArgumentException) {
        throw GradleException("[kiteSsot] ${e.message}", e)
    }
}
