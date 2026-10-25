package io.github.yuroyami.kiteconfig

import io.github.yuroyami.kiteconfig.core.validateLogoSafeZoneRatio
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
import java.io.File

/**
 * Generate the debug launcher icons from `debug { logo { } }` art into a
 * plugin-owned `build/` directory, like [GenerateAndroidSplashTask].
 *
 * The output is attached as a generated resource directory to the debug build
 * types only, so it replaces the release icons there and nowhere else. Nothing
 * leaves `build/`: no receipt, no backup, and turning it off simply detaches it.
 */
@CacheableTask
internal abstract class GenerateAndroidDebugIconsTask : DefaultTask() {

    init {
        group = "kiteconfig"
        description = "Generate the Android debug launcher icons from debug { logo { } } art."
        generatedRoot.convention(project.layout.buildDirectory.dir("generated/kiteconfig"))
        emitMonochrome.convention(false)
    }

    /** Optional so missing art fails with the plugin's own message instead of Gradle's. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val foreground: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val background: RegularFileProperty

    @get:[Input Optional]
    abstract val backgroundColor: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val overlay: RegularFileProperty

    @get:Input
    abstract val safeZoneRatio: Property<Double>

    @get:Input
    abstract val emitMonochrome: Property<Boolean>

    /** Declared output so `addGeneratedSourceDirectory` carries the dependency. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Safety boundary for plugin-owned generated output. */
    @get:Internal
    abstract val generatedRoot: DirectoryProperty

    @TaskAction
    fun generate() {
        val fgFile = foreground.asFile.orNull ?: throw GradleException(
            "[kiteConfig] The Android debug icon has no foreground. Set logo { foreground } at the root, " +
                "or debug { logo { foreground } }.",
        )
        val mark = decodeLogo(fgFile, "debug { logo { foreground } }").image
        val fg = overlay.asFile.orNull
            ?.let { applyLogoOverlay(mark, decodeLogo(it, "debug { logo { overlay } }").image) }
            ?: mark
        val bg = if (backgroundColor.isPresent) {
            var color = parseLogoBackgroundColor(backgroundColor.get())
            if (color.alpha < 255) {
                logger.warn(
                    "[kiteConfig] debug { logo { background } } is semi-transparent, so it will be " +
                        "flattened over white to match the iOS icon.",
                )
                color = flattenOverWhite(color)
            }
            // 432px matches the xxxhdpi adaptive canvas; downscaled per density.
            solidColorImage(432, color)
        } else {
            val bgFile = background.asFile.orNull ?: throw GradleException(
                "[kiteConfig] The Android debug icon has no background. Set logo { background } at the " +
                    "root, or debug { logo { background } }.",
            )
            decodeLogo(bgFile, "debug { logo { background } }").image
        }
        val files = renderAndroidLauncherIcons(
            foreground = fg,
            background = bg,
            safeZoneRatio = validateLogoSafeZoneRatio(safeZoneRatio.get()),
            emitMonochrome = emitMonochrome.get(),
        )
        OwnedOutputSafety.replaceGeneratedTree(
            outputRoot = outputDir.get().asFile,
            allowedRoot = generatedRoot.get().asFile,
            owner = "android-debug-icons",
            files = files,
        )
        logger.info("[kiteConfig] Generated Android debug launcher icons: ${outputDir.get().asFile}")
    }

    private fun decodeLogo(file: File, label: String): LogoPngSnapshot = try {
        readBoundedLogoPngSnapshot(file, label)
    } catch (e: IllegalArgumentException) {
        throw GradleException("[kiteConfig] ${e.message}", e)
    }
}
